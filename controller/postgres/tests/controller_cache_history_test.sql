-- Shared daily cache and two-month history isolation regression.
-- A different authorised member must be able to read a device result without
-- having submitted the original check. A different tenant must not see it.
BEGIN;

DO $cache_history$
DECLARE
    owner_id UUID;
    member_id UUID;
    outsider_id UUID;
    tenant_id UUID;
    outsider_tenant_id UUID;
    gateway_id UUID;
    batch_id UUID;
    job_id UUID;
    attempt_id UUID;
    day_offset INTEGER;
    shared_rows INTEGER;
    outsider_rows INTEGER;
    newest_value TEXT;
BEGIN
    INSERT INTO dm_users(auth_subject) VALUES ('cache-history-owner')
    RETURNING id INTO owner_id;
    INSERT INTO dm_users(auth_subject) VALUES ('cache-history-authorized-member')
    RETURNING id INTO member_id;
    INSERT INTO dm_users(auth_subject) VALUES ('cache-history-outsider')
    RETURNING id INTO outsider_id;

    INSERT INTO dm_tenants(display_name) VALUES ('Cache History Shared Tenant')
    RETURNING id INTO tenant_id;
    INSERT INTO dm_tenants(display_name) VALUES ('Cache History Separate Tenant')
    RETURNING id INTO outsider_tenant_id;

    INSERT INTO dm_tenant_memberships(tenant_id,user_id,role) VALUES
      (tenant_id,owner_id,'OWNER'),
      (tenant_id,member_id,'MEMBER'),
      (outsider_tenant_id,outsider_id,'OWNER');

    INSERT INTO dm_tenant_device_access(tenant_id,canonical_device_id)
    VALUES (tenant_id,'CACHE-HISTORY-SHARED-DEVICE');

    INSERT INTO dm_gateways(
      owner_user_id,display_name,credential_hash,health_state,
      sms_permission_ready,telephony_ready,network_ready,heartbeat_at
    ) VALUES (
      owner_id,'Cache History Test Gateway',
      digest(gen_random_bytes(32),'sha256'),'READY',TRUE,TRUE,TRUE,now()
    ) RETURNING id INTO gateway_id;

    INSERT INTO dm_check_batches(
      tenant_id,requested_by_user_id,client_idempotency_key,app_edition,
      total_jobs,request_fingerprint
    ) VALUES (
      tenant_id,owner_id,'cache-history-fixture','GATEWAY',3,
      digest('cache-history-fixture','sha256')
    ) RETURNING id INTO batch_id;

    -- Results from one requester are visible to every authorised member of
    -- the same tenant, even when the second member never requested this device.
    FOR day_offset IN 0..2 LOOP
      INSERT INTO dm_check_jobs(
        batch_id,tenant_id,requested_by_user_id,canonical_device_id,query_profile,state
      ) VALUES (
        batch_id,tenant_id,owner_id,'CACHE-HISTORY-SHARED-DEVICE','standard','COMPLETED'
      ) RETURNING id INTO job_id;

      attempt_id := gen_random_uuid();
      INSERT INTO dm_job_attempts(
        id,job_id,gateway_id,attempt_number,lease_token_hash,state,lease_expires_at,completed_at
      ) VALUES (
        attempt_id,job_id,gateway_id,1,
        digest(gen_random_bytes(32),'sha256'),'COMPLETED',now(),now()
      );

      INSERT INTO dm_daily_sms_results(
        tenant_id,canonical_device_id,business_date,checked_at,received_at,
        values_json,gateway_id,job_id,attempt_id,parser_version
      ) VALUES (
        tenant_id,'CACHE-HISTORY-SHARED-DEVICE',CURRENT_DATE-day_offset,
        now()-make_interval(days=>day_offset),now()-make_interval(days=>day_offset),
        jsonb_build_object('todayCount',100-day_offset,'mtdCount',500-day_offset),
        gateway_id,job_id,attempt_id,'cache-history-test'
      );
    END LOOP;

    SELECT count(*) INTO shared_rows
      FROM dm_daily_sms_results
     WHERE tenant_id=tenant_id
       AND canonical_device_id='CACHE-HISTORY-SHARED-DEVICE'
       AND business_date BETWEEN CURRENT_DATE-INTERVAL '61 days' AND CURRENT_DATE;
    IF shared_rows <> 3 THEN
      RAISE EXCEPTION 'authorised shared tenant history fixture expected 3 rows, got %',shared_rows;
    END IF;

    SELECT values_json->>'todayCount' INTO newest_value
      FROM dm_daily_sms_results
     WHERE tenant_id=tenant_id
       AND canonical_device_id='CACHE-HISTORY-SHARED-DEVICE'
     ORDER BY checked_at DESC LIMIT 1;
    IF newest_value <> '100' THEN
      RAISE EXCEPTION 'latest shared cache value was not returned; got %',newest_value;
    END IF;

    SELECT count(*) INTO outsider_rows
      FROM dm_daily_sms_results
     WHERE tenant_id=outsider_tenant_id
       AND canonical_device_id='CACHE-HISTORY-SHARED-DEVICE';
    IF outsider_rows <> 0 THEN
      RAISE EXCEPTION 'another tenant unexpectedly sees shared cache rows';
    END IF;

    IF NOT EXISTS (
      SELECT 1 FROM dm_tenant_memberships
       WHERE tenant_id=tenant_id AND user_id=member_id
    ) THEN
      RAISE EXCEPTION 'second authorised member is not in shared tenant';
    END IF;
    IF EXISTS (
      SELECT 1 FROM dm_tenant_memberships
       WHERE tenant_id=tenant_id AND user_id=outsider_id
    ) THEN
      RAISE EXCEPTION 'outside user unexpectedly belongs to shared tenant';
    END IF;
END;
$cache_history$;

ROLLBACK;
