-- DevMonitor 5.0 - request-level FIFO distribution across multiple Gateway workers.
BEGIN;

ALTER TABLE dm_check_batches
    ADD COLUMN IF NOT EXISTS assigned_gateway_id UUID REFERENCES dm_gateways(id),
    ADD COLUMN IF NOT EXISTS assignment_updated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS processing_started_at TIMESTAMPTZ;

-- Backfill batches that had already received an attempt before this migration.
UPDATE dm_check_batches b
   SET processing_started_at=started.first_started_at
  FROM (
      SELECT j.batch_id,min(a.leased_at) AS first_started_at
        FROM dm_check_jobs j
        JOIN dm_job_attempts a ON a.job_id=j.id
       GROUP BY j.batch_id
  ) started
 WHERE b.id=started.batch_id AND b.processing_started_at IS NULL;

CREATE INDEX IF NOT EXISTS dm_batches_gateway_assignment_idx
    ON dm_check_batches (assigned_gateway_id, created_at, id)
    WHERE cancellation_requested_at IS NULL;

-- Reassign only unstarted request batches. A live send/reply attempt stays with
-- its original phone; an AMBIGUOUS device remains quarantined but does not prevent
-- other, not-yet-started devices in the same request from moving to a healthy phone.
CREATE OR REPLACE FUNCTION dm_rebalance_unstarted_batches()
RETURNS INTEGER LANGUAGE plpgsql AS $$
DECLARE changed_count INTEGER;
BEGIN
    WITH gateway_load AS (
        SELECT g.id,
               count(DISTINCT b.id) FILTER (
                   WHERE b.processing_started_at IS NOT NULL
                     AND EXISTS (
                         SELECT 1 FROM dm_check_jobs pending_job
                          WHERE pending_job.batch_id=b.id
                            AND pending_job.state IN ('QUEUED','RETRY_PENDING','LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS')
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
               row_number() OVER (ORDER BY active_batches,id)-1 AS gateway_slot,
               count(*) OVER () AS gateway_count
          FROM gateway_load
    ),
    pending_base AS (
        SELECT b.id,b.tenant_id,b.requested_by_user_id,
               pref.preferred_gateway_id,b.created_at
          FROM dm_check_batches b
          LEFT JOIN LATERAL (
              SELECT min(j.preferred_gateway_id::text)::uuid AS preferred_gateway_id
                FROM dm_check_jobs j
               WHERE j.batch_id=b.id
          ) pref ON TRUE
         WHERE b.cancellation_requested_at IS NULL
           AND EXISTS (
               SELECT 1 FROM dm_check_jobs j
                WHERE j.batch_id=b.id AND j.state IN ('QUEUED','RETRY_PENDING')
           )
           AND NOT EXISTS (
               SELECT 1 FROM dm_check_jobs j
                WHERE j.batch_id=b.id
                  AND j.state IN ('LEASED','SENDING','AWAITING_REPLY')
           )
           AND (
               b.processing_started_at IS NULL
               OR NOT EXISTS (
                   SELECT 1 FROM dm_gateways assigned
                    WHERE assigned.id=b.assigned_gateway_id
                      AND assigned.health_state='READY'
                      AND assigned.revoked_at IS NULL
                      AND assigned.heartbeat_at >= now()-INTERVAL '90 seconds'
                      AND assigned.sms_permission_ready
                      AND assigned.telephony_ready
                      AND assigned.network_ready
                      AND (
                          assigned.accept_pool_jobs
                          OR EXISTS (
                              SELECT 1 FROM dm_check_jobs owner_job
                               WHERE owner_job.batch_id=b.id
                                 AND owner_job.preferred_gateway_id=assigned.id
                                 AND owner_job.requested_by_user_id=assigned.owner_user_id
                          )
                      )
               )
           )
    ),
    personal_targets AS (
        SELECT p.id AS batch_id,owned.id AS target_gateway_id
          FROM pending_base p
          JOIN dm_gateways owned
            ON owned.id=p.preferred_gateway_id
           AND owned.owner_user_id=p.requested_by_user_id
           AND owned.health_state='READY'
           AND owned.revoked_at IS NULL
           AND owned.heartbeat_at >= now()-INTERVAL '90 seconds'
           AND owned.sms_permission_ready AND owned.telephony_ready AND owned.network_ready
    ),
    shared_pending AS (
        SELECT p.id,
               row_number() OVER (ORDER BY p.created_at,p.id)-1 AS batch_slot
          FROM pending_base p
         WHERE NOT EXISTS (
             SELECT 1 FROM dm_gateways owned
              WHERE owned.id=p.preferred_gateway_id
                AND owned.owner_user_id=p.requested_by_user_id
                AND owned.health_state='READY'
                AND owned.revoked_at IS NULL
                AND owned.heartbeat_at >= now()-INTERVAL '90 seconds'
                AND owned.sms_permission_ready AND owned.telephony_ready AND owned.network_ready
         )
    ),
    shared_targets AS (
        SELECT p.id AS batch_id,
               g.id AS target_gateway_id
          FROM shared_pending p
          JOIN pool g
            ON g.gateway_slot = (p.batch_slot % NULLIF((SELECT count(*) FROM pool),0))
    ),
    targets AS (
        SELECT batch_id,target_gateway_id FROM personal_targets
        UNION ALL
        SELECT batch_id,target_gateway_id FROM shared_targets
        UNION ALL
        SELECT p.id AS batch_id,NULL::UUID AS target_gateway_id
          FROM pending_base p
         WHERE NOT EXISTS (SELECT 1 FROM personal_targets x WHERE x.batch_id=p.id)
           AND NOT EXISTS (SELECT 1 FROM shared_targets x WHERE x.batch_id=p.id)
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
    SELECT tenant_id,requested_by_user_id,assigned_gateway_id,
           CASE WHEN assigned_gateway_id IS NULL THEN 'REQUEST_BATCH_UNASSIGNED'
                ELSE 'REQUEST_BATCH_ASSIGNED' END,
           jsonb_build_object('batch_id',id,'gateway_id',assigned_gateway_id)
      FROM changed;

    GET DIAGNOSTICS changed_count = ROW_COUNT;
    RETURN changed_count;
END;
$$;

-- A ready-state transition (for example, a second phone coming online) redistributes
-- all unstarted batches. A heartbeat that leaves the state unchanged does not churn
-- assignments. The health-transition history introduced in V009 remains intact.
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
       SET app_version=p_app_version,
           sms_permission_ready=p_sms_permission_ready,
           telephony_ready=p_telephony_ready,
           network_ready=p_network_ready,
           heartbeat_at=now(),
           last_successful_3030_at=COALESCE(p_last_successful_3030_at,last_successful_3030_at),
           health_state=CASE
               WHEN revoked_at IS NOT NULL THEN 'OFFLINE'
               WHEN health_state='QUARANTINED' THEN 'QUARANTINED'
               WHEN p_sms_permission_ready AND p_telephony_ready AND p_network_ready THEN 'READY'
               ELSE 'NOT_READY'
           END,
           updated_at=now()
     WHERE id=p_gateway_id
     RETURNING health_state INTO resulting_state;

    IF previous_state IS DISTINCT FROM resulting_state THEN
        -- V013 records the health transition through a single table trigger.
        PERFORM dm_rebalance_unstarted_batches();
    END IF;
    RETURN resulting_state;
END;
$$;

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

    -- If a phone holding unstarted batches has gone stale/offline, release those
    -- batches to the remaining healthy pool before this worker claims its next job.
    IF EXISTS (
        SELECT 1 FROM dm_check_batches b
         WHERE b.assigned_gateway_id IS NOT NULL
           AND b.cancellation_requested_at IS NULL
           AND EXISTS (SELECT 1 FROM dm_check_jobs j
                        WHERE j.batch_id=b.id AND j.state IN ('QUEUED','RETRY_PENDING'))
           AND NOT EXISTS (SELECT 1 FROM dm_gateways g
                            WHERE g.id=b.assigned_gateway_id
                              AND g.health_state='READY' AND g.revoked_at IS NULL
                              AND g.heartbeat_at >= now()-INTERVAL '90 seconds'
                              AND g.sms_permission_ready AND g.telephony_ready AND g.network_ready)
    ) THEN
        PERFORM dm_rebalance_unstarted_batches();
    END IF;

    SELECT j.* INTO selected_job
      FROM dm_check_batches b
      JOIN dm_check_jobs j ON j.batch_id=b.id
     WHERE b.assigned_gateway_id=p_gateway_id
       AND b.cancellation_requested_at IS NULL
       AND j.state IN ('QUEUED','RETRY_PENDING')
     ORDER BY CASE WHEN EXISTS (
                  SELECT 1 FROM dm_check_jobs own_job
                   WHERE own_job.batch_id=b.id
                     AND own_job.preferred_gateway_id=p_gateway_id
                     AND own_job.requested_by_user_id=gateway_row.owner_user_id
              ) THEN 0 ELSE 1 END,
              b.created_at,b.id,j.enqueued_at,j.id
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
       SET status='RUNNING',
           processing_started_at=COALESCE(processing_started_at,now()),
           updated_at=now()
     WHERE id=selected_job.batch_id;

    RETURN QUERY SELECT selected_job.id,new_attempt_id,raw_lease_token,
                        selected_job.canonical_device_id,selected_job.query_profile,
                        selected_job.attempt_count+1,new_expiry;
END;
$$;



CREATE OR REPLACE FUNCTION dm_set_gateway_pool_participation(
    p_owner_user_id UUID,
    p_gateway_id UUID,
    p_enabled BOOLEAN
)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
    UPDATE dm_gateways
       SET accept_pool_jobs=p_enabled,updated_at=now()
     WHERE id=p_gateway_id AND owner_user_id=p_owner_user_id
       AND revoked_at IS NULL;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    INSERT INTO dm_gateway_audit_events(actor_user_id,gateway_id,event_type,details)
    VALUES (p_owner_user_id,p_gateway_id,
            CASE WHEN p_enabled THEN 'POOL_OPT_IN' ELSE 'POOL_OPT_OUT' END,
            jsonb_build_object('accept_pool_jobs',p_enabled));
    PERFORM dm_rebalance_unstarted_batches();
    RETURN TRUE;
END;
$$;

CREATE OR REPLACE FUNCTION dm_revoke_personal_gateway(
    p_owner_user_id UUID,
    p_gateway_id UUID
)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
    UPDATE dm_gateways
       SET revoked_at=now(),health_state='OFFLINE',
           accept_pool_jobs=FALSE,updated_at=now()
     WHERE id=p_gateway_id AND owner_user_id=p_owner_user_id
       AND revoked_at IS NULL;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    INSERT INTO dm_gateway_audit_events(actor_user_id,gateway_id,event_type)
    VALUES (p_owner_user_id,p_gateway_id,'PERSONAL_GATEWAY_REVOKED');
    PERFORM dm_rebalance_unstarted_batches();
    RETURN TRUE;
END;
$$;

COMMIT;
