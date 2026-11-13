-- DevMonitor 5.0 Gateway Controller - follow-up hardening migration.
-- Apply after V001__gateway_controller.sql to the dedicated controller database.
BEGIN;

-- The controller must grant device access before a user can queue a check.
-- This is intentionally separate from cached result visibility and membership.
CREATE TABLE IF NOT EXISTS dm_tenant_device_access (
    tenant_id UUID NOT NULL REFERENCES dm_tenants(id) ON DELETE CASCADE,
    canonical_device_id TEXT NOT NULL,
    access_state TEXT NOT NULL DEFAULT 'ACTIVE'
        CHECK (access_state IN ('ACTIVE','REVOKED')),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, canonical_device_id)
);

ALTER TABLE dm_check_jobs
    ADD CONSTRAINT dm_check_job_device_access_fk
    FOREIGN KEY (tenant_id, canonical_device_id)
    REFERENCES dm_tenant_device_access(tenant_id, canonical_device_id);

-- Register a personal gateway. The raw bearer token is returned once; only its
-- SHA-256 hash is stored. The API must supply owner_user_id from its verified user token.
CREATE OR REPLACE FUNCTION dm_register_personal_gateway(
    p_owner_user_id UUID,
    p_display_name TEXT
)
RETURNS TABLE (gateway_id UUID, gateway_token TEXT)
LANGUAGE plpgsql AS $$
DECLARE new_id UUID;
        raw_token TEXT;
BEGIN
    IF length(trim(p_display_name)) < 1 OR length(p_display_name) > 80 THEN
        RAISE EXCEPTION 'invalid gateway display name';
    END IF;
    new_id := gen_random_uuid();
    raw_token := encode(gen_random_bytes(32), 'hex');
    INSERT INTO dm_gateways(id, owner_user_id, display_name, credential_hash)
    VALUES (new_id, p_owner_user_id, trim(p_display_name),
            digest(decode(raw_token, 'hex'), 'sha256'));
    INSERT INTO dm_gateway_audit_events(actor_user_id, gateway_id, event_type)
    VALUES (p_owner_user_id, new_id, 'PERSONAL_GATEWAY_REGISTERED');
    RETURN QUERY SELECT new_id, raw_token;
END;
$$;

-- Pool participation can be changed only by the registered owner.
CREATE OR REPLACE FUNCTION dm_set_gateway_pool_participation(
    p_owner_user_id UUID,
    p_gateway_id UUID,
    p_enabled BOOLEAN
)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
    UPDATE dm_gateways
       SET accept_pool_jobs = p_enabled, updated_at = now()
     WHERE id = p_gateway_id AND owner_user_id = p_owner_user_id;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    INSERT INTO dm_gateway_audit_events(actor_user_id, gateway_id, event_type, details)
    VALUES (p_owner_user_id, p_gateway_id,
            CASE WHEN p_enabled THEN 'POOL_OPT_IN' ELSE 'POOL_OPT_OUT' END,
            jsonb_build_object('accept_pool_jobs', p_enabled));
    RETURN TRUE;
END;
$$;

-- A gateway explicitly acknowledges that it is about to send. Once this transition
-- succeeds, lease expiry is treated as ambiguous rather than silently reassigned.
CREATE OR REPLACE FUNCTION dm_mark_attempt_sending(
    p_gateway_id UUID,
    p_job_id UUID,
    p_attempt_id UUID,
    p_lease_token TEXT
)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
    UPDATE dm_job_attempts
       SET state = 'SENDING', send_started_at = now()
     WHERE id = p_attempt_id AND job_id = p_job_id AND gateway_id = p_gateway_id
       AND lease_token_hash = digest(decode(p_lease_token, 'hex'), 'sha256')
       AND state = 'LEASED' AND lease_expires_at > now();
    IF NOT FOUND THEN RETURN FALSE; END IF;
    UPDATE dm_check_jobs SET state = 'SENDING', updated_at = now()
     WHERE id = p_job_id AND state = 'LEASED';
    RETURN FOUND;
END;
$$;

CREATE OR REPLACE FUNCTION dm_mark_attempt_awaiting_reply(
    p_gateway_id UUID,
    p_job_id UUID,
    p_attempt_id UUID,
    p_lease_token TEXT,
    p_sms_sent_at TIMESTAMPTZ
)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
    UPDATE dm_job_attempts
       SET state = 'AWAITING_REPLY', sms_sent_at = p_sms_sent_at,
           lease_expires_at = GREATEST(lease_expires_at, now() + INTERVAL '180 seconds')
     WHERE id = p_attempt_id AND job_id = p_job_id AND gateway_id = p_gateway_id
       AND lease_token_hash = digest(decode(p_lease_token, 'hex'), 'sha256')
       AND state = 'SENDING';
    IF NOT FOUND THEN RETURN FALSE; END IF;
    UPDATE dm_check_jobs SET state = 'AWAITING_REPLY', updated_at = now()
     WHERE id = p_job_id AND state = 'SENDING';
    RETURN FOUND;
END;
$$;

-- A definitive local send failure (e.g. Android rejected send before transmission)
-- is retryable. Do not call this for delivery-report uncertainty or reply timeout.
CREATE OR REPLACE FUNCTION dm_mark_definitive_send_failure(
    p_gateway_id UUID,
    p_job_id UUID,
    p_attempt_id UUID,
    p_lease_token TEXT,
    p_failure_code TEXT
)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE job_row dm_check_jobs%ROWTYPE;
BEGIN
    SELECT * INTO job_row FROM dm_check_jobs WHERE id = p_job_id FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;

    UPDATE dm_job_attempts
       SET state = 'SEND_FAILED', failure_code = p_failure_code,
           completed_at = now()
     WHERE id = p_attempt_id AND job_id = p_job_id AND gateway_id = p_gateway_id
       AND lease_token_hash = digest(decode(p_lease_token, 'hex'), 'sha256')
       AND state = 'LEASED';
    IF NOT FOUND THEN RETURN FALSE; END IF;

    UPDATE dm_check_jobs
       SET state = CASE WHEN attempt_count < max_attempts THEN 'RETRY_PENDING' ELSE 'FAILED' END,
           updated_at = now()
     WHERE id = p_job_id;
    RETURN TRUE;
END;
$$;

-- Reap expired leases conservatively:
-- * LEASED with no send_started_at: safe to return to queue.
-- * SENDING/AWAITING_REPLY or any attempt with send_started_at: ambiguous, quarantine.
-- This function never treats a quiet period as proof that a 3030 reply belongs elsewhere.
CREATE OR REPLACE FUNCTION dm_reap_expired_attempts()
RETURNS TABLE (requeued_jobs INTEGER, quarantined_gateways INTEGER)
LANGUAGE plpgsql AS $$
DECLARE a RECORD;
        requeued_count INTEGER := 0;
        quarantined_count INTEGER := 0;
BEGIN
    FOR a IN
        SELECT att.id AS attempt_id, att.job_id, att.gateway_id,
               att.state AS attempt_state, att.send_started_at,
               j.attempt_count, j.max_attempts
          FROM dm_job_attempts att
          JOIN dm_check_jobs j ON j.id = att.job_id
         WHERE att.lease_expires_at < now()
           AND att.state IN ('LEASED','SENDING','AWAITING_REPLY')
         FOR UPDATE OF att, j SKIP LOCKED
    LOOP
        IF a.attempt_state = 'LEASED' AND a.send_started_at IS NULL THEN
            UPDATE dm_job_attempts
               SET state = 'REJECTED', failure_code = 'LEASE_EXPIRED_BEFORE_SEND',
                   completed_at = now()
             WHERE id = a.attempt_id;
            UPDATE dm_check_jobs
               SET state = CASE WHEN attempt_count < max_attempts THEN 'RETRY_PENDING' ELSE 'FAILED' END,
                   updated_at = now()
             WHERE id = a.job_id;
            requeued_count := requeued_count + 1;
        ELSE
            UPDATE dm_job_attempts
               SET state = 'AMBIGUOUS', failure_code = 'LEASE_EXPIRED_AFTER_POSSIBLE_SEND'
             WHERE id = a.attempt_id;
            UPDATE dm_check_jobs SET state = 'AMBIGUOUS', updated_at = now()
             WHERE id = a.job_id;
            UPDATE dm_gateways SET health_state = 'QUARANTINED',
                   quarantined_reason = 'LEASE_EXPIRED_AFTER_POSSIBLE_SEND',
                   updated_at = now()
             WHERE id = a.gateway_id AND health_state <> 'QUARANTINED';
            IF FOUND THEN quarantined_count := quarantined_count + 1; END IF;
        END IF;
    END LOOP;
    RETURN QUERY SELECT requeued_count, quarantined_count;
END;
$$;

-- Privileged maintenance operation. Never expose this through a gateway/client API.
-- An operator must establish that the old 3030 transaction can no longer be misattributed.
CREATE OR REPLACE FUNCTION dm_operator_resolve_gateway_quarantine(
    p_gateway_id UUID,
    p_operator_user_id UUID,
    p_resolution_note TEXT
)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
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
