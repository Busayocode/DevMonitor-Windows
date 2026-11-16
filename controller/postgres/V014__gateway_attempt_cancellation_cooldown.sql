-- DevMonitor Gateway Controller V014: exact worker cancellation and server-enforced cooldown.
BEGIN;

ALTER TABLE dm_gateways
    ADD COLUMN IF NOT EXISTS cancellation_cooldown_until TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS dm_gateway_attempt_cancellations (
    attempt_id UUID PRIMARY KEY REFERENCES dm_job_attempts(id),
    job_id UUID NOT NULL REFERENCES dm_check_jobs(id),
    gateway_id UUID NOT NULL REFERENCES dm_gateways(id),
    outcome TEXT NOT NULL CHECK (outcome IN ('CANCELLED_BEFORE_SEND','CANCELLED_AFTER_SEND','BLOCKED_QUARANTINED')),
    reason TEXT,
    cooldown_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS dm_gateway_cooldown_idx
    ON dm_gateways(cancellation_cooldown_until)
    WHERE cancellation_cooldown_until IS NOT NULL;

-- Keep the original atomic scheduler intact, but put a durable cooldown barrier
-- in front of every claim. The old function also locks the gateway row itself.
ALTER FUNCTION dm_claim_next_job(UUID, INTEGER)
    RENAME TO dm_claim_next_job_without_cooldown;

CREATE FUNCTION dm_claim_next_job(
    p_gateway_id UUID,
    p_lease_seconds INTEGER DEFAULT 120
)
RETURNS TABLE (
    job_id UUID,
    attempt_id UUID,
    lease_token TEXT,
    canonical_device_id TEXT,
    query_profile TEXT,
    attempt_number INTEGER,
    lease_expires_at TIMESTAMPTZ
)
LANGUAGE plpgsql AS $$
DECLARE cooldown_until TIMESTAMPTZ;
BEGIN
    SELECT g.cancellation_cooldown_until
      INTO cooldown_until
      FROM dm_gateways g
     WHERE g.id=p_gateway_id
     FOR UPDATE;
    IF NOT FOUND THEN RETURN; END IF;
    IF cooldown_until IS NOT NULL AND cooldown_until > now() THEN
        RETURN;
    END IF;
    IF cooldown_until IS NOT NULL THEN
        UPDATE dm_gateways
           SET cancellation_cooldown_until=NULL,updated_at=now()
         WHERE id=p_gateway_id;
    END IF;
    RETURN QUERY
        SELECT * FROM dm_claim_next_job_without_cooldown(p_gateway_id,p_lease_seconds);
END;
$$;

CREATE OR REPLACE FUNCTION dm_cancel_gateway_attempt(
    p_gateway_id UUID,
    p_job_id UUID,
    p_attempt_id UUID,
    p_lease_token TEXT,
    p_reason TEXT DEFAULT NULL
)
RETURNS TABLE (
    accepted BOOLEAN,
    outcome TEXT,
    cooldown_until TIMESTAMPTZ
)
LANGUAGE plpgsql AS $$
DECLARE
    gateway_row dm_gateways%ROWTYPE;
    attempt_row dm_job_attempts%ROWTYPE;
    job_row dm_check_jobs%ROWTYPE;
    prior dm_gateway_attempt_cancellations%ROWTYPE;
    next_cooldown TIMESTAMPTZ;
BEGIN
    -- Lock order starts at gateway, then exact attempt, then job. This serializes
    -- cancellation with claims and never frees a different or newer attempt.
    SELECT * INTO gateway_row FROM dm_gateways
     WHERE id=p_gateway_id AND revoked_at IS NULL
     FOR UPDATE;
    IF NOT FOUND THEN
        RETURN QUERY SELECT FALSE,'NOT_FOUND'::TEXT,NULL::TIMESTAMPTZ;
        RETURN;
    END IF;

    IF p_lease_token IS NULL OR p_lease_token !~ '^[0-9A-Fa-f]{64,256}
    SELECT * INTO job_row FROM dm_check_jobs WHERE id=p_job_id FOR UPDATE;
    IF NOT FOUND THEN
        RETURN QUERY SELECT FALSE,'JOB_NOT_FOUND'::TEXT,NULL::TIMESTAMPTZ;
        RETURN;
    END IF;

    IF attempt_row.state='LEASED'
       AND job_row.state='LEASED'
       AND attempt_row.send_started_at IS NULL
       AND attempt_row.sms_sent_at IS NULL THEN
        UPDATE dm_job_attempts
           SET state='REJECTED',completed_at=now(),failure_code='USER_CANCELLED_BEFORE_SEND'
         WHERE id=p_attempt_id;
        UPDATE dm_check_jobs SET state='CANCELLED',updated_at=now()
         WHERE id=p_job_id AND state='LEASED';
        INSERT INTO dm_gateway_attempt_cancellations(
            attempt_id,job_id,gateway_id,outcome,reason
        ) VALUES (
            p_attempt_id,p_job_id,p_gateway_id,'CANCELLED_BEFORE_SEND',
            NULLIF(left(trim(COALESCE(p_reason,'')),240),'')
        );
        INSERT INTO dm_gateway_audit_events(
            tenant_id,gateway_id,job_id,event_type,details
        ) VALUES (
            job_row.tenant_id,p_gateway_id,p_job_id,'GATEWAY_ATTEMPT_CANCELLED_BEFORE_SEND',
            jsonb_build_object('attempt_id',p_attempt_id,'reason',p_reason)
        );
        RETURN QUERY SELECT TRUE,'CANCELLED_BEFORE_SEND'::TEXT,NULL::TIMESTAMPTZ;
        RETURN;
    END IF;

    IF attempt_row.state='AWAITING_REPLY'
       AND job_row.state='AWAITING_REPLY'
       AND attempt_row.send_started_at IS NOT NULL
       AND attempt_row.sms_sent_at IS NOT NULL THEN
        next_cooldown := now()+INTERVAL '4 minutes';
        UPDATE dm_job_attempts
           SET state='REJECTED',completed_at=now(),failure_code='USER_CANCELLED_AFTER_SEND'
         WHERE id=p_attempt_id;
        UPDATE dm_check_jobs SET state='CANCELLED',updated_at=now()
         WHERE id=p_job_id AND state='AWAITING_REPLY';
        UPDATE dm_gateways
           SET cancellation_cooldown_until=next_cooldown,updated_at=now()
         WHERE id=p_gateway_id;
        INSERT INTO dm_gateway_attempt_cancellations(
            attempt_id,job_id,gateway_id,outcome,reason,cooldown_until
        ) VALUES (
            p_attempt_id,p_job_id,p_gateway_id,'CANCELLED_AFTER_SEND',
            NULLIF(left(trim(COALESCE(p_reason,'')),240),''),next_cooldown
        );
        INSERT INTO dm_gateway_audit_events(
            tenant_id,gateway_id,job_id,event_type,details
        ) VALUES (
            job_row.tenant_id,p_gateway_id,p_job_id,'GATEWAY_ATTEMPT_CANCELLED_AFTER_SEND',
            jsonb_build_object('attempt_id',p_attempt_id,'cooldown_until',next_cooldown,'reason',p_reason)
        );
        RETURN QUERY SELECT TRUE,'CANCELLED_AFTER_SEND'::TEXT,next_cooldown;
        RETURN;
    END IF;

    -- SENDING means carrier submission may have happened but SMS_SENT evidence is
    -- absent; AMBIGUOUS is likewise unresolved. Neither may be released or retried.
    IF attempt_row.state IN ('SENDING','AMBIGUOUS')
       OR job_row.state IN ('SENDING','AMBIGUOUS') THEN
        UPDATE dm_gateways
           SET health_state='QUARANTINED',
               quarantined_reason='Cancellation requested while SMS submission/result is uncertain',
               accept_pool_jobs=FALSE,updated_at=now()
         WHERE id=p_gateway_id;
        INSERT INTO dm_gateway_attempt_cancellations(
            attempt_id,job_id,gateway_id,outcome,reason
        ) VALUES (
            p_attempt_id,p_job_id,p_gateway_id,'BLOCKED_QUARANTINED',
            NULLIF(left(trim(COALESCE(p_reason,'')),240),'')
        );
        INSERT INTO dm_gateway_audit_events(
            tenant_id,gateway_id,job_id,event_type,details
        ) VALUES (
            job_row.tenant_id,p_gateway_id,p_job_id,'GATEWAY_CANCELLATION_BLOCKED_UNCERTAIN_SEND',
            jsonb_build_object('attempt_id',p_attempt_id,'attempt_state',attempt_row.state,'reason',p_reason)
        );
        RETURN QUERY SELECT TRUE,'BLOCKED_QUARANTINED'::TEXT,NULL::TIMESTAMPTZ;
        RETURN;
    END IF;

    RETURN QUERY SELECT FALSE,'STATE_MISMATCH'::TEXT,NULL::TIMESTAMPTZ;
END;
$$;

COMMIT;

       OR length(p_lease_token) % 2 <> 0 THEN
        RETURN QUERY SELECT FALSE,'ATTEMPT_MISMATCH'::TEXT,NULL::TIMESTAMPTZ;
        RETURN;
    END IF;

    SELECT * INTO attempt_row FROM dm_job_attempts
     WHERE id=p_attempt_id AND job_id=p_job_id AND gateway_id=p_gateway_id
       AND lease_token_hash=digest(decode(p_lease_token,'hex'),'sha256')
     FOR UPDATE;
    IF NOT FOUND THEN
        RETURN QUERY SELECT FALSE,'ATTEMPT_MISMATCH'::TEXT,NULL::TIMESTAMPTZ;
        RETURN;
    END IF;

    -- Validate the opaque lease token even for idempotent retries; knowledge of
    -- an attempt UUID alone must never reveal or replay its cancellation result.
    SELECT * INTO prior FROM dm_gateway_attempt_cancellations
     WHERE attempt_id=p_attempt_id AND gateway_id=p_gateway_id AND job_id=p_job_id;
    IF FOUND THEN
        RETURN QUERY SELECT TRUE,prior.outcome,prior.cooldown_until;
        RETURN;
    END IF;

    SELECT * INTO job_row FROM dm_check_jobs WHERE id=p_job_id FOR UPDATE;
    IF NOT FOUND THEN
        RETURN QUERY SELECT FALSE,'JOB_NOT_FOUND'::TEXT,NULL::TIMESTAMPTZ;
        RETURN;
    END IF;

    IF attempt_row.state='LEASED'
       AND job_row.state='LEASED'
       AND attempt_row.send_started_at IS NULL
       AND attempt_row.sms_sent_at IS NULL THEN
        UPDATE dm_job_attempts
           SET state='REJECTED',completed_at=now(),failure_code='USER_CANCELLED_BEFORE_SEND'
         WHERE id=p_attempt_id;
        UPDATE dm_check_jobs SET state='CANCELLED',updated_at=now()
         WHERE id=p_job_id AND state='LEASED';
        INSERT INTO dm_gateway_attempt_cancellations(
            attempt_id,job_id,gateway_id,outcome,reason
        ) VALUES (
            p_attempt_id,p_job_id,p_gateway_id,'CANCELLED_BEFORE_SEND',
            NULLIF(left(trim(COALESCE(p_reason,'')),240),'')
        );
        INSERT INTO dm_gateway_audit_events(
            tenant_id,gateway_id,job_id,event_type,details
        ) VALUES (
            job_row.tenant_id,p_gateway_id,p_job_id,'GATEWAY_ATTEMPT_CANCELLED_BEFORE_SEND',
            jsonb_build_object('attempt_id',p_attempt_id,'reason',p_reason)
        );
        RETURN QUERY SELECT TRUE,'CANCELLED_BEFORE_SEND'::TEXT,NULL::TIMESTAMPTZ;
        RETURN;
    END IF;

    IF attempt_row.state='AWAITING_REPLY'
       AND job_row.state='AWAITING_REPLY'
       AND attempt_row.send_started_at IS NOT NULL
       AND attempt_row.sms_sent_at IS NOT NULL THEN
        next_cooldown := now()+INTERVAL '4 minutes';
        UPDATE dm_job_attempts
           SET state='REJECTED',completed_at=now(),failure_code='USER_CANCELLED_AFTER_SEND'
         WHERE id=p_attempt_id;
        UPDATE dm_check_jobs SET state='CANCELLED',updated_at=now()
         WHERE id=p_job_id AND state='AWAITING_REPLY';
        UPDATE dm_gateways
           SET cancellation_cooldown_until=next_cooldown,updated_at=now()
         WHERE id=p_gateway_id;
        INSERT INTO dm_gateway_attempt_cancellations(
            attempt_id,job_id,gateway_id,outcome,reason,cooldown_until
        ) VALUES (
            p_attempt_id,p_job_id,p_gateway_id,'CANCELLED_AFTER_SEND',
            NULLIF(left(trim(COALESCE(p_reason,'')),240),''),next_cooldown
        );
        INSERT INTO dm_gateway_audit_events(
            tenant_id,gateway_id,job_id,event_type,details
        ) VALUES (
            job_row.tenant_id,p_gateway_id,p_job_id,'GATEWAY_ATTEMPT_CANCELLED_AFTER_SEND',
            jsonb_build_object('attempt_id',p_attempt_id,'cooldown_until',next_cooldown,'reason',p_reason)
        );
        RETURN QUERY SELECT TRUE,'CANCELLED_AFTER_SEND'::TEXT,next_cooldown;
        RETURN;
    END IF;

    -- SENDING means carrier submission may have happened but SMS_SENT evidence is
    -- absent; AMBIGUOUS is likewise unresolved. Neither may be released or retried.
    IF attempt_row.state IN ('SENDING','AMBIGUOUS')
       OR job_row.state IN ('SENDING','AMBIGUOUS') THEN
        UPDATE dm_gateways
           SET health_state='QUARANTINED',
               quarantined_reason='Cancellation requested while SMS submission/result is uncertain',
               accept_pool_jobs=FALSE,updated_at=now()
         WHERE id=p_gateway_id;
        INSERT INTO dm_gateway_attempt_cancellations(
            attempt_id,job_id,gateway_id,outcome,reason
        ) VALUES (
            p_attempt_id,p_job_id,p_gateway_id,'BLOCKED_QUARANTINED',
            NULLIF(left(trim(COALESCE(p_reason,'')),240),'')
        );
        INSERT INTO dm_gateway_audit_events(
            tenant_id,gateway_id,job_id,event_type,details
        ) VALUES (
            job_row.tenant_id,p_gateway_id,p_job_id,'GATEWAY_CANCELLATION_BLOCKED_UNCERTAIN_SEND',
            jsonb_build_object('attempt_id',p_attempt_id,'attempt_state',attempt_row.state,'reason',p_reason)
        );
        RETURN QUERY SELECT TRUE,'BLOCKED_QUARANTINED'::TEXT,NULL::TIMESTAMPTZ;
        RETURN;
    END IF;

    RETURN QUERY SELECT FALSE,'STATE_MISMATCH'::TEXT,NULL::TIMESTAMPTZ;
END;
$$;

COMMIT;
