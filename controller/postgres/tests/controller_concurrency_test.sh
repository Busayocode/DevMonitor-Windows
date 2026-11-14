#!/usr/bin/env bash
set -euo pipefail

# This test deliberately uses independent psql processes. Unlike the SQL smoke
# test, setup data is committed so concurrent sessions can observe the same jobs.
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

psql -X -v ON_ERROR_STOP=1 <<'SQL'
INSERT INTO dm_users(auth_subject) VALUES
  ('controller-concurrency-user-a'),
  ('controller-concurrency-user-b'),
  ('controller-concurrency-user-c');

INSERT INTO dm_tenants(display_name) VALUES
  ('Controller Concurrency Tenant A'),
  ('Controller Concurrency Tenant B'),
  ('Controller Same-Gateway Concurrency Tenant');

INSERT INTO dm_tenant_memberships(tenant_id,user_id,role)
SELECT t.id,u.id,'OWNER'
  FROM (VALUES
    ('Controller Concurrency Tenant A','controller-concurrency-user-a'),
    ('Controller Concurrency Tenant B','controller-concurrency-user-b'),
    ('Controller Same-Gateway Concurrency Tenant','controller-concurrency-user-c')
  ) AS fixture(tenant_name,subject)
  JOIN dm_tenants t ON t.display_name=fixture.tenant_name
  JOIN dm_users u ON u.auth_subject=fixture.subject;

INSERT INTO dm_gateways(
  owner_user_id,display_name,credential_hash,health_state,accept_pool_jobs,
  sms_permission_ready,telephony_ready,network_ready,heartbeat_at
)
SELECT u.id,fixture.gateway_name,digest(gen_random_bytes(32),'sha256'),
       'READY',TRUE,TRUE,TRUE,TRUE,now()
  FROM (VALUES
    ('controller-concurrency-user-a','Controller Concurrency Gateway A'),
    ('controller-concurrency-user-b','Controller Concurrency Gateway B'),
    ('controller-concurrency-user-c','Controller Same Gateway')
  ) AS fixture(subject,gateway_name)
  JOIN dm_users u ON u.auth_subject=fixture.subject;

-- Two independent tenants let separate gateways claim simultaneously without
-- contending on a shared tenant dispatch cursor.
INSERT INTO dm_check_batches(
  tenant_id,requested_by_user_id,client_idempotency_key,app_edition,
  total_jobs,request_fingerprint,assigned_gateway_id
)
SELECT t.id,u.id,'parallel-claim-'||fixture.suffix,'GATEWAY',1,
       digest('parallel-claim-'||fixture.suffix,'sha256'),g.id
  FROM (VALUES
    ('Controller Concurrency Tenant A','controller-concurrency-user-a',
     'Controller Concurrency Gateway A','a'),
    ('Controller Concurrency Tenant B','controller-concurrency-user-b',
     'Controller Concurrency Gateway B','b')
  ) AS fixture(tenant_name,subject,gateway_name,suffix)
  JOIN dm_tenants t ON t.display_name=fixture.tenant_name
  JOIN dm_users u ON u.auth_subject=fixture.subject
  JOIN dm_gateways g ON g.display_name=fixture.gateway_name;

INSERT INTO dm_tenant_device_access(tenant_id,canonical_device_id)
SELECT t.id,'CONCURRENT-DEVICE-'||fixture.suffix
  FROM (VALUES
    ('Controller Concurrency Tenant A','CONCURRENT-DEVICE-a','a'),
    ('Controller Concurrency Tenant B','CONCURRENT-DEVICE-b','b')
  ) AS fixture(tenant_name,device_id,suffix)
  JOIN dm_tenants t ON t.display_name=fixture.tenant_name;

INSERT INTO dm_check_jobs(
  batch_id,tenant_id,requested_by_user_id,canonical_device_id,query_profile
)
SELECT b.id,b.tenant_id,b.requested_by_user_id,
       'CONCURRENT-DEVICE-'||fixture.suffix,'standard'
  FROM (VALUES
    ('Controller Concurrency Tenant A','CONCURRENT-DEVICE-a','a'),
    ('Controller Concurrency Tenant B','CONCURRENT-DEVICE-b','b')
  ) AS fixture(tenant_name,device_id,suffix)
  JOIN dm_tenants t ON t.display_name=fixture.tenant_name
  JOIN dm_check_batches b ON b.tenant_id=t.id
    AND b.client_idempotency_key='parallel-claim-'||fixture.suffix;

-- One gateway with two jobs tests the one-active-SMS-transaction invariant.
INSERT INTO dm_check_batches(
  tenant_id,requested_by_user_id,client_idempotency_key,app_edition,
  total_jobs,request_fingerprint,assigned_gateway_id
)
SELECT t.id,u.id,'same-gateway-claim','GATEWAY',2,
       digest('same-gateway-claim','sha256'),g.id
  FROM dm_tenants t
  JOIN dm_users u ON u.auth_subject='controller-concurrency-user-c'
  JOIN dm_gateways g ON g.display_name='Controller Same Gateway'
 WHERE t.display_name='Controller Same-Gateway Concurrency Tenant';

INSERT INTO dm_tenant_device_access(tenant_id,canonical_device_id)
SELECT t.id,d.device_id
  FROM dm_tenants t
  CROSS JOIN (VALUES ('SAME-GATEWAY-DEVICE-1'),('SAME-GATEWAY-DEVICE-2')) d(device_id)
 WHERE t.display_name='Controller Same-Gateway Concurrency Tenant';

INSERT INTO dm_check_jobs(
  batch_id,tenant_id,requested_by_user_id,canonical_device_id,query_profile
)
SELECT b.id,b.tenant_id,b.requested_by_user_id,d.device_id,'standard'
  FROM dm_check_batches b
  CROSS JOIN (VALUES ('SAME-GATEWAY-DEVICE-1'),('SAME-GATEWAY-DEVICE-2')) d(device_id)
 WHERE b.client_idempotency_key='same-gateway-claim';
SQL

gateway_a="$(psql -X -qAt -c "SELECT id FROM dm_gateways WHERE display_name='Controller Concurrency Gateway A'")"
gateway_b="$(psql -X -qAt -c "SELECT id FROM dm_gateways WHERE display_name='Controller Concurrency Gateway B'")"
gateway_c="$(psql -X -qAt -c "SELECT id FROM dm_gateways WHERE display_name='Controller Same Gateway'")"

# Start two genuinely separate PostgreSQL sessions at nearly the same time.
psql -X -qAt -c "SELECT pg_sleep(0.2); SELECT job_id FROM dm_claim_next_job('$gateway_a')" >"$tmp_dir/parallel_a" &
pid_a=$!
psql -X -qAt -c "SELECT pg_sleep(0.2); SELECT job_id FROM dm_claim_next_job('$gateway_b')" >"$tmp_dir/parallel_b" &
pid_b=$!
wait "$pid_a"
wait "$pid_b"

job_a="$(tr -d '[:space:]' <"$tmp_dir/parallel_a")"
job_b="$(tr -d '[:space:]' <"$tmp_dir/parallel_b")"
if [[ -z "$job_a" || -z "$job_b" || "$job_a" == "$job_b" ]]; then
  echo "Concurrent independent-gateway claims did not return two distinct jobs."
  echo "Gateway A result: $job_a"
  echo "Gateway B result: $job_b"
  exit 1
fi

active_parallel="$(psql -X -qAt -c "SELECT count(*) FROM dm_job_attempts WHERE job_id IN ('$job_a'::uuid,'$job_b'::uuid) AND state='LEASED'")"
distinct_parallel_gateways="$(psql -X -qAt -c "SELECT count(DISTINCT gateway_id) FROM dm_job_attempts WHERE job_id IN ('$job_a'::uuid,'$job_b'::uuid) AND state='LEASED'")"
if [[ "$active_parallel" != "2" || "$distinct_parallel_gateways" != "2" ]]; then
  echo "Expected two active leases owned by two different gateways; got leases=$active_parallel gateways=$distinct_parallel_gateways."
  exit 1
fi

# Hold the same gateway row briefly so both claim sessions queue behind its
# row lock. Once released, one call must win; the other must see the active
# lease and return no job rather than starting a second SMS transaction.
psql -X -qAt -c "BEGIN; SELECT id FROM dm_gateways WHERE id='$gateway_c' FOR UPDATE; SELECT pg_sleep(1); COMMIT" >/dev/null &
lock_pid=$!
sleep 0.1
psql -X -qAt -c "SELECT job_id FROM dm_claim_next_job('$gateway_c')" >"$tmp_dir/same_a" &
same_a=$!
psql -X -qAt -c "SELECT job_id FROM dm_claim_next_job('$gateway_c')" >"$tmp_dir/same_b" &
same_b=$!
wait "$lock_pid"
wait "$same_a"
wait "$same_b"

same_count=0
[[ -n "$(tr -d '[:space:]' <"$tmp_dir/same_a")" ]] && ((same_count+=1))
[[ -n "$(tr -d '[:space:]' <"$tmp_dir/same_b")" ]] && ((same_count+=1))
if [[ "$same_count" -ne 1 ]]; then
  echo "Expected exactly one of two simultaneous same-gateway claims to win; got $same_count."
  echo "Claim A: $(cat "$tmp_dir/same_a")"
  echo "Claim B: $(cat "$tmp_dir/same_b")"
  exit 1
fi

psql -X -v ON_ERROR_STOP=1 <<'SQL'
DO $assert$
BEGIN
  IF (SELECT count(*) FROM dm_job_attempts a
       JOIN dm_gateways g ON g.id=a.gateway_id
       WHERE g.display_name='Controller Same Gateway'
         AND a.state IN ('LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS')) <> 1 THEN
    RAISE EXCEPTION 'same gateway has more than one unresolved active SMS attempt';
  END IF;
  IF (SELECT count(*) FROM dm_check_jobs j
       JOIN dm_check_batches b ON b.id=j.batch_id
       WHERE b.client_idempotency_key='same-gateway-claim'
         AND j.state='QUEUED') <> 1 THEN
    RAISE EXCEPTION 'same-gateway concurrent test did not leave exactly one job queued';
  END IF;
END;
$assert$;
SQL

echo "PostgreSQL concurrent claim tests passed: two independent gateways claimed distinct jobs concurrently, and one gateway admitted only one active SMS attempt."
