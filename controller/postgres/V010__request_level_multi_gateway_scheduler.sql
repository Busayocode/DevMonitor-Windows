-- DevMonitor 5.0 - request-level FIFO distribution across multiple Gateway workers.
BEGIN;

ALTER TABLE dm_check_batches
    ADD COLUMN IF NOT EXISTS assigned_gateway_id UUID REFERENCES dm_gateways(id),
    ADD COLUMN IF NOT EXISTS assignment_updated_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS dm_batches_gateway_assignment_idx
    ON dm_check_batches (assigned_gateway_id, created_at, id)
    WHERE cancellation_requested_at IS NULL;

-- Redistribute only batches with no live attempt. A batch already being processed
-- stays with its Gateway; unstarted request batches are spread across ready pool
-- Gateways in arrival order. Gateways with an in-flight request get the next turn
-- after idle Gateways, which gives the expected A/B/A/B distribution.
CREATE OR REPLACE FUNCTION dm_rebalance_unstarted_batches()
RETURNS INTEGER LANGUAGE plpgsql AS $$
DECLARE changed_count INTEGER;
BEGIN
    WITH gateway_load AS (
        SELECT g.id,
               count(DISTINCT b.id) FILTER (
                   WHERE EXISTS (
                       SELECT 1 FROM dm_check_jobs active_job
                        WHERE active_job.batch_id=b.id
                          AND active_job.state IN ('LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS')
                   )
               ) AS active_batches
          FROM dm_gateways g
          LEFT JOIN dm_check_batches b ON b.assigned_gateway_id=g.id
         WHERE g.health_state='READY'
           AND g.revoked_at IS NULL
           AND g.heartbeat_at >= now()-INTERVAL '90 seconds'
           AND g.sms_permission_ready AND g.telephony_ready AND g.network_ready
           AND g.accept_pool_jobs
         GROUP BY g.id
    ),
    pool AS (
        SELECT id,
               row_number() OVER (ORDER BY active_batches, id)-1 AS gateway_slot,
               count(*) OVER () AS gateway_count
          FROM gateway_load
    ),
    pending AS (
        SELECT b.id, b.tenant_id, b.requested_by_user_id, b.preferred_gateway_id,
               row_number() OVER (ORDER BY b.created_at,b.id)-1 AS batch_slot
          FROM dm_check_batches b
         WHERE b.cancellation_requested_at IS NULL
           AND EXISTS (
               SELECT 1 FROM dm_check_jobs j
                WHERE j.batch_id=b.id AND j.state IN ('QUEUED','RETRY_PENDING')
           )
           AND NOT EXISTS (
               SELECT 1 FROM dm_check_jobs j
                WHERE j.batch_id=b.id
                  AND j.state IN ('LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS')
           )
    ),
    targets AS (
        SELECT p.id AS batch_id,
               COALESCE(owned.id, shared.id) AS target_gateway_id
          FROM pending p
          LEFT JOIN dm_gateways owned
            ON owned.id=p.preferred_gateway_id
           AND owned.owner_user_id=p.requested_by_user_id
           AND owned.health_state='READY'
           AND owned.revoked_at IS NULL
           AND owned.heartbeat_at >= now()-INTERVAL '90 seconds'
           AND owned.sms_permission_ready AND owned.telephony_ready AND owned.network_ready
          LEFT JOIN pool shared
            ON shared.gateway_slot = (
                p.batch_slot % NULLIF((SELECT count(*) FROM pool),0)
            )
         WHERE owned.id IS NOT NULL OR shared.id IS NOT NULL
    ),
    changed AS (
        UPDATE dm_check_batches b
           SET assigned_gateway_id=t.target_gateway_id,
               assignment_updated_at=now(),
               updated_at=now()
          FROM targets t
         WHERE b.id=t.batch_id
           AND b.assigned_gateway_id IS DISTINCT FROM t.target_gateway_id
        RETURNING b.id,b.tenant_id,b.requested_by_user_id,b.assigned_gateway_id
    )
    INSERT INTO dm_gateway_audit_events(
        tenant_id,actor_user_id,gateway_id,event_type,details
    )
    SELECT tenant_id,requested_by_user_id,assigned_gateway_id,'REQUEST_BATCH_ASSIGNED',
           jsonb_build_object('batch_id',id,'gateway_id',assigned_gateway_id)
      FROM changed;

    GET DIAGNOSTICS changed_count = ROW_COUNT;
    RETURN changed_count;
END;
$$;

-- Request-level scheduling: a Gateway owns one request batch at a time, processing
-- its devices in request order. Other Gateways take the next request batch and run
-- in parallel. A new/returning Gateway triggers redistribution on the next claim;
-- stale/offline Gateways are excluded by the health/heartbeat eligibility test.
CREATE OR REPLACE FUNCTION dm_claim_next_job(
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
DECLARE
    gateway_row dm_gateways%ROWTYPE;
    selected_job dm_check_jobs%ROWTYPE;
    new_attempt_id UUID;
    raw_lease_token TEXT;
    new_expiry TIMESTAMPTZ;
BEGIN
    IF p_lease_seconds IS NULL OR p_lease_seconds < 30 OR p_lease_seconds > 600 THEN
        RAISE EXCEPTION 'lease duration out of allowed range';
    END IF;

    SELECT * INTO gateway_row FROM dm_gateways
     WHERE id=p_gateway_id FOR UPDATE;

    IF NOT FOUND OR gateway_row.health_state <> 'READY'
       OR gateway_row.revoked_at IS NOT NULL
       OR gateway_row.heartbeat_at IS NULL
       OR gateway_row.heartbeat_at < now()-INTERVAL '90 seconds'
       OR NOT gateway_row.sms_permission_ready
       OR NOT gateway_row.telephony_ready
       OR NOT gateway_row.network_ready THEN
        RETURN;
    END IF;

    IF EXISTS (
        SELECT 1 FROM dm_job_attempts a
         WHERE a.gateway_id=p_gateway_id
           AND a.state IN ('LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS')
    ) THEN
        RETURN;
    END IF;

    PERFORM dm_rebalance_unstarted_batches();

    SELECT j.* INTO selected_job
      FROM dm_check_batches b
      JOIN dm_check_jobs j ON j.batch_id=b.id
     WHERE b.assigned_gateway_id=p_gateway_id
       AND b.cancellation_requested_at IS NULL
       AND j.state IN ('QUEUED','RETRY_PENDING')
     ORDER BY b.created_at,b.id,j.enqueued_at,j.id
     LIMIT 1
     FOR UPDATE OF b,j SKIP LOCKED;

    IF NOT FOUND THEN RETURN; END IF;

    new_attempt_id := gen_random_uuid();
    raw_lease_token := encode(gen_random_bytes(32),'hex');
    new_expiry := now()+make_interval(secs => p_lease_seconds);

    INSERT INTO dm_job_attempts(
        id,job_id,gateway_id,attempt_number,lease_token_hash,state,lease_expires_at
    ) VALUES (
        new_attempt_id,selected_job.id,p_gateway_id,selected_job.attempt_count+1,
        digest(decode(raw_lease_token,'hex'),'sha256'),'LEASED',new_expiry
    );

    UPDATE dm_check_jobs
       SET state='LEASED',attempt_count=attempt_count+1,updated_at=now()
     WHERE id=selected_job.id;

    UPDATE dm_check_batches
       SET status='RUNNING',updated_at=now()
     WHERE id=selected_job.batch_id AND status='QUEUED';

    RETURN QUERY SELECT selected_job.id,new_attempt_id,raw_lease_token,
                        selected_job.canonical_device_id,selected_job.query_profile,
                        selected_job.attempt_count+1,new_expiry;
END;
$$;

COMMIT;
