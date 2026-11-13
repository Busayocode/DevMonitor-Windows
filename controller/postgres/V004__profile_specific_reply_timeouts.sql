-- DevMonitor 5.0 Gateway Controller - preserve existing per-query wait windows.
BEGIN;

-- The reply timeout is selected by the server from the existing DevMonitor query profile:
-- standard Device Monitor profile defaults to 120 seconds; Partner menu profile to 180 seconds.
-- The controller adds a small transport grace period but does not replace the app's wait rule.
DROP FUNCTION IF EXISTS dm_mark_attempt_awaiting_reply(UUID, UUID, UUID, TEXT, TIMESTAMPTZ);

CREATE OR REPLACE FUNCTION dm_mark_attempt_awaiting_reply(
    p_gateway_id UUID,
    p_job_id UUID,
    p_attempt_id UUID,
    p_lease_token TEXT,
    p_sms_sent_at TIMESTAMPTZ,
    p_reply_timeout_seconds INTEGER DEFAULT 120
)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
    IF p_reply_timeout_seconds < 30 OR p_reply_timeout_seconds > 360 THEN
        RAISE EXCEPTION 'reply timeout out of allowed range';
    END IF;

    UPDATE dm_job_attempts
       SET state = 'AWAITING_REPLY',
           sms_sent_at = p_sms_sent_at,
           lease_expires_at = GREATEST(
               lease_expires_at,
               now() + make_interval(secs => p_reply_timeout_seconds + 30)
           )
     WHERE id = p_attempt_id AND job_id = p_job_id AND gateway_id = p_gateway_id
       AND lease_token_hash = digest(decode(p_lease_token, 'hex'), 'sha256')
       AND state = 'SENDING';
    IF NOT FOUND THEN RETURN FALSE; END IF;

    UPDATE dm_check_jobs SET state = 'AWAITING_REPLY', updated_at = now()
     WHERE id = p_job_id AND state = 'SENDING';
    RETURN FOUND;
END;
$$;

-- Status-only updates must remain possible after a device entitlement is revoked.
-- Re-check entitlement only when the tenant/device identity itself is inserted or changed.
CREATE OR REPLACE FUNCTION dm_validate_active_device_access()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE access_state_value TEXT;
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.tenant_id = NEW.tenant_id
       AND OLD.canonical_device_id = NEW.canonical_device_id THEN
        RETURN NEW;
    END IF;

    SELECT access_state INTO access_state_value
      FROM dm_tenant_device_access
     WHERE tenant_id = NEW.tenant_id
       AND canonical_device_id = NEW.canonical_device_id;
    IF access_state_value IS DISTINCT FROM 'ACTIVE' THEN
        RAISE EXCEPTION 'device is not actively authorized for this tenant'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

COMMIT;
