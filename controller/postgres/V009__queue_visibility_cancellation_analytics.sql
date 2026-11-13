-- DevMonitor 5.0 Gateway Controller - request cancellation, queue snapshots and analytics.
BEGIN;

ALTER TABLE dm_check_batches
    ADD COLUMN IF NOT EXISTS cancellation_requested_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS cancelled_by_user_id UUID REFERENCES dm_users(id),
    ADD COLUMN IF NOT EXISTS cancellation_reason TEXT;

CREATE INDEX IF NOT EXISTS dm_batches_tenant_created_idx
    ON dm_check_batches (tenant_id, created_at, id);

CREATE INDEX IF NOT EXISTS dm_jobs_batch_queue_state_idx
    ON dm_check_jobs (batch_id, state, enqueued_at);

-- Keep batch lifecycle consistent with the device-level source of truth. A request
-- with cancellation requested remains RUNNING while a send/reply is in flight or
-- ambiguous; it becomes PARTIAL/CANCELLED only after those attempts are resolved.
CREATE OR REPLACE FUNCTION dm_refresh_batch_lifecycle()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    target_batch UUID;
    has_active BOOLEAN;
    completed_count INTEGER;
    failed_count INTEGER;
    cancelled_count INTEGER;
    total_count INTEGER;
    cancel_requested TIMESTAMPTZ;
BEGIN
    target_batch := COALESCE(NEW.batch_id, OLD.batch_id);
    SELECT cancellation_requested_at INTO cancel_requested
      FROM dm_check_batches WHERE id = target_batch;
    IF NOT FOUND THEN RETURN COALESCE(NEW, OLD); END IF;

    SELECT
        count(*) FILTER (WHERE state IN ('LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS')) > 0,
        count(*) FILTER (WHERE state='COMPLETED'),
        count(*) FILTER (WHERE state='FAILED'),
        count(*) FILTER (WHERE state='CANCELLED'),
        count(*)
      INTO has_active, completed_count, failed_count, cancelled_count, total_count
      FROM dm_check_jobs WHERE batch_id = target_batch;

    UPDATE dm_check_batches
       SET completed_jobs = completed_count,
           failed_jobs = failed_count,
           status = CASE
             WHEN has_active THEN 'RUNNING'
             WHEN cancel_requested IS NOT NULL AND cancelled_count > 0
               THEN CASE WHEN completed_count + failed_count > 0 THEN 'PARTIAL' ELSE 'CANCELLED' END
             WHEN total_count > 0 AND completed_count + failed_count = total_count
               THEN CASE WHEN failed_count > 0 AND completed_count > 0 THEN 'PARTIAL'
                         WHEN failed_count > 0 THEN 'FAILED' ELSE 'COMPLETED' END
             WHEN completed_count + failed_count > 0 THEN 'RUNNING'
             ELSE 'QUEUED'
           END,
           cancelled_at = CASE
             WHEN cancel_requested IS NOT NULL AND NOT has_active
                  AND cancelled_count > 0 THEN COALESCE(cancelled_at, now())
             ELSE cancelled_at
           END,
           updated_at = now()
     WHERE id = target_batch;
    RETURN COALESCE(NEW, OLD);
END;
$$;

DROP TRIGGER IF EXISTS dm_job_refresh_batch_lifecycle ON dm_check_jobs;
CREATE TRIGGER dm_job_refresh_batch_lifecycle
AFTER INSERT OR UPDATE OF state OR DELETE ON dm_check_jobs
FOR EACH ROW EXECUTE FUNCTION dm_refresh_batch_lifecycle();

-- Cancellation is idempotent and scoped to the authenticated requester and tenant.
-- The attempt row is locked before its job, matching the send-transition lock order.
-- A LEASED attempt can be rejected safely; SENDING/AWAITING_REPLY/AMBIGUOUS attempts
-- are not interrupted because the SMS may already have left the phone.
CREATE OR REPLACE FUNCTION dm_cancel_check_batch(
    p_batch_id UUID,
    p_tenant_id UUID,
    p_user_id UUID,
    p_reason TEXT DEFAULT NULL
)
RETURNS TABLE (
    accepted BOOLEAN,
    batch_status TEXT,
    total_jobs BIGINT,
    completed_jobs BIGINT,
    cancelled_jobs BIGINT,
    in_flight_jobs BIGINT
)
LANGUAGE plpgsql AS $$
DECLARE batch_row dm_check_batches%ROWTYPE;
BEGIN
    SELECT * INTO batch_row
      FROM dm_check_batches
     WHERE id=p_batch_id AND tenant_id=p_tenant_id
       AND requested_by_user_id=p_user_id
     FOR UPDATE;
    IF NOT FOUND THEN
        RETURN QUERY SELECT FALSE, NULL::TEXT, 0::BIGINT, 0::BIGINT, 0::BIGINT, 0::BIGINT;
        RETURN;
    END IF;

    IF batch_row.cancellation_requested_at IS NULL THEN
        UPDATE dm_check_batches
           SET cancellation_requested_at=now(),
               cancelled_by_user_id=p_user_id,
               cancellation_reason=NULLIF(left(trim(COALESCE(p_reason,'')),240),''),
               updated_at=now()
         WHERE id=p_batch_id;

        -- Revoke only pre-send leases. A concurrent send transition serializes on
        -- the same attempt row and will either win (then remain in flight) or lose.
        UPDATE dm_job_attempts a
           SET state='REJECTED', completed_at=now(), failure_code='USER_CANCELLED'
          FROM dm_check_jobs j
         WHERE j.batch_id=p_batch_id AND a.job_id=j.id
           AND a.state='LEASED';

        UPDATE dm_check_jobs
           SET state='CANCELLED', updated_at=now()
         WHERE batch_id=p_batch_id AND state IN ('QUEUED','RETRY_PENDING','LEASED');
        
        INSERT INTO dm_gateway_audit_events(
            tenant_id, actor_user_id, event_type, details
        ) VALUES (
            p_tenant_id, p_user_id, 'REQUEST_CANCELLED',
            jsonb_build_object('batch_id',p_batch_id,'reason',NULLIF(left(trim(COALESCE(p_reason,'')),240),''))
        );
    END IF;

    RETURN QUERY
    SELECT TRUE, b.status,
           count(j.id),
           count(j.id) FILTER (WHERE j.state='COMPLETED'),
           count(j.id) FILTER (WHERE j.state='CANCELLED'),
           count(j.id) FILTER (WHERE j.state IN ('SENDING','AWAITING_REPLY','AMBIGUOUS','LEASED'))
      FROM dm_check_batches b
      LEFT JOIN dm_check_jobs j ON j.batch_id=b.id
     WHERE b.id=p_batch_id
     GROUP BY b.status;
END;
$$;

-- Record gateway health transitions, including loss and restoration of eligibility.
CREATE TABLE IF NOT EXISTS dm_gateway_health_events (
    id BIGSERIAL PRIMARY KEY,
    gateway_id UUID NOT NULL REFERENCES dm_gateways(id) ON DELETE CASCADE,
    previous_state TEXT,
    new_state TEXT NOT NULL,
    event_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    app_version TEXT
);
CREATE INDEX IF NOT EXISTS dm_gateway_health_events_gateway_time_idx
    ON dm_gateway_health_events(gateway_id, event_at DESC);

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
        previous_state TEXT;
BEGIN
    SELECT health_state INTO previous_state
      FROM dm_gateways WHERE id=p_gateway_id FOR UPDATE;
    IF NOT FOUND THEN RETURN NULL; END IF;

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

    IF previous_state IS DISTINCT FROM resulting_state THEN
        INSERT INTO dm_gateway_health_events(gateway_id,previous_state,new_state,app_version)
        VALUES (p_gateway_id,previous_state,resulting_state,p_app_version);
        INSERT INTO dm_gateway_audit_events(gateway_id,event_type,details)
        VALUES (p_gateway_id,'GATEWAY_HEALTH_CHANGED',
                jsonb_build_object('previous_state',previous_state,'new_state',resulting_state));
    END IF;
    RETURN resulting_state;
END;
$$;

COMMIT;
