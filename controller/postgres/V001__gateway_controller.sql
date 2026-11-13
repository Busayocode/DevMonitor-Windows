-- DevMonitor 5.0 Gateway Controller
-- PostgreSQL reference migration. Apply only to a dedicated controller database.
-- This does not touch the existing DevMonitor/Firebase databases.
BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS dm_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    auth_subject TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS dm_tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS dm_tenant_memberships (
    tenant_id UUID NOT NULL REFERENCES dm_tenants(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES dm_users(id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('OWNER','ADMIN','MEMBER')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id)
);

CREATE TABLE IF NOT EXISTS dm_gateways (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id UUID NOT NULL REFERENCES dm_users(id),
    display_name TEXT NOT NULL,
    credential_hash BYTEA NOT NULL UNIQUE,
    health_state TEXT NOT NULL DEFAULT 'NOT_READY'
        CHECK (health_state IN ('READY','OFFLINE','NOT_READY','QUARANTINED')),
    accept_pool_jobs BOOLEAN NOT NULL DEFAULT FALSE,
    app_version TEXT,
    sms_permission_ready BOOLEAN NOT NULL DEFAULT FALSE,
    telephony_ready BOOLEAN NOT NULL DEFAULT FALSE,
    network_ready BOOLEAN NOT NULL DEFAULT FALSE,
    heartbeat_at TIMESTAMPTZ,
    last_successful_3030_at TIMESTAMPTZ,
    quarantined_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS dm_gateways_health_heartbeat_idx
    ON dm_gateways (health_state, heartbeat_at);

CREATE TABLE IF NOT EXISTS dm_check_batches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    requested_by_user_id UUID NOT NULL,
    client_idempotency_key TEXT NOT NULL,
    app_edition TEXT NOT NULL CHECK (app_edition IN ('GATEWAY')),
    app_version TEXT,
    status TEXT NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED','RUNNING','COMPLETED','PARTIAL','FAILED','CANCELLED')),
    total_jobs INTEGER NOT NULL DEFAULT 0 CHECK (total_jobs >= 0),
    completed_jobs INTEGER NOT NULL DEFAULT 0 CHECK (completed_jobs >= 0),
    failed_jobs INTEGER NOT NULL DEFAULT 0 CHECK (failed_jobs >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, requested_by_user_id)
        REFERENCES dm_tenant_memberships(tenant_id, user_id),
    UNIQUE (tenant_id, requested_by_user_id, client_idempotency_key)
);

CREATE TABLE IF NOT EXISTS dm_check_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id UUID NOT NULL REFERENCES dm_check_batches(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL,
    requested_by_user_id UUID NOT NULL,
    canonical_device_id TEXT NOT NULL,
    query_profile TEXT NOT NULL,
    preferred_gateway_id UUID REFERENCES dm_gateways(id),
    state TEXT NOT NULL DEFAULT 'QUEUED'
        CHECK (state IN ('QUEUED','LEASED','SENDING','AWAITING_REPLY','RETRY_PENDING','COMPLETED','FAILED','CANCELLED','AMBIGUOUS')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts INTEGER NOT NULL DEFAULT 2 CHECK (max_attempts > 0),
    enqueued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, requested_by_user_id)
        REFERENCES dm_tenant_memberships(tenant_id, user_id)
);

CREATE INDEX IF NOT EXISTS dm_jobs_tenant_queue_idx
    ON dm_check_jobs (tenant_id, enqueued_at, id) WHERE state IN ('QUEUED','RETRY_PENDING');
CREATE INDEX IF NOT EXISTS dm_jobs_personal_queue_idx
    ON dm_check_jobs (preferred_gateway_id, enqueued_at, id)
    WHERE state IN ('QUEUED','RETRY_PENDING');

CREATE TABLE IF NOT EXISTS dm_job_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL REFERENCES dm_check_jobs(id) ON DELETE CASCADE,
    gateway_id UUID NOT NULL REFERENCES dm_gateways(id),
    attempt_number INTEGER NOT NULL,
    lease_token_hash BYTEA NOT NULL UNIQUE,
    state TEXT NOT NULL CHECK (state IN ('LEASED','SENDING','AWAITING_REPLY','COMPLETED','SEND_FAILED','TIMED_OUT','AMBIGUOUS','REJECTED')),
    leased_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_expires_at TIMESTAMPTZ NOT NULL,
    send_started_at TIMESTAMPTZ,
    sms_sent_at TIMESTAMPTZ,
    reply_received_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    failure_code TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (job_id, attempt_number)
);

-- A phone/SIM may have only one unresolved 3030 transaction. AMBIGUOUS deliberately
-- remains blocking until explicit safe resolution; it is not a normal lease expiry.
CREATE UNIQUE INDEX IF NOT EXISTS dm_one_active_attempt_per_gateway
    ON dm_job_attempts (gateway_id)
    WHERE state IN ('LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS');
CREATE UNIQUE INDEX IF NOT EXISTS dm_one_active_attempt_per_job
    ON dm_job_attempts (job_id)
    WHERE state IN ('LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS');

CREATE TABLE IF NOT EXISTS dm_daily_sms_results (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES dm_tenants(id),
    canonical_device_id TEXT NOT NULL,
    business_date DATE NOT NULL,
    checked_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    values_json JSONB NOT NULL,
    gateway_id UUID NOT NULL REFERENCES dm_gateways(id),
    job_id UUID NOT NULL REFERENCES dm_check_jobs(id),
    attempt_id UUID NOT NULL UNIQUE REFERENCES dm_job_attempts(id),
    parser_version TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, canonical_device_id, business_date)
);

CREATE INDEX IF NOT EXISTS dm_daily_results_history_idx
    ON dm_daily_sms_results (tenant_id, canonical_device_id, business_date DESC);

CREATE TABLE IF NOT EXISTS dm_tenant_dispatch_state (
    tenant_id UUID PRIMARY KEY REFERENCES dm_tenants(id) ON DELETE CASCADE,
    last_served_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS dm_gateway_audit_events (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID REFERENCES dm_tenants(id),
    actor_user_id UUID REFERENCES dm_users(id),
    gateway_id UUID REFERENCES dm_gateways(id),
    job_id UUID REFERENCES dm_check_jobs(id),
    event_type TEXT NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Enforce personal gateway ownership when a preferred gateway is selected.
CREATE OR REPLACE FUNCTION dm_validate_personal_gateway_owner()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE gateway_owner UUID;
BEGIN
    IF NEW.preferred_gateway_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT owner_user_id INTO gateway_owner
      FROM dm_gateways WHERE id = NEW.preferred_gateway_id;
    IF gateway_owner IS NULL OR gateway_owner <> NEW.requested_by_user_id THEN
        RAISE EXCEPTION 'preferred gateway is not owned by requesting user'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS dm_check_job_personal_gateway_owner ON dm_check_jobs;
CREATE TRIGGER dm_check_job_personal_gateway_owner
BEFORE INSERT OR UPDATE OF preferred_gateway_id, requested_by_user_id
ON dm_check_jobs FOR EACH ROW EXECUTE FUNCTION dm_validate_personal_gateway_owner();

-- Create one dispatch cursor for every tenant that submits work.
CREATE OR REPLACE FUNCTION dm_ensure_tenant_dispatch_state()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO dm_tenant_dispatch_state(tenant_id)
    VALUES (NEW.tenant_id) ON CONFLICT (tenant_id) DO NOTHING;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS dm_job_dispatch_state ON dm_check_jobs;
CREATE TRIGGER dm_job_dispatch_state
AFTER INSERT ON dm_check_jobs FOR EACH ROW EXECUTE FUNCTION dm_ensure_tenant_dispatch_state();

-- Claim one job atomically. The API must authenticate the gateway credential first
-- and pass only the gateway ID resolved from that credential, never a client-supplied ID.
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

    -- Personal fast lane: the owner's own preferred jobs are always considered first.
    SELECT * INTO selected_job
      FROM dm_check_jobs j
     WHERE j.preferred_gateway_id = p_gateway_id
       AND j.requested_by_user_id = gateway_row.owner_user_id
       AND j.state IN ('QUEUED','RETRY_PENDING')
     ORDER BY j.enqueued_at, j.id
     FOR UPDATE SKIP LOCKED
     LIMIT 1;

    -- Shared pool: only opted-in phones enter the tenant round-robin scheduler.
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

    IF selected_job.id IS NULL THEN
        RETURN;
    END IF;

    new_attempt_id := gen_random_uuid();
    raw_lease_token := encode(gen_random_bytes(32), 'hex');
    new_expiry := now() + make_interval(secs => p_lease_seconds);

    INSERT INTO dm_job_attempts(
        id, job_id, gateway_id, attempt_number, lease_token_hash,
        state, lease_expires_at
    ) VALUES (
        new_attempt_id, selected_job.id, p_gateway_id,
        selected_job.attempt_count + 1,
        digest(decode(raw_lease_token, 'hex'), 'sha256'),
        'LEASED', new_expiry
    );

    UPDATE dm_check_jobs
       SET state = 'LEASED',
           attempt_count = attempt_count + 1,
           updated_at = now()
     WHERE id = selected_job.id;

    UPDATE dm_check_batches
       SET status = 'RUNNING', updated_at = now()
     WHERE id = selected_job.batch_id
       AND status = 'QUEUED';

    RETURN QUERY SELECT selected_job.id, new_attempt_id, raw_lease_token,
                        selected_job.canonical_device_id, selected_job.query_profile,
                        selected_job.attempt_count + 1, new_expiry;
END;
$$;

-- Gateway heartbeats cannot clear quarantine. A quarantined gateway requires a
-- separate privileged operator-resolution path after the ambiguous SMS is resolved.
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

-- Result acceptance is lease-token and attempt bound. The API must also authenticate
-- the gateway and call this with the ID resolved from its device credential.
CREATE OR REPLACE FUNCTION dm_complete_job_result(
    p_gateway_id UUID,
    p_job_id UUID,
    p_attempt_id UUID,
    p_lease_token TEXT,
    p_business_date DATE,
    p_checked_at TIMESTAMPTZ,
    p_received_at TIMESTAMPTZ,
    p_values_json JSONB,
    p_parser_version TEXT
)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE attempt_row dm_job_attempts%ROWTYPE;
       job_row dm_check_jobs%ROWTYPE;
BEGIN
    SELECT * INTO attempt_row
      FROM dm_job_attempts
     WHERE id = p_attempt_id
       AND job_id = p_job_id
       AND gateway_id = p_gateway_id
       AND lease_token_hash = digest(decode(p_lease_token, 'hex'), 'sha256')
       AND state IN ('LEASED','SENDING','AWAITING_REPLY')
     FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;

    SELECT * INTO job_row FROM dm_check_jobs
     WHERE id = p_job_id AND state IN ('LEASED','SENDING','AWAITING_REPLY')
     FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;

    INSERT INTO dm_daily_sms_results(
        tenant_id, canonical_device_id, business_date, checked_at, received_at,
        values_json, gateway_id, job_id, attempt_id, parser_version
    ) VALUES (
        job_row.tenant_id, job_row.canonical_device_id, p_business_date,
        p_checked_at, p_received_at, p_values_json, p_gateway_id,
        p_job_id, p_attempt_id, p_parser_version
    )
    ON CONFLICT (tenant_id, canonical_device_id, business_date)
    DO UPDATE SET checked_at = EXCLUDED.checked_at,
                  received_at = EXCLUDED.received_at,
                  values_json = EXCLUDED.values_json,
                  gateway_id = EXCLUDED.gateway_id,
                  job_id = EXCLUDED.job_id,
                  attempt_id = EXCLUDED.attempt_id,
                  parser_version = EXCLUDED.parser_version
    WHERE EXCLUDED.checked_at > dm_daily_sms_results.checked_at;

    UPDATE dm_job_attempts
       SET state = 'COMPLETED', reply_received_at = p_received_at,
           completed_at = now()
     WHERE id = p_attempt_id;
    UPDATE dm_check_jobs
       SET state = 'COMPLETED', updated_at = now()
     WHERE id = p_job_id;
    UPDATE dm_check_batches b
       SET completed_jobs = (
               SELECT count(*) FROM dm_check_jobs j
                WHERE j.batch_id = b.id AND j.state = 'COMPLETED'
           ),
           failed_jobs = (
               SELECT count(*) FROM dm_check_jobs j
                WHERE j.batch_id = b.id AND j.state IN ('FAILED','AMBIGUOUS')
           ),
           status = CASE
               WHEN NOT EXISTS (SELECT 1 FROM dm_check_jobs j WHERE j.batch_id = b.id AND j.state IN ('QUEUED','RETRY_PENDING','LEASED','SENDING','AWAITING_REPLY'))
               THEN CASE
                   WHEN EXISTS (SELECT 1 FROM dm_check_jobs j WHERE j.batch_id = b.id AND j.state IN ('FAILED','AMBIGUOUS'))
                   THEN 'PARTIAL' ELSE 'COMPLETED' END
               ELSE 'RUNNING'
           END,
           updated_at = now()
     WHERE b.id = job_row.batch_id;
    RETURN TRUE;
END;
$$;

-- A timeout after a possible send is ambiguous. It quarantines the phone and blocks
-- further claims until explicit operator resolution; no blind reassignment is done here.
CREATE OR REPLACE FUNCTION dm_mark_attempt_ambiguous(
    p_gateway_id UUID,
    p_job_id UUID,
    p_attempt_id UUID,
    p_reason TEXT
)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
    UPDATE dm_job_attempts
       SET state = 'AMBIGUOUS', failure_code = p_reason
     WHERE id = p_attempt_id AND job_id = p_job_id AND gateway_id = p_gateway_id
       AND state IN ('LEASED','SENDING','AWAITING_REPLY');
    IF NOT FOUND THEN RETURN FALSE; END IF;

    UPDATE dm_check_jobs SET state = 'AMBIGUOUS', updated_at = now()
     WHERE id = p_job_id;
    UPDATE dm_gateways SET health_state = 'QUARANTINED',
           quarantined_reason = p_reason, updated_at = now()
     WHERE id = p_gateway_id;
    RETURN TRUE;
END;
$$;

COMMIT;
