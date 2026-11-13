-- DevMonitor 5.0 - periodic queue snapshots for historical Gateway analytics.
BEGIN;

CREATE TABLE IF NOT EXISTS dm_tenant_queue_snapshots (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES dm_tenants(id) ON DELETE CASCADE,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    pending_devices INTEGER NOT NULL DEFAULT 0,
    waiting_devices INTEGER NOT NULL DEFAULT 0,
    active_devices INTEGER NOT NULL DEFAULT 0,
    waiting_requests INTEGER NOT NULL DEFAULT 0,
    active_requests INTEGER NOT NULL DEFAULT 0,
    ready_gateways INTEGER NOT NULL DEFAULT 0,
    busy_gateways INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS dm_tenant_queue_snapshots_time_idx
    ON dm_tenant_queue_snapshots(tenant_id,captured_at DESC);

CREATE OR REPLACE FUNCTION dm_capture_tenant_queue_snapshots()
RETURNS INTEGER LANGUAGE plpgsql AS $$
DECLARE inserted_count INTEGER;
BEGIN
    INSERT INTO dm_tenant_queue_snapshots(
        tenant_id,captured_at,pending_devices,waiting_devices,active_devices,
        waiting_requests,active_requests,ready_gateways,busy_gateways
    )
    SELECT t.id,now(),
           count(j.id) FILTER (WHERE j.state IN ('QUEUED','RETRY_PENDING','LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS')),
           count(j.id) FILTER (WHERE j.state IN ('QUEUED','RETRY_PENDING')),
           count(j.id) FILTER (WHERE j.state IN ('LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS')),
           count(DISTINCT b.id) FILTER (WHERE j.state IN ('QUEUED','RETRY_PENDING')),
           count(DISTINCT b.id) FILTER (WHERE j.state IN ('LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS')),
           (
             SELECT count(*) FROM dm_gateways g
              WHERE g.health_state='READY' AND g.revoked_at IS NULL
                AND g.heartbeat_at >= now()-INTERVAL '90 seconds'
                AND g.sms_permission_ready AND g.telephony_ready AND g.network_ready
                AND (g.accept_pool_jobs OR g.owner_user_id IN (
                    SELECT m.user_id FROM dm_tenant_memberships m WHERE m.tenant_id=t.id
                ))
           ),
           (
             SELECT count(*) FROM dm_gateways g
              WHERE g.health_state='READY' AND g.revoked_at IS NULL
                AND g.heartbeat_at >= now()-INTERVAL '90 seconds'
                AND g.sms_permission_ready AND g.telephony_ready AND g.network_ready
                AND EXISTS (
                    SELECT 1 FROM dm_job_attempts a
                     WHERE a.gateway_id=g.id
                       AND a.state IN ('LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS')
                )
                AND (g.accept_pool_jobs OR g.owner_user_id IN (
                    SELECT m.user_id FROM dm_tenant_memberships m WHERE m.tenant_id=t.id
                ))
           )
      FROM dm_tenants t
      LEFT JOIN dm_check_batches b
        ON b.tenant_id=t.id AND b.cancellation_requested_at IS NULL
      LEFT JOIN dm_check_jobs j
        ON j.batch_id=b.id
     GROUP BY t.id;

    GET DIAGNOSTICS inserted_count = ROW_COUNT;
    DELETE FROM dm_tenant_queue_snapshots
     WHERE captured_at < now()-INTERVAL '90 days';
    RETURN inserted_count;
END;
$$;

COMMIT;
