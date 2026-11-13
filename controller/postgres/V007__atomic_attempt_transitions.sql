-- DevMonitor 5.0 Gateway Controller - serialize attempt state transitions.
BEGIN;

CREATE OR REPLACE FUNCTION dm_mark_attempt_sending(
    p_gateway_id UUID,
    p_job_id UUID,
    p_attempt_id UUID,
    p_lease_token TEXT
)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE attempt_row dm_job_attempts%ROWTYPE;
        job_row dm_check_jobs%ROWTYPE;
BEGIN
    SELECT * INTO attempt_row
      FROM dm_job_attempts
     WHERE id=p_attempt_id AND job_id=p_job_id AND gateway_id=p_gateway_id
       AND lease_token_hash=digest(decode(p_lease_token,'hex'),'sha256')
       AND state='LEASED' AND lease_expires_at > now()
     FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;

    SELECT * INTO job_row FROM dm_check_jobs
     WHERE id=p_job_id AND state='LEASED'
     FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;

    UPDATE dm_job_attempts SET state='SENDING', send_started_at=now()
     WHERE id=p_attempt_id;
    UPDATE dm_check_jobs SET state='SENDING', updated_at=now()
     WHERE id=p_job_id;
    RETURN TRUE;
END;
$$;

CREATE OR REPLACE FUNCTION dm_mark_attempt_awaiting_reply(
    p_gateway_id UUID,
    p_job_id UUID,
    p_attempt_id UUID,
    p_lease_token TEXT,
    p_sms_sent_at TIMESTAMPTZ,
    p_reply_timeout_seconds INTEGER DEFAULT 120
)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE attempt_row dm_job_attempts%ROWTYPE;
        job_row dm_check_jobs%ROWTYPE;
BEGIN
    IF p_reply_timeout_seconds < 30 OR p_reply_timeout_seconds > 360 THEN
        RAISE EXCEPTION 'reply timeout out of allowed range';
    END IF;

    SELECT * INTO attempt_row
      FROM dm_job_attempts
     WHERE id=p_attempt_id AND job_id=p_job_id AND gateway_id=p_gateway_id
       AND lease_token_hash=digest(decode(p_lease_token,'hex'),'sha256')
       AND state='SENDING'
     FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;

    SELECT * INTO job_row FROM dm_check_jobs
     WHERE id=p_job_id AND state='SENDING'
     FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;

    UPDATE dm_job_attempts
       SET state='AWAITING_REPLY', sms_sent_at=p_sms_sent_at,
           lease_expires_at=GREATEST(
               lease_expires_at,
               now()+make_interval(secs => p_reply_timeout_seconds+30)
           )
     WHERE id=p_attempt_id;
    UPDATE dm_check_jobs SET state='AWAITING_REPLY', updated_at=now()
     WHERE id=p_job_id;
    RETURN TRUE;
END;
$$;

-- This transition is only for a gateway-confirmed failure before transmission.
-- Any uncertain send outcome must use AMBIGUOUS instead.
CREATE OR REPLACE FUNCTION dm_mark_definitive_send_failure(
    p_gateway_id UUID,
    p_job_id UUID,
    p_attempt_id UUID,
    p_lease_token TEXT,
    p_failure_code TEXT
)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE attempt_row dm_job_attempts%ROWTYPE;
        job_row dm_check_jobs%ROWTYPE;
BEGIN
    SELECT * INTO attempt_row
      FROM dm_job_attempts
     WHERE id=p_attempt_id AND job_id=p_job_id AND gateway_id=p_gateway_id
       AND lease_token_hash=digest(decode(p_lease_token,'hex'),'sha256')
       AND state IN ('LEASED','SENDING') AND sms_sent_at IS NULL
     FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;

    SELECT * INTO job_row FROM dm_check_jobs
     WHERE id=p_job_id AND state IN ('LEASED','SENDING')
     FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;

    UPDATE dm_job_attempts
       SET state='SEND_FAILED', failure_code=p_failure_code, completed_at=now()
     WHERE id=p_attempt_id;
    UPDATE dm_check_jobs
       SET state=CASE WHEN attempt_count < max_attempts THEN 'RETRY_PENDING' ELSE 'FAILED' END,
           updated_at=now()
     WHERE id=p_job_id;
    RETURN TRUE;
END;
$$;

COMMIT;
