-- DevMonitor 5.0 Gateway Controller - concurrency and manual recovery fixes.
BEGIN;

-- Trigger must run for all inserts/updates; ownership is checked inside the function.
DROP TRIGGER IF EXISTS dm_check_job_personal_gateway_owner ON dm_check_jobs;
CREATE TRIGGER dm_check_job_personal_gateway_owner
BEFORE INSERT OR UPDATE ON dm_check_jobs
FOR EACH ROW EXECUTE FUNCTION dm_validate_personal_gateway_owner();

-- Explicitly lock the selected queue row in the shared-pool lateral query.
-- The tenant cursor lock provides fair rotation; the job lock prevents two
-- different gateways from claiming the same queued item.
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
    IF p_lease_seconds < 30 OR p_lease_seconds > 600 THEN
        RAISE EXCEPTION 'lease duration out of allowed range';
    END IF;

    SELECT * INTO gateway_row
      FROM dm_gateways
     WHERE id = p_gateway_id
     FOR UPDATE;

    IF NOT FOUND OR gateway_row.health_state <> 'READY'
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

    -- Owner's personal jobs always take precedence on their paired gateway.
    SELECT * INTO selected_job
      FROM dm_check_jobs j
     WHERE j.preferred_gateway_id = p_gateway_id
       AND j.requested_by_user_id = gateway_row.owner_user_id
       AND j.state IN ('QUEUED','RETRY_PENDING')
     ORDER BY j.enqueued_at, j.id
     FOR UPDATE SKIP LOCKED
     LIMIT 1;

    IF NOT FOUND AND gateway_row.accept_pool_jobs THEN
        SELECT j.* INTO selected_job
          FROM dm_tenant_dispatch_state s
          JOIN LATERAL (
              SELECT q.*
                FROM dm_check_jobs q
               WHERE q.tenant_id = s.tenant_id
                 AND q.state IN ('QUEUED','RETRY_PENDING')
                 AND (
                     q.preferred_gateway_id IS NULL
                     OR NOT EXISTS (
                         SELECT 1 FROM dm_gateways pg
                          WHERE pg.id = q.preferred_gateway_id
                            AND pg.health_state = 'READY'
                            AND pg.heartbeat_at >= now() - INTERVAL '90 seconds'
                     )
                 )
               ORDER BY q.enqueued_at, q.id
               LIMIT 1
               FOR UPDATE SKIP LOCKED
          ) j ON TRUE
         ORDER BY s.last_served_at NULLS FIRST, j.enqueued_at, s.tenant_id
         FOR UPDATE OF s SKIP LOCKED
         LIMIT 1;

        IF FOUND THEN
            UPDATE dm_tenant_dispatch_state
               SET last_served_at = now(), updated_at = now()
             WHERE tenant_id = selected_job.tenant_id;
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

-- An operator must first establish that the gateway inbox/transaction is safe.
-- Only then can the ambiguous attempt be discarded or explicitly requeued.
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

COMMIT;
