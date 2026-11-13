-- Integration smoke tests for V001-V013 on a clean disposable PostgreSQL database.
-- Covers request-level multi-Gateway distribution, safe failover, cancellation and
-- the existing 3030 attempt/result-correlating safety rules. All fixtures roll back.
BEGIN;

DO $test$
DECLARE
    user_a UUID; user_b UUID; tenant_a UUID;
    gateway_a UUID; gateway_b UUID; gateway_personal UUID;
    batch_ids UUID[] := ARRAY[]::UUID[];
    batch_id UUID; job_id UUID; first_job UUID; second_job UUID;
    assigned UUID; assigned_2 UUID; assigned_3 UUID; assigned_4 UUID; assigned_5 UUID;
    claim_row RECORD; claim_b RECORD; retry_claim RECORD; reaper_row RECORD; cancel_row RECORD;
    attempt_id UUID; lease_token TEXT; accepted BOOLEAN;
    ahead_count BIGINT; batch_no INTEGER; cache_first_job UUID;
BEGIN
    INSERT INTO dm_users(auth_subject) VALUES ('gateway-queue-test-user-a') RETURNING id INTO user_a;
    INSERT INTO dm_users(auth_subject) VALUES ('gateway-queue-test-user-b') RETURNING id INTO user_b;
    INSERT INTO dm_tenants(display_name) VALUES ('Gateway Multi-Queue Test Tenant') RETURNING id INTO tenant_a;
    INSERT INTO dm_tenant_memberships(tenant_id,user_id,role) VALUES
      (tenant_a,user_a,'OWNER'),(tenant_a,user_b,'MEMBER');

    -- Gateway A is the only eligible shared worker at first. Gateway B is registered
    -- but not ready. The personal gateway is reserved for its owner and is not pooled.
    INSERT INTO dm_gateways(owner_user_id,display_name,credential_hash,health_state,accept_pool_jobs,
                            sms_permission_ready,telephony_ready,network_ready,heartbeat_at)
    VALUES (user_a,'Queue Gateway A',digest(gen_random_bytes(32),'sha256'),'READY',TRUE,TRUE,TRUE,TRUE,now())
    RETURNING id INTO gateway_a;
    INSERT INTO dm_gateways(owner_user_id,display_name,credential_hash,health_state,accept_pool_jobs,
                            sms_permission_ready,telephony_ready,network_ready,heartbeat_at)
    VALUES (user_b,'Queue Gateway B',digest(gen_random_bytes(32),'sha256'),'NOT_READY',TRUE,FALSE,FALSE,FALSE,now())
    RETURNING id INTO gateway_b;
    INSERT INTO dm_gateways(owner_user_id,display_name,credential_hash,health_state,accept_pool_jobs,
                            sms_permission_ready,telephony_ready,network_ready,heartbeat_at)
    VALUES (user_a,'Personal Only Gateway',digest(gen_random_bytes(32),'sha256'),'READY',FALSE,TRUE,TRUE,TRUE,now())
    RETURNING id INTO gateway_personal;

    -- Five request batches: 15, 35, 20, 10 and 5 devices.
    FOR batch_no IN 1..5 LOOP
        INSERT INTO dm_check_batches(
            tenant_id,requested_by_user_id,client_idempotency_key,app_edition,total_jobs,request_fingerprint,created_at
        ) VALUES (
            tenant_a,
            CASE WHEN batch_no IN (1,3,5) THEN user_a ELSE user_b END,
            'multi-gateway-'||batch_no,'GATEWAY',
            CASE batch_no WHEN 1 THEN 15 WHEN 2 THEN 35 WHEN 3 THEN 20 WHEN 4 THEN 10 ELSE 5 END,
            digest('multi-gateway-'||batch_no,'sha256'),
            now()+make_interval(secs=>batch_no)
        ) RETURNING id INTO batch_id;
        batch_ids := array_append(batch_ids,batch_id);
        INSERT INTO dm_tenant_device_access(tenant_id,canonical_device_id)
        SELECT tenant_a,'QUEUE-'||batch_no||'-'||n
          FROM generate_series(1,CASE batch_no WHEN 1 THEN 15 WHEN 2 THEN 35 WHEN 3 THEN 20 WHEN 4 THEN 10 ELSE 5 END) n;
        INSERT INTO dm_check_jobs(batch_id,tenant_id,requested_by_user_id,canonical_device_id,query_profile)
        SELECT batch_id,tenant_a,
               CASE WHEN batch_no IN (1,3,5) THEN user_a ELSE user_b END,
               'QUEUE-'||batch_no||'-'||n,'standard'
          FROM generate_series(1,CASE batch_no WHEN 1 THEN 15 WHEN 2 THEN 35 WHEN 3 THEN 20 WHEN 4 THEN 10 ELSE 5 END) n;
    END LOOP;

    PERFORM dm_rebalance_unstarted_batches();
    IF EXISTS (SELECT 1 FROM dm_check_batches WHERE id=ANY(batch_ids) AND assigned_gateway_id IS DISTINCT FROM gateway_a) THEN
        RAISE EXCEPTION 'single eligible Gateway should initially own all five queued requests';
    END IF;

    -- Gateway A starts request 1. When Gateway B becomes ready, the remaining
    -- unstarted request batches distribute B/A/B/A while request 1 stays with A.
    SELECT * INTO claim_row FROM dm_claim_next_job(gateway_a);
    SELECT j.id INTO first_job FROM dm_check_jobs j WHERE j.batch_id=batch_ids[1] ORDER BY j.enqueued_at,j.id LIMIT 1;
    IF claim_row.job_id IS DISTINCT FROM first_job THEN
        RAISE EXCEPTION 'Gateway A did not start the oldest request batch';
    END IF;

    PERFORM dm_record_gateway_heartbeat(gateway_b,'test',TRUE,TRUE,TRUE,NULL);
    SELECT assigned_gateway_id INTO assigned_2 FROM dm_check_batches WHERE id=batch_ids[2];
    SELECT assigned_gateway_id INTO assigned_3 FROM dm_check_batches WHERE id=batch_ids[3];
    SELECT assigned_gateway_id INTO assigned_4 FROM dm_check_batches WHERE id=batch_ids[4];
    SELECT assigned_gateway_id INTO assigned_5 FROM dm_check_batches WHERE id=batch_ids[5];
    IF assigned_2 IS DISTINCT FROM gateway_b OR assigned_3 IS DISTINCT FROM gateway_a
       OR assigned_4 IS DISTINCT FROM gateway_b OR assigned_5 IS DISTINCT FROM gateway_a THEN
        RAISE EXCEPTION 'new Gateway did not rebalance queued requests in FIFO round-robin order';
    END IF;

    SELECT count(*) INTO ahead_count
      FROM dm_check_jobs j JOIN dm_check_batches b ON b.id=j.batch_id
     WHERE b.assigned_gateway_id=gateway_a
       AND b.id<>batch_ids[3]
       AND b.created_at < (SELECT created_at FROM dm_check_batches WHERE id=batch_ids[3])
       AND j.state IN ('QUEUED','RETRY_PENDING','LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS');
    IF ahead_count <> 15 THEN
        RAISE EXCEPTION 'request 3 should see 15 devices ahead on Gateway A, got %',ahead_count;
    END IF;
    SELECT count(*) INTO ahead_count
      FROM dm_check_jobs j JOIN dm_check_batches b ON b.id=j.batch_id
     WHERE b.assigned_gateway_id=gateway_a AND b.id<>batch_ids[5]
       AND b.created_at < (SELECT created_at FROM dm_check_batches WHERE id=batch_ids[5])
       AND j.state IN ('QUEUED','RETRY_PENDING','LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS');
    IF ahead_count <> 35 THEN
        RAISE EXCEPTION 'request 5 should see 35 devices ahead on Gateway A, got %',ahead_count;
    END IF;
    SELECT count(*) INTO ahead_count
      FROM dm_check_jobs j JOIN dm_check_batches b ON b.id=j.batch_id
     WHERE b.assigned_gateway_id=gateway_b AND b.id<>batch_ids[4]
       AND b.created_at < (SELECT created_at FROM dm_check_batches WHERE id=batch_ids[4])
       AND j.state IN ('QUEUED','RETRY_PENDING','LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS');
    IF ahead_count <> 35 THEN
        RAISE EXCEPTION 'request 4 should see 35 devices ahead on Gateway B, got %',ahead_count;
    END IF;
    SELECT count(*) INTO ahead_count FROM dm_check_jobs
     WHERE state IN ('QUEUED','RETRY_PENDING','LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS');
    IF ahead_count <> 85 THEN
        RAISE EXCEPTION 'global queue should contain 85 pending devices, got %',ahead_count;
    END IF;

    SELECT * INTO claim_b FROM dm_claim_next_job(gateway_b);
    SELECT j.id INTO job_id FROM dm_check_jobs j WHERE j.batch_id=batch_ids[2] ORDER BY j.enqueued_at,j.id LIMIT 1;
    IF claim_b.job_id IS DISTINCT FROM job_id THEN
        RAISE EXCEPTION 'Gateway B did not start request 2 after becoming ready';
    END IF;

    -- Once a request has started, a normal rebalance between SMS attempts must
    -- keep the remaining devices on that Gateway while it remains healthy.
    accepted := dm_mark_attempt_sending(gateway_a,claim_row.job_id,claim_row.attempt_id,claim_row.lease_token);
    IF NOT accepted THEN RAISE EXCEPTION 'could not mark Gateway A's first request sending'; END IF;
    accepted := dm_mark_attempt_awaiting_reply(gateway_a,claim_row.job_id,claim_row.attempt_id,
                                               claim_row.lease_token,now(),120);
    IF NOT accepted THEN RAISE EXCEPTION 'could not await Gateway A's first request reply'; END IF;
    accepted := dm_complete_job_result(
        gateway_a,claim_row.job_id,claim_row.attempt_id,claim_row.lease_token,
        CURRENT_DATE,now(),now(),
        '{"todayCount":3,"mtdCount":30,"active":20,"bar48":1,"bar96":0,"duplicates":0,"monthlyTarget":100}'::jsonb,
        'controller-test-parser');
    IF NOT accepted THEN RAISE EXCEPTION 'Gateway A first request result was not accepted'; END IF;
    PERFORM dm_rebalance_unstarted_batches();
    IF (SELECT assigned_gateway_id FROM dm_check_batches WHERE id=batch_ids[1]) IS DISTINCT FROM gateway_a
       OR (SELECT assigned_gateway_id FROM dm_check_batches WHERE id=batch_ids[2]) IS DISTINCT FROM gateway_b THEN
        RAISE EXCEPTION 'started request batches moved during a normal between-device rebalance';
    END IF;

    -- Historical analytics snapshot captures the live shared workload without
    -- storing any other user's device identifiers in the snapshot table.
    PERFORM dm_capture_tenant_queue_snapshots();
    IF (SELECT pending_devices FROM dm_tenant_queue_snapshots
         WHERE tenant_id=tenant_a ORDER BY captured_at DESC,id DESC LIMIT 1) <> 84 THEN
        RAISE EXCEPTION 'queue analytics snapshot did not record 84 remaining devices';
    END IF;

    -- Gateway B goes unavailable. Its pre-send leased request is held until the
    -- lease is safely reaped; unstarted request 4 is immediately reassigned to A.
    UPDATE dm_gateways SET heartbeat_at=now()-INTERVAL '2 minutes' WHERE id=gateway_b;
    IF dm_mark_stale_gateways_offline() <> 1 THEN
        RAISE EXCEPTION 'stale Gateway heartbeat was not detected';
    END IF;
    IF (SELECT health_state FROM dm_gateways WHERE id=gateway_b) <> 'OFFLINE' THEN
        RAISE EXCEPTION 'stale Gateway was not marked offline';
    END IF;
    IF (SELECT assigned_gateway_id FROM dm_check_batches WHERE id=batch_ids[4]) IS DISTINCT FROM gateway_a THEN
        RAISE EXCEPTION 'unstarted request 4 was not reassigned after Gateway B went offline';
    END IF;
    UPDATE dm_job_attempts SET lease_expires_at=now()-INTERVAL '1 second'
     WHERE id=claim_b.attempt_id;
    SELECT * INTO reaper_row FROM dm_reap_expired_attempts();
    IF reaper_row.requeued_jobs <> 1 THEN
        RAISE EXCEPTION 'pre-send expiry was not safely requeued';
    END IF;
    PERFORM dm_rebalance_unstarted_batches();
    IF (SELECT assigned_gateway_id FROM dm_check_batches WHERE id=batch_ids[2]) IS DISTINCT FROM gateway_a THEN
        RAISE EXCEPTION 'request 2 was not reassigned after its pre-send lease expired';
    END IF;

    -- Clear the five queue fixtures after checking distribution and failover.
    FOR batch_no IN 1..5 LOOP
        PERFORM * FROM dm_cancel_check_batch(batch_ids[batch_no],tenant_a,
            CASE WHEN batch_no IN (1,3,5) THEN user_a ELSE user_b END,'cleanup after queue test');
    END LOOP;

    -- Personal gateway is owner-only and remains available even when not in the pool.
    INSERT INTO dm_tenant_device_access(tenant_id,canonical_device_id)
    VALUES (tenant_a,'CACHE-DEVICE'),(tenant_a,'RECOVERY-DEVICE'),
           (tenant_a,'CANCEL-QUEUED-1'),(tenant_a,'CANCEL-QUEUED-2'),
           (tenant_a,'CANCEL-INFLIGHT-1'),(tenant_a,'CANCEL-INFLIGHT-2');

    INSERT INTO dm_check_batches(tenant_id,requested_by_user_id,client_idempotency_key,app_edition,total_jobs,request_fingerprint)
    VALUES (tenant_a,user_a,'cache-first','GATEWAY',1,digest('cache-first','sha256')) RETURNING id INTO batch_id;
    INSERT INTO dm_check_jobs(batch_id,tenant_id,requested_by_user_id,canonical_device_id,query_profile,preferred_gateway_id)
    VALUES (batch_id,tenant_a,user_a,'CACHE-DEVICE','standard',gateway_personal);
    PERFORM dm_rebalance_unstarted_batches();
    SELECT * INTO claim_row FROM dm_claim_next_job(gateway_personal);
    cache_first_job := claim_row.job_id;
    accepted := dm_mark_attempt_sending(gateway_personal,claim_row.job_id,claim_row.attempt_id,claim_row.lease_token);
    IF NOT accepted THEN RAISE EXCEPTION 'could not start first cache attempt'; END IF;
    accepted := dm_mark_attempt_awaiting_reply(gateway_personal,claim_row.job_id,claim_row.attempt_id,
                                               claim_row.lease_token,now(),120);
    IF NOT accepted THEN RAISE EXCEPTION 'could not await first cache reply'; END IF;
    accepted := dm_complete_job_result(
        gateway_personal,claim_row.job_id,claim_row.attempt_id,claim_row.lease_token,
        CURRENT_DATE,now(),now(),
        '{"todayCount":12,"mtdCount":120,"active":90,"bar48":4,"bar96":2,"duplicates":0,"monthlyTarget":200}'::jsonb,
        'controller-test-parser');
    IF NOT accepted THEN RAISE EXCEPTION 'first cache result was not accepted'; END IF;

    INSERT INTO dm_check_batches(tenant_id,requested_by_user_id,client_idempotency_key,app_edition,total_jobs,request_fingerprint)
    VALUES (tenant_a,user_a,'cache-delayed','GATEWAY',1,digest('cache-delayed','sha256')) RETURNING id INTO batch_id;
    INSERT INTO dm_check_jobs(batch_id,tenant_id,requested_by_user_id,canonical_device_id,query_profile,preferred_gateway_id)
    VALUES (batch_id,tenant_a,user_a,'CACHE-DEVICE','standard',gateway_personal);
    PERFORM dm_rebalance_unstarted_batches();
    SELECT * INTO claim_row FROM dm_claim_next_job(gateway_personal);
    accepted := dm_mark_attempt_sending(gateway_personal,claim_row.job_id,claim_row.attempt_id,claim_row.lease_token);
    IF NOT accepted THEN RAISE EXCEPTION 'could not start delayed cache attempt'; END IF;
    accepted := dm_mark_attempt_awaiting_reply(gateway_personal,claim_row.job_id,claim_row.attempt_id,
                                               claim_row.lease_token,now(),120);
    IF NOT accepted THEN RAISE EXCEPTION 'could not await delayed cache reply'; END IF;
    accepted := dm_complete_job_result(
        gateway_personal,claim_row.job_id,claim_row.attempt_id,claim_row.lease_token,
        CURRENT_DATE,now()-INTERVAL '1 minute',now(),
        '{"todayCount":1,"mtdCount":1,"active":1,"bar48":0,"bar96":0,"duplicates":0,"monthlyTarget":200}'::jsonb,
        'controller-test-parser');
    IF NOT accepted THEN RAISE EXCEPTION 'delayed cache result was not accepted'; END IF;
    IF (SELECT r.job_id FROM dm_daily_sms_results r
         WHERE r.tenant_id=tenant_a AND r.canonical_device_id='CACHE-DEVICE'
           AND r.business_date=CURRENT_DATE) IS DISTINCT FROM cache_first_job THEN
        RAISE EXCEPTION 'older delayed response overwrote the newer daily cache row';
    END IF;
    IF (SELECT values_json->>'todayCount' FROM dm_daily_sms_results
         WHERE tenant_id=tenant_a AND canonical_device_id='CACHE-DEVICE'
           AND business_date=CURRENT_DATE) <> '12' THEN
        RAISE EXCEPTION 'older delayed response overwrote the newer daily cache';
    END IF;

    -- Pre-send lease expiry can be retried safely by the same still-ready Gateway.
    INSERT INTO dm_check_batches(tenant_id,requested_by_user_id,client_idempotency_key,app_edition,total_jobs,request_fingerprint)
    VALUES (tenant_a,user_a,'recovery','GATEWAY',1,digest('recovery','sha256')) RETURNING id INTO batch_id;
    INSERT INTO dm_check_jobs(batch_id,tenant_id,requested_by_user_id,canonical_device_id,query_profile,preferred_gateway_id)
    VALUES (batch_id,tenant_a,user_a,'RECOVERY-DEVICE','standard',gateway_personal);
    PERFORM dm_rebalance_unstarted_batches();
    SELECT * INTO claim_row FROM dm_claim_next_job(gateway_personal);
    UPDATE dm_job_attempts SET lease_expires_at=now()-INTERVAL '1 second' WHERE id=claim_row.attempt_id;
    SELECT * INTO reaper_row FROM dm_reap_expired_attempts();
    IF reaper_row.requeued_jobs <> 1 THEN RAISE EXCEPTION 'pre-send expiry did not requeue'; END IF;
    SELECT * INTO retry_claim FROM dm_claim_next_job(gateway_personal);
    IF retry_claim.job_id IS DISTINCT FROM claim_row.job_id THEN
        RAISE EXCEPTION 'same Gateway did not safely retry its pre-send job';
    END IF;
    accepted := dm_mark_attempt_sending(gateway_personal,retry_claim.job_id,retry_claim.attempt_id,retry_claim.lease_token);
    IF NOT accepted THEN RAISE EXCEPTION 'could not start recovery attempt'; END IF;
    UPDATE dm_job_attempts SET lease_expires_at=now()-INTERVAL '1 second' WHERE id=retry_claim.attempt_id;
    SELECT * INTO reaper_row FROM dm_reap_expired_attempts();
    IF reaper_row.quarantined_gateways <> 1 THEN RAISE EXCEPTION 'post-send expiry did not quarantine Gateway'; END IF;
    IF (SELECT state FROM dm_check_jobs WHERE id=retry_claim.job_id) <> 'AMBIGUOUS' THEN
        RAISE EXCEPTION 'post-send expiry did not mark job ambiguous';
    END IF;
    INSERT INTO dm_controller_operators(user_id) VALUES (user_a);
    accepted := dm_operator_resolve_ambiguous_attempt(
        retry_claim.attempt_id,user_a,'REQUEUE_AFTER_INBOX_CLEARED',
        'Test operator verified the old inbox transaction is fully resolved.');
    IF NOT accepted THEN RAISE EXCEPTION 'operator could not resolve ambiguous attempt'; END IF;
    accepted := dm_operator_resolve_gateway_quarantine(
        gateway_personal,user_a,'Test operator confirmed inbox cleared and safe for new work.');
    IF NOT accepted THEN RAISE EXCEPTION 'operator could not release Gateway quarantine'; END IF;

    -- User can cancel an untouched queue; every unstarted device is cancelled.
    INSERT INTO dm_check_batches(tenant_id,requested_by_user_id,client_idempotency_key,app_edition,total_jobs,request_fingerprint)
    VALUES (tenant_a,user_b,'cancel-queued','GATEWAY',2,digest('cancel-queued','sha256')) RETURNING id INTO batch_id;
    INSERT INTO dm_check_jobs(batch_id,tenant_id,requested_by_user_id,canonical_device_id,query_profile)
    VALUES (batch_id,tenant_a,user_b,'CANCEL-QUEUED-1','standard'),
           (batch_id,tenant_a,user_b,'CANCEL-QUEUED-2','standard');
    PERFORM dm_rebalance_unstarted_batches();
    SELECT * INTO cancel_row FROM dm_cancel_check_batch(batch_id,tenant_a,user_b,'user cancelled');
    IF cancel_row.batch_status <> 'CANCELLED' OR cancel_row.cancelled_jobs <> 2 THEN
        RAISE EXCEPTION 'queued request cancellation did not cancel both jobs';
    END IF;

    -- An already-sent SMS is not interrupted. Other devices stop, and the request
    -- becomes PARTIAL once the in-flight result arrives.
    INSERT INTO dm_check_batches(tenant_id,requested_by_user_id,client_idempotency_key,app_edition,total_jobs,request_fingerprint)
    VALUES (tenant_a,user_a,'cancel-inflight','GATEWAY',2,digest('cancel-inflight','sha256')) RETURNING id INTO batch_id;
    INSERT INTO dm_check_jobs(batch_id,tenant_id,requested_by_user_id,canonical_device_id,query_profile,preferred_gateway_id)
    VALUES (batch_id,tenant_a,user_a,'CANCEL-INFLIGHT-1','standard',gateway_personal),
           (batch_id,tenant_a,user_a,'CANCEL-INFLIGHT-2','standard',gateway_personal);
    PERFORM dm_rebalance_unstarted_batches();
    SELECT * INTO claim_row FROM dm_claim_next_job(gateway_personal);
    accepted := dm_mark_attempt_sending(gateway_personal,claim_row.job_id,claim_row.attempt_id,claim_row.lease_token);
    IF NOT accepted THEN RAISE EXCEPTION 'could not begin in-flight cancellation test'; END IF;
    accepted := dm_mark_attempt_awaiting_reply(gateway_personal,claim_row.job_id,claim_row.attempt_id,
                                               claim_row.lease_token,now(),120);
    IF NOT accepted THEN RAISE EXCEPTION 'could not await in-flight cancellation test'; END IF;
    SELECT * INTO cancel_row FROM dm_cancel_check_batch(batch_id,tenant_a,user_a,'cancel remaining devices');
    IF cancel_row.in_flight_jobs <> 1 OR cancel_row.cancelled_jobs <> 1
       OR cancel_row.batch_status <> 'RUNNING' THEN
        RAISE EXCEPTION 'cancellation interrupted an in-flight SMS or failed to stop remaining jobs';
    END IF;
    accepted := dm_complete_job_result(
        gateway_personal,claim_row.job_id,claim_row.attempt_id,claim_row.lease_token,
        CURRENT_DATE,now(),now(),
        '{"todayCount":4,"mtdCount":40,"active":30,"bar48":1,"bar96":0,"duplicates":0,"monthlyTarget":100}'::jsonb,
        'controller-test-parser');
    IF NOT accepted THEN RAISE EXCEPTION 'in-flight SMS result was not accepted after cancellation'; END IF;
    IF (SELECT status FROM dm_check_batches WHERE id=batch_id) <> 'PARTIAL' THEN
        RAISE EXCEPTION 'cancelled request did not settle to PARTIAL after in-flight completion';
    END IF;
END;
$test$;

ROLLBACK;
