PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS dm_users (
  id TEXT PRIMARY KEY,
  auth_subject TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE IF NOT EXISTS dm_tenants (
  id TEXT PRIMARY KEY,
  display_name TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE IF NOT EXISTS dm_tenant_memberships (
  tenant_id TEXT NOT NULL REFERENCES dm_tenants(id) ON DELETE CASCADE,
  user_id TEXT NOT NULL REFERENCES dm_users(id) ON DELETE CASCADE,
  role TEXT NOT NULL CHECK(role IN ('OWNER','ADMIN','MEMBER')),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  PRIMARY KEY(tenant_id,user_id)
);

CREATE TABLE IF NOT EXISTS dm_tenant_device_access (
  tenant_id TEXT NOT NULL REFERENCES dm_tenants(id) ON DELETE CASCADE,
  canonical_device_id TEXT NOT NULL,
  access_state TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(access_state IN ('ACTIVE','REVOKED')),
  granted_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  PRIMARY KEY(tenant_id,canonical_device_id)
);

CREATE TABLE IF NOT EXISTS dm_gateways (
  id TEXT PRIMARY KEY,
  owner_user_id TEXT NOT NULL REFERENCES dm_users(id),
  display_name TEXT NOT NULL,
  credential_hash TEXT NOT NULL UNIQUE,
  health_state TEXT NOT NULL DEFAULT 'NOT_READY' CHECK(health_state IN ('READY','OFFLINE','NOT_READY','QUARANTINED')),
  accept_pool_jobs INTEGER NOT NULL DEFAULT 0 CHECK(accept_pool_jobs IN (0,1)),
  app_version TEXT,
  sms_permission_ready INTEGER NOT NULL DEFAULT 0,
  telephony_ready INTEGER NOT NULL DEFAULT 0,
  network_ready INTEGER NOT NULL DEFAULT 0,
  heartbeat_at TEXT,
  last_successful_3030_at TEXT,
  quarantined_reason TEXT,
  revoked_at TEXT,
  ready_since TEXT,
  cancellation_cooldown_until TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE IF NOT EXISTS dm_check_batches (
  id TEXT PRIMARY KEY,
  tenant_id TEXT NOT NULL,
  requested_by_user_id TEXT NOT NULL,
  client_idempotency_key TEXT NOT NULL,
  app_edition TEXT NOT NULL DEFAULT 'GATEWAY' CHECK(app_edition='GATEWAY'),
  app_version TEXT,
  status TEXT NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','RUNNING','COMPLETED','PARTIAL','FAILED','CANCELLED')),
  total_jobs INTEGER NOT NULL DEFAULT 0,
  completed_jobs INTEGER NOT NULL DEFAULT 0,
  failed_jobs INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  request_fingerprint TEXT NOT NULL,
  cancellation_requested_at TEXT,
  cancelled_at TEXT,
  cancelled_by_user_id TEXT REFERENCES dm_users(id),
  cancellation_reason TEXT,
  assigned_gateway_id TEXT REFERENCES dm_gateways(id),
  assignment_updated_at TEXT,
  processing_started_at TEXT,
  FOREIGN KEY(tenant_id,requested_by_user_id) REFERENCES dm_tenant_memberships(tenant_id,user_id),
  UNIQUE(tenant_id,requested_by_user_id,client_idempotency_key),
  UNIQUE(id,tenant_id,requested_by_user_id)
);

CREATE TABLE IF NOT EXISTS dm_check_jobs (
  id TEXT PRIMARY KEY,
  batch_id TEXT NOT NULL REFERENCES dm_check_batches(id) ON DELETE CASCADE,
  tenant_id TEXT NOT NULL,
  requested_by_user_id TEXT NOT NULL,
  canonical_device_id TEXT NOT NULL,
  query_profile TEXT NOT NULL,
  preferred_gateway_id TEXT REFERENCES dm_gateways(id),
  state TEXT NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','LEASED','SENDING','AWAITING_REPLY','RETRY_PENDING','COMPLETED','FAILED','CANCELLED','AMBIGUOUS')),
  attempt_count INTEGER NOT NULL DEFAULT 0,
  max_attempts INTEGER NOT NULL DEFAULT 2,
  enqueued_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  active_attempt_id TEXT,
  FOREIGN KEY(tenant_id,requested_by_user_id) REFERENCES dm_tenant_memberships(tenant_id,user_id),
  FOREIGN KEY(tenant_id,canonical_device_id) REFERENCES dm_tenant_device_access(tenant_id,canonical_device_id),
  FOREIGN KEY(batch_id,tenant_id,requested_by_user_id) REFERENCES dm_check_batches(id,tenant_id,requested_by_user_id)
);

CREATE TABLE IF NOT EXISTS dm_job_attempts (
  id TEXT PRIMARY KEY,
  job_id TEXT NOT NULL REFERENCES dm_check_jobs(id) ON DELETE CASCADE,
  gateway_id TEXT NOT NULL REFERENCES dm_gateways(id),
  attempt_number INTEGER NOT NULL,
  lease_token_hash TEXT NOT NULL UNIQUE,
  state TEXT NOT NULL CHECK(state IN ('LEASED','SENDING','AWAITING_REPLY','COMPLETED','SEND_FAILED','TIMED_OUT','AMBIGUOUS','REJECTED')),
  leased_at TEXT NOT NULL,
  lease_expires_at TEXT NOT NULL,
  send_started_at TEXT,
  sms_sent_at TEXT,
  reply_deadline_at TEXT,
  reply_received_at TEXT,
  completed_at TEXT,
  failure_code TEXT,
  created_at TEXT NOT NULL,
  UNIQUE(job_id,attempt_number)
);

CREATE TABLE IF NOT EXISTS dm_daily_sms_results (
  id TEXT PRIMARY KEY,
  tenant_id TEXT NOT NULL REFERENCES dm_tenants(id),
  canonical_device_id TEXT NOT NULL,
  business_date TEXT NOT NULL,
  checked_at TEXT NOT NULL,
  received_at TEXT NOT NULL,
  values_json TEXT NOT NULL,
  gateway_id TEXT NOT NULL REFERENCES dm_gateways(id),
  job_id TEXT NOT NULL REFERENCES dm_check_jobs(id),
  attempt_id TEXT NOT NULL UNIQUE REFERENCES dm_job_attempts(id),
  parser_version TEXT NOT NULL,
  created_at TEXT NOT NULL,
  UNIQUE(tenant_id,canonical_device_id,business_date)
);

CREATE TABLE IF NOT EXISTS dm_gateway_attempt_cancellations (
  attempt_id TEXT PRIMARY KEY REFERENCES dm_job_attempts(id),
  job_id TEXT NOT NULL REFERENCES dm_check_jobs(id),
  gateway_id TEXT NOT NULL REFERENCES dm_gateways(id),
  outcome TEXT NOT NULL CHECK(outcome IN ('CANCELLED_BEFORE_SEND','CANCELLED_AFTER_SEND','BLOCKED_QUARANTINED')),
  reason TEXT,
  cooldown_until TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS dm_gateway_audit_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id TEXT,
  actor_user_id TEXT,
  gateway_id TEXT,
  job_id TEXT,
  event_type TEXT NOT NULL,
  details TEXT NOT NULL DEFAULT '{}',
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS dm_gateway_health_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  gateway_id TEXT NOT NULL REFERENCES dm_gateways(id) ON DELETE CASCADE,
  previous_state TEXT,
  new_state TEXT NOT NULL,
  event_at TEXT NOT NULL,
  app_version TEXT
);

CREATE TABLE IF NOT EXISTS dm_tenant_queue_snapshots (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id TEXT NOT NULL REFERENCES dm_tenants(id) ON DELETE CASCADE,
  captured_at TEXT NOT NULL,
  snapshot_minute TEXT NOT NULL,
  pending_devices INTEGER NOT NULL DEFAULT 0,
  waiting_devices INTEGER NOT NULL DEFAULT 0,
  active_devices INTEGER NOT NULL DEFAULT 0,
  waiting_requests INTEGER NOT NULL DEFAULT 0,
  active_requests INTEGER NOT NULL DEFAULT 0,
  ready_gateways INTEGER NOT NULL DEFAULT 0,
  busy_gateways INTEGER NOT NULL DEFAULT 0,
  UNIQUE(tenant_id,snapshot_minute)
);

CREATE TABLE IF NOT EXISTS dm_controller_operators (
  user_id TEXT PRIMARY KEY REFERENCES dm_users(id) ON DELETE CASCADE,
  active INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS dm_tenant_dispatch_state (
  tenant_id TEXT PRIMARY KEY REFERENCES dm_tenants(id) ON DELETE CASCADE,
  last_served_at TEXT,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS dm_requester_dispatch_state (
  tenant_id TEXT NOT NULL,
  requested_by_user_id TEXT NOT NULL,
  last_served_at TEXT,
  updated_at TEXT NOT NULL,
  PRIMARY KEY(tenant_id,requested_by_user_id),
  FOREIGN KEY(tenant_id,requested_by_user_id) REFERENCES dm_tenant_memberships(tenant_id,user_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_gateway_owner ON dm_gateways(owner_user_id,created_at);
CREATE INDEX IF NOT EXISTS idx_gateway_ready ON dm_gateways(health_state,accept_pool_jobs,heartbeat_at);
CREATE INDEX IF NOT EXISTS idx_gateway_cooldown ON dm_gateways(cancellation_cooldown_until);
CREATE INDEX IF NOT EXISTS idx_batch_tenant_user ON dm_check_batches(tenant_id,requested_by_user_id,created_at);
CREATE INDEX IF NOT EXISTS idx_batch_gateway_queue ON dm_check_batches(assigned_gateway_id,status,created_at);
CREATE INDEX IF NOT EXISTS idx_jobs_batch ON dm_check_jobs(batch_id,enqueued_at);
CREATE INDEX IF NOT EXISTS idx_jobs_tenant_queue ON dm_check_jobs(tenant_id,state,enqueued_at);
CREATE INDEX IF NOT EXISTS idx_attempt_gateway_state ON dm_job_attempts(gateway_id,state,lease_expires_at);
CREATE INDEX IF NOT EXISTS idx_attempt_job ON dm_job_attempts(job_id,attempt_number);
CREATE INDEX IF NOT EXISTS idx_daily_result_lookup ON dm_daily_sms_results(tenant_id,canonical_device_id,business_date);
CREATE INDEX IF NOT EXISTS idx_health_gateway_time ON dm_gateway_health_events(gateway_id,event_at);
CREATE INDEX IF NOT EXISTS idx_snapshots_tenant_time ON dm_tenant_queue_snapshots(tenant_id,captured_at);

-- Mirror the PostgreSQL active-device-entitlement trigger at the D1 boundary.
CREATE TRIGGER IF NOT EXISTS dm_check_job_active_device_access_insert
BEFORE INSERT ON dm_check_jobs
WHEN NOT EXISTS (
  SELECT 1 FROM dm_tenant_device_access
  WHERE tenant_id=NEW.tenant_id
    AND canonical_device_id=NEW.canonical_device_id
    AND access_state='ACTIVE'
)
BEGIN
  SELECT RAISE(ABORT,'device is not actively authorized for this tenant');
END;

CREATE TRIGGER IF NOT EXISTS dm_check_job_active_device_access_update
BEFORE UPDATE OF tenant_id,canonical_device_id ON dm_check_jobs
WHEN (OLD.tenant_id<>NEW.tenant_id OR OLD.canonical_device_id<>NEW.canonical_device_id)
 AND NOT EXISTS (
  SELECT 1 FROM dm_tenant_device_access
  WHERE tenant_id=NEW.tenant_id
    AND canonical_device_id=NEW.canonical_device_id
    AND access_state='ACTIVE'
 )
BEGIN
  SELECT RAISE(ABORT,'device is not actively authorized for this tenant');
END;
