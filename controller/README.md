# Gateway Controller Database Foundation

This folder contains the first durable-controller database/API foundation for DevMonitor 5.0 Gateway Edition.

**Status:** Reference PostgreSQL schema and API contract only. No server has been deployed, no database provisioned, and no existing Firebase project or app edition has been changed.

## Files

- `postgres/V001__gateway_controller.sql` — users/tenants, memberships, gateway registry, batches, jobs, attempts, daily cache, tenant fair-queue cursor, audit log, atomic claim, heartbeat, result acceptance and ambiguous-timeout quarantine.
- `postgres/V002__gateway_controller_hardening.sql` — device access grants, personal gateway registration, owner-controlled pool opt-in/out, send-state transitions and conservative expired-lease recovery.
- `postgres/V003__claim_locking_and_ambiguous_resolution.sql` — explicit row locking for cross-gateway claims, active device-entitlement enforcement and audited manual resolution of ambiguous attempts.
- `postgres/V004__profile_specific_reply_timeouts.sql` — preserves the existing 120-second standard wait and 180-second Partner wait as server-selected query-profile values.
- `postgres/V005__operator_authorization.sql` — requires an explicit controller-operator role for ambiguous-attempt resolution and quarantine release.
- `postgres/V006__batch_integrity_and_gateway_revocation.sql` — binds each job to the exact batch tenant/requester, requires a SHA-256 request fingerprint for new batches, and supports owner-authorized gateway revocation.
- `api/openapi.yaml` — proposed HTTPS contract for clients, personal gateway management and gateway workers.

## Database choice

PostgreSQL is the reference relational database for this controller prototype because it provides transactional row locks, partial unique indexes, durable constraints and `SKIP LOCKED` queue claiming. This is a design choice for the schema, not approval to purchase hosting or provision a production database.

The controller database must be separate from the app's existing Firebase/Google Drive data. Drive is not the job queue.

## Migration order

Apply to a fresh, dedicated PostgreSQL database in this exact order:

1. `V001__gateway_controller.sql`
2. `V002__gateway_controller_hardening.sql`
3. `V003__claim_locking_and_ambiguous_resolution.sql`
4. `V004__profile_specific_reply_timeouts.sql`
5. `V005__operator_authorization.sql`
6. `V006__batch_integrity_and_gateway_revocation.sql`

The migrations require PostgreSQL with the `pgcrypto` extension available. Do not apply them to a production database until they have passed integration tests on the selected PostgreSQL version.

## Security boundary

- User JWT validation happens in the HTTPS service. It maps the verified identity-provider subject to `dm_users.id`.
- Gateway bearer credentials are random installation credentials. The database stores only their SHA-256 hashes. The raw token is returned once at registration and must be stored in Android Keystore; never log it.
- The API must resolve a gateway bearer token to a gateway ID before calling database functions. Never accept a gateway ID from a request body as authentication. A revoked gateway credential must be rejected even if the phone later sends a heartbeat.
- Tenant membership and active device grants are checked on every batch creation and cache/history read.
- Personal gateway ownership is enforced by a database trigger as well as API authorization.
- Only the owner's authenticated API can toggle that gateway's pool participation.
- The quarantine-release and ambiguous-attempt-resolution functions are privileged operator operations and must not be exposed through ordinary user or gateway routes.
- Restrict direct table/function access. Runtime API roles should receive only the minimum required execute/select privileges; migration and operator roles remain separate.

## Claim and recovery rules

- A gateway must have a fresh heartbeat, READY state, SMS permission, telephony and network readiness before claiming.
- A gateway is limited to one unresolved 3030 attempt at a time. The partial unique index also blocks claims while an attempt is AMBIGUOUS.
- Owner-preferred jobs are claimed before shared-pool jobs by that owner's paired gateway.
- A healthy preferred gateway reserves its owner's job. Another pool gateway may take it only when the preferred gateway is unavailable.
- Shared jobs rotate across tenant cursors. Each worker claims one job at a time; there are no fixed batch slices.
- The gateway first claims a short lease, then acknowledges SENDING before it attempts the SMS. An expired lease before SENDING can safely return to the queue. Once sending may have happened, expiry becomes AMBIGUOUS and quarantines the gateway.
- A late response is accepted only against the same live attempt, gateway, job, lease token, tenant and device. It cannot complete a later job.
- A completed result updates the daily cache only when its checked_at is newer than the stored value for that tenant/device/business date.
- The reaper function should run periodically from a trusted server worker. It is deliberately conservative.

## Required server transaction for a client batch

The HTTP service must:
1. Validate the user token and resolve the internal user ID.
2. Verify tenant membership and every requested device's ACTIVE grant.
3. If a preferred gateway is supplied, verify that it belongs to the requesting user. Never trust a client-supplied owner ID.
4. Insert or return the idempotent batch using (tenant, requesting user, idempotency key).
5. Insert one job per device in the same database transaction and set the batch total.
6. Commit before returning HTTP 202.

A replay with the same idempotency key must return the original batch only when its canonical request fingerprint matches. A different payload under the same key must be rejected, not silently merged.

## Required server transaction for result retrieval

The API must verify tenant membership and active device access before reading `dm_daily_sms_results`. A result may be shared among authorized members of the same tenant, regardless of which member originally requested the check. A user from another tenant must not learn that a row exists.

## Existing DevMonitor behaviour preserved

- Full edition keeps its native SMS implementation.
- Gateway Edition clients have no native SMS send/receive permissions.
- Gateway phones are separate registered worker installations.
- Standard Device Monitor reply wait remains 120 seconds.
- Partner menu reply wait remains 180 seconds.
- The existing retry-after-first-pass policy and other SMS business rules are not changed by this database schema.
- Daily cache stores the last accepted check per tenant/device/business date and its checked timestamp.
- Existing app history retention remains limited to the two-month window; the API must reject history ranges beyond its configured retention policy.

## Validation status

GitHub Actions run [#10](https://github.com/Busayocode/DevMonitor-Windows/actions/runs/43427348246) successfully applied V001–V006 to PostgreSQL 16 and passed the controller smoke tests for personal priority, pool reservation, tenant fairness, result persistence, pre-send requeue, post-send quarantine and operator recovery. A follow-up test for stale-result protection is being added now. This is an isolated disposable CI database, not a provisioned production database. Cross-process load/concurrency tests, identity-provider tests, restart/recovery tests and a live phone test are still required before any phone connects.

The OpenAPI document is a contract, not an implemented HTTP server. The next implementation step is a small HTTPS service with identity-provider verification, gateway token verification, transactional repository methods, structured logs and health checks. Backend hosting/provider and costs remain unselected.
