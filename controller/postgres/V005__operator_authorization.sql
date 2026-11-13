-- DevMonitor 5.0 Gateway Controller - privileged operator identity.
BEGIN;

CREATE TABLE IF NOT EXISTS dm_controller_operators (
    user_id UUID PRIMARY KEY REFERENCES dm_users(id) ON DELETE CASCADE,
    granted_by_user_id UUID REFERENCES dm_users(id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION dm_operator_resolve_ambiguous_attempt(
    p_attempt_id UUID,
    p_operator_user_id UUID,
    p_resolution TEXT,
    p_resolution_note TEXT
)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE attempt_row dm_job_attempts%ROWTYPE;
        job_row dm_check_jobs%ROWTYPE;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM dm_controller_operators WHERE user_id = p_operator_user_id) THEN
        RAISE EXCEPTION 'controller operator role required' USING ERRCODE = '42501';
    END IF;
    IF p_resolution NOT IN ('DISCARD_NO_RETRY','REQUEUE_AFTER_INBOX_CLEARED') THEN
        RAISE EXCEPTION 'unsupported ambiguous-attempt resolution';
    END IF;
    IF length(trim(p_resolution_note)) < 12 THEN
        RAISE EXCEPTION 'a meaningful resolution note is required';
    END IF;

    SELECT * INTO attempt_row FROM dm_job_attempts
     WHERE id = p_attempt_id AND state = 'AMBIGUOUS'
     FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;

    SELECT * INTO job_row FROM dm_check_jobs WHERE id = attempt_row.job_id FOR UPDATE;
    IF NOT FOUND OR job_row.state <> 'AMBIGUOUS' THEN RETURN FALSE; END IF;

    UPDATE dm_job_attempts SET state = 'TIMED_OUT',
           failure_code = p_resolution, completed_at = now()
     WHERE id = p_attempt_id;

    UPDATE dm_check_jobs
       SET state = CASE
           WHEN p_resolution = 'REQUEUE_AFTER_INBOX_CLEARED'
                AND attempt_count < max_attempts THEN 'RETRY_PENDING'
           ELSE 'FAILED' END,
           updated_at = now()
     WHERE id = job_row.id;

    INSERT INTO dm_gateway_audit_events(
        tenant_id, actor_user_id, gateway_id, job_id, event_type, details
    ) VALUES (
        job_row.tenant_id, p_operator_user_id, attempt_row.gateway_id,
        job_row.id, 'AMBIGUOUS_ATTEMPT_RESOLVED',
        jsonb_build_object('resolution', p_resolution, 'note', p_resolution_note)
    );
    RETURN TRUE;
END;
$$;

CREATE OR REPLACE FUNCTION dm_operator_resolve_gateway_quarantine(
    p_gateway_id UUID,
    p_operator_user_id UUID,
    p_resolution_note TEXT
)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM dm_controller_operators WHERE user_id = p_operator_user_id) THEN
        RAISE EXCEPTION 'controller operator role required' USING ERRCODE = '42501';
    END IF;
    IF length(trim(p_resolution_note)) < 12 THEN
        RAISE EXCEPTION 'a meaningful resolution note is required';
    END IF;
    IF EXISTS (
        SELECT 1 FROM dm_job_attempts
         WHERE gateway_id = p_gateway_id AND state = 'AMBIGUOUS'
    ) THEN
        RAISE EXCEPTION 'ambiguous attempt must be explicitly resolved before gateway release';
    END IF;
    UPDATE dm_gateways
       SET health_state = CASE
               WHEN sms_permission_ready AND telephony_ready AND network_ready THEN 'READY'
               ELSE 'NOT_READY' END,
           quarantined_reason = NULL, updated_at = now()
     WHERE id = p_gateway_id AND health_state = 'QUARANTINED';
    IF NOT FOUND THEN RETURN FALSE; END IF;
    INSERT INTO dm_gateway_audit_events(actor_user_id, gateway_id, event_type, details)
    VALUES (p_operator_user_id, p_gateway_id, 'QUARANTINE_RELEASED',
            jsonb_build_object('resolution_note', p_resolution_note));
    RETURN TRUE;
END;
$$;

COMMIT;
