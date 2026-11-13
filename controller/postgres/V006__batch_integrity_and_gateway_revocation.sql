-- DevMonitor 5.0 Gateway Controller - tenant-consistent batches and gateway revocation.
BEGIN;

ALTER TABLE dm_check_batches
    ADD COLUMN IF NOT EXISTS request_fingerprint BYTEA;

-- Jobs cannot be attached to a batch belonging to another tenant or requester.
CREATE UNIQUE INDEX IF NOT EXISTS dm_batches_identity_scope_uq
    ON dm_check_batches (id, tenant_id, requested_by_user_id);

ALTER TABLE dm_check_jobs
    ADD CONSTRAINT dm_check_job_batch_scope_fk
    FOREIGN KEY (batch_id, tenant_id, requested_by_user_id)
    REFERENCES dm_check_batches(id, tenant_id, requested_by_user_id);

CREATE OR REPLACE FUNCTION dm_require_batch_fingerprint()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.request_fingerprint IS NULL AND NEW.request_fingerprint IS NULL THEN
        RETURN NEW;
    END IF;
    IF NEW.request_fingerprint IS NULL OR octet_length(NEW.request_fingerprint) <> 32 THEN
        RAISE EXCEPTION 'batch request fingerprint must be a SHA-256 digest';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS dm_check_batch_fingerprint_required ON dm_check_batches;
CREATE TRIGGER dm_check_batch_fingerprint_required
BEFORE INSERT OR UPDATE ON dm_check_batches
FOR EACH ROW EXECUTE FUNCTION dm_require_batch_fingerprint();

ALTER TABLE dm_gateways
    ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMPTZ;

-- Revocation prevents new claims and prevents a later heartbeat from reactivating the phone.
CREATE OR REPLACE FUNCTION dm_revoke_personal_gateway(
    p_owner_user_id UUID,
    p_gateway_id UUID
)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
    UPDATE dm_gateways
       SET revoked_at = now(), health_state = 'OFFLINE',
           accept_pool_jobs = FALSE, updated_at = now()
     WHERE id = p_gateway_id AND owner_user_id = p_owner_user_id
       AND revoked_at IS NULL;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    INSERT INTO dm_gateway_audit_events(actor_user_id, gateway_id, event_type)
    VALUES (p_owner_user_id, p_gateway_id, 'PERSONAL_GATEWAY_REVOKED');
    RETURN TRUE;
END;
$$;

CREATE OR REPLACE FUNCTION dm_record_gateway_heartbeat(
    p_gateway_id UUID,
    p_app_version TEXT,
    p_sms_permission_ready BOOLEAN,
    p_telephony_ready BOOLEAN,
    p_network_ready BOOLEAN,
    p_last_successful_3030_at TIMESTAMPTZ DEFAULT NULL
)
RETURNS TEXT LANGUAGE plpgsql AS $$
DECLARE resulting_state TEXT;
BEGIN
    UPDATE dm_gateways
       SET app_version = p_app_version,
           sms_permission_ready = p_sms_permission_ready,
           telephony_ready = p_telephony_ready,
           network_ready = p_network_ready,
           heartbeat_at = now(),
           last_successful_3030_at = COALESCE(p_last_successful_3030_at, last_successful_3030_at),
           health_state = CASE
               WHEN revoked_at IS NOT NULL THEN 'OFFLINE'
               WHEN health_state = 'QUARANTINED' THEN 'QUARANTINED'
               WHEN p_sms_permission_ready AND p_telephony_ready AND p_network_ready THEN 'READY'
               ELSE 'NOT_READY'
           END,
           updated_at = now()
     WHERE id = p_gateway_id
     RETURNING health_state INTO resulting_state;
    RETURN resulting_state;
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
               WHEN revoked_at IS NOT NULL THEN 'OFFLINE'
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
