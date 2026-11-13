-- Integration smoke test for V001-V005. Run against a clean disposable database after migrations.
-- All fixtures are rolled back at the end.
BEGIN;

DO $test$
DECLARE
    user_a UUID; user_b UUID;
    tenant_a UUID; tenant_b UUID; tenant_c UUID; tenant_d UUID;
    gateway_personal UUID; gateway_pool UUID; gateway_f1 UUID; gateway_f2 UUID; gateway_f3 UUID;
    batch_a UUID; batch_b UUID; batch_c UUID; batch_d UUID;
    job_personal UUID; job_shared UUID; job_c UUID; job_d UUID;
    claim_row RECORD; claim_f1 RECORD; claim_f2 RECORD; claim_f3 RECORD;
    reaper_row RECORD;
    personal_attempt UUID; personal_token TEXT;
    attempt_f1 UUID; attempt_f3 UUID; token_f3 TEXT;
    accepted BOOLEAN;
BEGIN
    INSERT INTO dm_users(auth_subject) VALUES ('controller-test-user-a') RETURNING id INTO user_a;
    INSERT INTO dm_users(auth_subject) VALUES ('controller-test-user-b') RETURNING id INTO user_b;

    INSERT INTO dm_tenants(display_name) VALUES ('Controller Test Tenant A') RETURNING id INTO tenant_a;
    INSERT INTO dm_tenants(display_name) VALUES ('Controller Test Tenant B') RETURNING id INTO tenant_b;
    INSERT INTO dm_tenants(display_name) VALUES ('Controller Fair Tenant C') RETURNING id INTO tenant_c;
    INSERT INTO dm_tenants(display_name) VALUES ('Controller Fair Tenant D') RETURNING id INTO tenant_d;

    INSERT INTO dm_tenant_memberships(tenant_id,user_id,role) VALUES
      (tenant_a,user_a,'OWNER'), (tenant_b,user_b,'OWNER'),
      (tenant_c,user_a,'OWNER'), (tenant_d,user_b,'OWNER');

    INSERT INTO dm_gateways(owner_user_id,display_name,credential_hash,health_state,accept_pool_jobs,
                            sms_permission_ready,telephony_ready,network_ready,heartbeat_at)
    VALUES (user_a,'Personal Gateway',digest(gen_random_bytes(32),'sha256'),'READY',TRUE,TRUE,TRUE,TRUE,now())
    RETURNING id INTO gateway_personal;
    INSERT INTO dm_gateways(owner_user_id,display_name,credential_hash,health_state,accept_pool_jobs,
                            sms_permission_ready,telephony_ready,network_ready,heartbeat_at)
    VALUES (user_b,'Shared Pool Gateway',digest(gen_random_bytes(32),'sha256'),'READY',TRUE,TRUE,TRUE,TRUE,now())
    RETURNING id INTO gateway_pool;
    INSERT INTO dm_gateways(owner_user_id,display_name,credential_hash,health_state,accept_pool_jobs,
                            sms_permission_ready,telephony_ready,network_ready,heartbeat_at)
    VALUES (user_a,'Fair Worker 1',digest(gen_random_bytes(32),'sha256'),'READY',TRUE,TRUE,TRUE,TRUE,now())
    RETURNING id INTO gateway_f1;
    INSERT INTO dm_gateways(owner_user_id,display_name,credential_hash,health_state,accept_pool_jobs,
                            sms_permission_ready,telephony_ready,network_ready,heartbeat_at)
    VALUES (user_b,'Fair Worker 2',digest(gen_random_bytes(32),'sha256'),'READY',TRUE,TRUE,TRUE,TRUE,now())
    RETURNING id INTO gateway_f2;
    INSERT INTO dm_gateways(owner_user_id,display_name,credential_hash,health_state,accept_pool_jobs,
                            sms_permission_ready,telephony_ready,network_ready,heartbeat_at)
    VALUES (user_a,'Recovery Worker',digest(gen_random_bytes(32),'sha256'),'READY',TRUE,TRUE,TRUE,TRUE,now())
    RETURNING id INTO gateway_f3;

    INSERT INTO dm_tenant_device_access(tenant_id,canonical_device_id) VALUES
      (tenant_a,'GLO-GD-PERSONAL'), (tenant_b,'GLO-GD-SHARED'),
      (tenant_c,'GLO-GD-FAIR-C'), (tenant_d,'GLO-GD-FAIR-D');

    INSERT INTO dm_check_batches(tenant_id,requested_by_user_id,client_idempotency_key,app_edition,total_jobs,request_fingerprint)
    VALUES (tenant_a,user_a,'personal-batch','GATEWAY',1,digest('personal-batch','sha256')) RETURNING id INTO batch_a;
    INSERT INTO dm_check_batches(tenant_id,requested_by_user_id,client_idempotency_key,app_edition,total_jobs,request_fingerprint)
    VALUES (tenant_b,user_b,'shared-batch','GATEWAY',1,digest('shared-batch','sha256')) RETURNING id INTO batch_b;

    INSERT INTO dm_check_jobs(batch_id,tenant_id,requested_by_user_id,canonical_device_id,query_profile,preferred_gateway_id)
    VALUES (batch_a,tenant_a,user_a,'GLO-GD-PERSONAL','standard',gateway_personal)
    RETURNING id INTO job_personal;
    INSERT INTO dm_check_jobs(batch_id,tenant_id,requested_by_user_id,canonical_device_id,query_profile)
    VALUES (batch_b,tenant_b,user_b,'GLO-GD-SHARED','standard')
    RETURNING id INTO job_shared;

    -- The pool gateway may take the ordinary shared job, but not the healthy owner's reserved job.
    SELECT * INTO claim_row FROM dm_claim_next_job(gateway_pool);
    IF claim_row.job_id IS DISTINCT FROM job_shared THEN
        RAISE EXCEPTION 'pool worker did not claim the shared job';
    END IF;
    SELECT * INTO claim_row FROM dm_claim_next_job(gateway_f1);
    IF claim_row.job_id IS NOT NULL THEN
        RAISE EXCEPTION 'shared worker stole a healthy personal gateway reservation';
    END IF;

    -- The personal gateway claims its owner's request before any pool work.
    SELECT * INTO claim_row FROM dm_claim_next_job(gateway_personal);
    IF claim_row.job_id IS DISTINCT FROM job_personal THEN
        RAISE EXCEPTION 'personal gateway did not claim owner-priority job';
    END IF;
    personal_attempt := claim_row.attempt_id;
    personal_token := claim_row.lease_token;

    accepted := dm_mark_attempt_sending(gateway_personal,job_personal,personal_attempt,personal_token);
    IF NOT accepted THEN RAISE EXCEPTION 'could not mark personal attempt sending'; END IF;
    accepted := dm_mark_attempt_awaiting_reply(
        gateway_personal,job_personal,personal_attempt,personal_token,now(),120
    );
    IF NOT accepted THEN RAISE EXCEPTION 'could not mark personal attempt awaiting reply'; END IF;
    accepted := dm_complete_job_result(
        gateway_personal,job_personal,personal_attempt,personal_token,
        CURRENT_DATE,now(),now(),
        '{"todayCount":12,"mtdCount":120,"active":90,"bar48":4,"bar96":2,"duplicates":0,"monthlyTarget":200}'::jsonb,
        'controller-test-parser'
    );
    IF NOT accepted THEN RAISE EXCEPTION 'valid result was not accepted'; END IF;
    IF NOT EXISTS (SELECT 1 FROM dm_daily_sms_results
                    WHERE tenant_id=tenant_a AND canonical_device_id='GLO-GD-PERSONAL'
                      AND business_date=CURRENT_DATE) THEN
        RAISE EXCEPTION 'daily result cache row missing';
    END IF;

    -- Tenant C is older and gets the first fair turn; tenant D gets the next one.
    INSERT INTO dm_tenant_device_access(tenant_id,canonical_device_id) VALUES
      (tenant_c,'GLO-GD-FAIR-C'), (tenant_d,'GLO-GD-FAIR-D');
    INSERT INTO dm_check_batches(tenant_id,requested_by_user_id,client_idempotency_key,app_edition,total_jobs,request_fingerprint)
    VALUES (tenant_c,user_a,'fair-c','GATEWAY',1,digest('fair-c','sha256')) RETURNING id INTO batch_c;
    INSERT INTO dm_check_batches(tenant_id,requested_by_user_id,client_idempotency_key,app_edition,total_jobs,request_fingerprint)
    VALUES (tenant_d,user_b,'fair-d','GATEWAY',1,digest('fair-d','sha256')) RETURNING id INTO batch_d;
    INSERT INTO dm_check_jobs(batch_id,tenant_id,requested_by_user_id,canonical_device_id,query_profile,enqueued_at)
    VALUES (batch_c,tenant_c,user_a,'GLO-GD-FAIR-C','standard',now()-INTERVAL '2 minutes')
    RETURNING id INTO job_c;
    INSERT INTO dm_check_jobs(batch_id,tenant_id,requested_by_user_id,canonical_device_id,query_profile,enqueued_at)
    VALUES (batch_d,tenant_d,user_b,'GLO-GD-FAIR-D','standard',now()-INTERVAL '1 minute')
    RETURNING id INTO job_d;

    SELECT * INTO claim_f1 FROM dm_claim_next_job(gateway_f1);
    SELECT * INTO claim_f2 FROM dm_claim_next_job(gateway_f2);
    IF claim_f1.job_id IS DISTINCT FROM job_c OR claim_f2.job_id IS DISTINCT FROM job_d THEN
        RAISE EXCEPTION 'tenant round-robin fairness failed';
    END IF;

    -- Expiry before SENDING is safe to requeue.
    UPDATE dm_job_attempts SET lease_expires_at=now()-INTERVAL '1 second'
     WHERE id=claim_f1.attempt_id;
    SELECT * INTO reaper_row FROM dm_reap_expired_attempts();
    IF reaper_row.requeued_jobs <> 1 THEN
        RAISE EXCEPTION 'pre-send lease expiry was not safely requeued';
    END IF;
    IF (SELECT state FROM dm_check_jobs WHERE id=job_c) <> 'RETRY_PENDING' THEN
        RAISE EXCEPTION 'pre-send expired job did not return to retry queue';
    END IF;

    -- Expiry after SENDING is ambiguous and quarantines the phone.
    SELECT * INTO claim_f3 FROM dm_claim_next_job(gateway_f3);
    IF claim_f3.job_id IS DISTINCT FROM job_c THEN
        RAISE EXCEPTION 'requeued job was not available to another worker';
    END IF;
    attempt_f3 := claim_f3.attempt_id;
    token_f3 := claim_f3.lease_token;
    accepted := dm_mark_attempt_sending(gateway_f3,job_c,attempt_f3,token_f3);
    IF NOT accepted THEN RAISE EXCEPTION 'could not mark recovery attempt sending'; END IF;
    UPDATE dm_job_attempts SET lease_expires_at=now()-INTERVAL '1 second'
     WHERE id=attempt_f3;
    SELECT * INTO reaper_row FROM dm_reap_expired_attempts();
    IF reaper_row.quarantined_gateways <> 1 THEN
        RAISE EXCEPTION 'post-send expiry did not quarantine gateway';
    END IF;
    IF (SELECT health_state FROM dm_gateways WHERE id=gateway_f3) <> 'QUARANTINED' THEN
        RAISE EXCEPTION 'gateway was not quarantined';
    END IF;
    IF (SELECT state FROM dm_check_jobs WHERE id=job_c) <> 'AMBIGUOUS' THEN
        RAISE EXCEPTION 'post-send expired job was not marked ambiguous';
    END IF;

    INSERT INTO dm_controller_operators(user_id) VALUES (user_a);
    accepted := dm_operator_resolve_ambiguous_attempt(
        attempt_f3,user_a,'REQUEUE_AFTER_INBOX_CLEARED',
        'Test operator verified the old inbox transaction is fully resolved.'
    );
    IF NOT accepted THEN RAISE EXCEPTION 'operator could not resolve ambiguous attempt'; END IF;
    accepted := dm_operator_resolve_gateway_quarantine(
        gateway_f3,user_a,'Test operator confirmed inbox cleared and safe for new work.'
    );
    IF NOT accepted THEN RAISE EXCEPTION 'operator could not release gateway quarantine'; END IF;
    IF (SELECT health_state FROM dm_gateways WHERE id=gateway_f3) <> 'READY' THEN
        RAISE EXCEPTION 'resolved gateway did not return to READY';
    END IF;
END;
$test$;

ROLLBACK;
