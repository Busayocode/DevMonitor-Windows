-- DevMonitor 5.0 Gateway Controller - hierarchical tenant/requester fair scheduling.
BEGIN;

-- Fairness is hierarchical: first rotate across tenants, then across requesters
-- within the selected tenant. This prevents one user's large batch from blocking
-- another user even when both users belong to the same company/tenant.
CREATE TABLE IF NOT EXISTS dm_requester_dispatch_state (
    tenant_id UUID NOT NULL,
    requested_by_user_id UUID NOT NULL,
    last_served_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, requested_by_user_id),
    FOREIGN KEY (tenant_id, requested_by_user_id)
        REFERENCES dm_tenant_memberships(tenant_id, user_id) ON DELETE CASCADE
);

CREATE OR REPLACE FUNCTION dm_ensure_tenant_dispatch_state()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO dm_tenant_dispatch_state(tenant_id)
    VALUES (NEW.tenant_id) ON CONFLICT (tenant_id) DO NOTHING;
    INSERT INTO dm_requester_dispatch_state(tenant_id, requested_by_user_id)
    VALUES (NEW.tenant_id, NEW.requested_by_user_id)
    ON CONFLICT (tenant_id, requested_by_user_id) DO NOTHING;
    RETURN NEW;
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

    SELECT * INTO gateway_row
      FROM dm_gateways
     WHERE id = p_gateway_id
     FOR UPDATE;

    IF NOT FOUND OR gateway_row.health_state <> 'READY'
       OR gateway_row.revoked_at IS NOT NULL
       OR gateway_row.heartbeat_at IS NULL
       OR gateway_row.heartbeat_at < now() - INTERVAL '90 seconds'
       OR NOT gateway_row.sms_permission_ready
       OR NOT gateway_row.telephony_ready
       OR NOT gateway_row.network_ready THEN
        RETURN;
    END IF;

    IF EXISTS (
        SELECT 1 FROM dm_job_attempts a
         WHERE a.gateway_id = p_gateway_id
           AND a.state IN ('LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS')
    ) THEN
        RETURN;
    END IF;

    -- Owner's personal jobs are considered before any shared-pool work.
    SELECT * INTO selected_job
      FROM dm_check_jobs j
     WHERE j.preferred_gateway_id = p_gateway_id
       AND j.requested_by_user_id = gateway_row.owner_user_id
       AND j.state IN ('QUEUED','RETRY_PENDING')
     ORDER BY j.enqueued_at, j.id
     LIMIT 1
     FOR UPDATE SKIP LOCKED;

    IF NOT FOUND AND gateway_row.accept_pool_jobs THEN
        -- First select a tenant by its tenant-level round-robin cursor. Within
        -- that tenant, select the least-recently-served requester with eligible work.
        SELECT chosen_job.* INTO selected_job
          FROM dm_tenant_dispatch_state tenant_cursor
          JOIN LATERAL (
              SELECT requester_job.*
                FROM dm_requester_dispatch_state requester_cursor
                JOIN LATERAL (
                    SELECT q.*
                      FROM dm_check_jobs q
                     WHERE q.tenant_id = requester_cursor.tenant_id
                       AND q.requested_by_user_id = requester_cursor.requested_by_user_id
                       AND q.state IN ('QUEUED','RETRY_PENDING')
                       AND (
                           q.preferred_gateway_id IS NULL
                           OR NOT EXISTS (
                               SELECT 1 FROM dm_gateways pg
                                WHERE pg.id = q.preferred_gateway_id
                                  AND pg.health_state = 'READY'
                                  AND pg.revoked_at IS NULL
                                  AND pg.heartbeat_at >= now() - INTERVAL '90 seconds'
                           )
                       )
                     ORDER BY q.enqueued_at, q.id
                     LIMIT 1
                     FOR UPDATE SKIP LOCKED
                ) requester_job ON TRUE
               WHERE requester_cursor.tenant_id = tenant_cursor.tenant_id
               ORDER BY requester_cursor.last_served_at NULLS FIRST,
                        requester_job.enqueued_at,
                        requester_cursor.requested_by_user_id
               LIMIT 1
               FOR UPDATE OF requester_cursor SKIP LOCKED
          ) chosen_job ON TRUE
         ORDER BY tenant_cursor.last_served_at NULLS FIRST,
                  chosen_job.enqueued_at,
                  tenant_cursor.tenant_id
         LIMIT 1
         FOR UPDATE OF tenant_cursor SKIP LOCKED;

        IF FOUND THEN
            UPDATE dm_tenant_dispatch_state
               SET last_served_at = now(), updated_at = now()
             WHERE tenant_id = selected_job.tenant_id;
            UPDATE dm_requester_dispatch_state
               SET last_served_at = now(), updated_at = now()
             WHERE tenant_id = selected_job.tenant_id
               AND requested_by_user_id = selected_job.requested_by_user_id;
        END IF;
    END IF;

    IF selected_job.id IS NULL THEN RETURN; END IF;

    new_attempt_id := gen_random_uuid();
    raw_lease_token := encode(gen_random_bytes(32), 'hex');
    new_expiry := now() + make_interval(secs => p_lease_seconds);

    INSERT INTO dm_job_attempts(
        id, job_id, gateway_id, attempt_number, lease_token_hash, state, lease_expires_at
    ) VALUES (
        new_attempt_id, selected_job.id, p_gateway_id,
        selected_job.attempt_count + 1,
        digest(decode(raw_lease_token, 'hex'), 'sha256'),
        'LEASED', new_expiry
    );

    UPDATE dm_check_jobs
       SET state = 'LEASED', attempt_count = attempt_count + 1, updated_at = now()
     WHERE id = selected_job.id;

    UPDATE dm_check_batches SET status = 'RUNNING', updated_at = now()
     WHERE id = selected_job.batch_id AND status = 'QUEUED';

    RETURN QUERY SELECT selected_job.id, new_attempt_id, raw_lease_token,
                        selected_job.canonical_device_id, selected_job.query_profile,
                        selected_job.attempt_count + 1, new_expiry;
END;
$$;

COMMIT;
