-- V014 integration coverage: exact pre-send cancellation, post-send cooldown and claim barrier.
BEGIN;

DO $$
DECLARE
    u UUID := gen_random_uuid();
    t UUID := gen_random_uuid();
    g UUID := gen_random_uuid();
    b UUID := gen_random_uuid();
    j_pre UUID := gen_random_uuid();
    a_pre UUID := gen_random_uuid();
    j_sent UUID := gen_random_uuid();
    a_sent UUID := gen_random_uuid();
    j_waiting UUID := gen_random_uuid();
    token_pre TEXT := encode(gen_random_bytes(32),'hex');
    token_sent TEXT := encode(gen_random_bytes(32),'hex');
    result_row RECORD;
    cooldown_value TIMESTAMPTZ;
    claimed RECORD;
BEGIN
    INSERT INTO dm_users(id,auth_subject) VALUES (u,'v014-test-'||u);
    INSERT INTO dm_tenants(id,display_name) VALUES (t,'V014 cancellation test');
    INSERT INTO dm_tenant_memberships(tenant_id,user_id,role) VALUES (t,u,'OWNER');
    INSERT INTO dm_gateways(
        id,owner_user_id,display_name,credential_hash,health_state,
        sms_permission_ready,telephony_ready,network_ready,heartbeat_at
    ) VALUES (
        g,u,'V014 test gateway',digest(gen_random_bytes(32),'sha256'),'READY',
        TRUE,TRUE,TRUE,now()
    );
    INSERT INTO dm_check_batches(id,tenant_id,requested_by_user_id,client_idempotency_key,app_edition,total_jobs,request_fingerprint)
    VALUES (b,t,u,'v014-'||b,'GATEWAY',3,digest(convert_to('v014-'||b::TEXT,'UTF8'),'sha256'));

    -- A lease cancelled before any send evidence is safe to close immediately.
    INSERT INTO dm_check_jobs(id,batch_id,tenant_id,requested_by_user_id,canonical_device_id,query_profile,state)
    VALUES (j_pre,b,t,u,'V014-PRE','standard','LEASED');
    INSERT INTO dm_job_attempts(
        id,job_id,gateway_id,attempt_number,lease_token_hash,state,lease_expires_at
    ) VALUES (
        a_pre,j_pre,g,1,digest(decode(token_pre,'hex'),'sha256'),'LEASED',now()+INTERVAL '2 minutes'
    );
    SELECT * INTO result_row
      FROM dm_cancel_gateway_attempt(g,j_pre,a_pre,token_pre,'pre-send test');
    IF NOT result_row.accepted OR result_row.outcome <> 'CANCELLED_BEFORE_SEND'
       OR result_row.cooldown_until IS NOT NULL THEN
        RAISE EXCEPTION 'V014 pre-send cancellation result incorrect: %',row_to_json(result_row);
    END IF;

    -- A positively acknowledged SMS submission is closed with a four-minute barrier.
    INSERT INTO dm_check_jobs(id,batch_id,tenant_id,requested_by_user_id,canonical_device_id,query_profile,state)
    VALUES (j_sent,b,t,u,'V014-SENT','standard','AWAITING_REPLY');
    INSERT INTO dm_job_attempts(
        id,job_id,gateway_id,attempt_number,lease_token_hash,state,lease_expires_at,
        send_started_at,sms_sent_at
    ) VALUES (
        a_sent,j_sent,g,1,digest(decode(token_sent,'hex'),'sha256'),'AWAITING_REPLY',
        now()+INTERVAL '2 minutes',now()-INTERVAL '10 seconds',now()-INTERVAL '5 seconds'
    );
    SELECT * INTO result_row
      FROM dm_cancel_gateway_attempt(g,j_sent,a_sent,token_sent,'post-send test');
    IF NOT result_row.accepted OR result_row.outcome <> 'CANCELLED_AFTER_SEND'
       OR result_row.cooldown_until IS NULL
       OR result_row.cooldown_until < now()+INTERVAL '3 minutes 55 seconds'
       OR result_row.cooldown_until > now()+INTERVAL '4 minutes 5 seconds' THEN
        RAISE EXCEPTION 'V014 post-send cancellation/cooldown incorrect: %',row_to_json(result_row);
    END IF;
    cooldown_value := result_row.cooldown_until;

    -- Idempotent retry must return the original deadline, not extend it.
    SELECT * INTO result_row
      FROM dm_cancel_gateway_attempt(g,j_sent,a_sent,token_sent,'retry');
    IF result_row.outcome <> 'CANCELLED_AFTER_SEND'
       OR result_row.cooldown_until <> cooldown_value THEN
        RAISE EXCEPTION 'V014 cancellation retry changed the outcome/deadline';
    END IF;

    INSERT INTO dm_check_jobs(id,batch_id,tenant_id,requested_by_user_id,canonical_device_id,query_profile,state)
    VALUES (j_waiting,b,t,u,'V014-WAIT','standard','QUEUED');

    -- Claim must be blocked by the durable server deadline even though the
    -- earlier attempt has been closed and no active-attempt row remains.
    SELECT * INTO claimed FROM dm_claim_next_job(g,120);
    IF claimed.job_id IS NOT NULL THEN
        RAISE EXCEPTION 'V014 claim escaped the four-minute cancellation cooldown';
    END IF;
END;
$$;

ROLLBACK;
