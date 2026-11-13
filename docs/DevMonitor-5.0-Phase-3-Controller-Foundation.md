# DevMonitor 5.0 — Phase 3 Controller Foundation

**Branch:** `devmonitor-5.0-foundation`  
**Status:** PostgreSQL schema, multi-Gateway request scheduler, queue/cancellation/analytics API and reference FastAPI service committed on the foundation branch; CI validation passed (controller workflow run #43511984083); not deployed.  
**Production impact:** None. No release ZIP, Full edition, Gateway client, main branch, Firebase project or live SMS workflow was changed.

## Objective

Move the approved gateway design from an in-memory scheduler toward a durable multi-user controller. The controller must retain queue state and cached results across restart, assign jobs transactionally, authenticate gateway installations, enforce personal gateway ownership and preserve the 3030 reply-correlation safety rule.

## What has been added

### 1. Durable data model

The PostgreSQL migrations define:
- User identities, tenants and tenant memberships.
- Personal gateway registrations with hashed installation credentials.
- Owner-controlled shared-pool opt-in/out.
- Active per-tenant device grants.
- Idempotent check batches and per-device jobs.
- Individual gateway attempts with hashed lease tokens and lifecycle timestamps.
- Daily SMS result cache, unique per tenant/device/business date.
- Batch-to-job tenant/requester integrity and request fingerprints for idempotency replay validation.
- Revocable personal gateway credentials and explicit controller-operator authorization.
- Tenant and per-requester fair-queue cursors and an audit trail.

### 2. Atomic scheduling

The database claim function:
- Locks the gateway record before assigning work.
- Refuses claims from stale, unready or quarantined gateways.
- Enforces one unresolved SMS attempt per physical gateway.
- Checks personal owner work before shared-pool work.
- Keeps owner-preferred jobs reserved while their personal gateway is healthy.
- V008 introduced two-level per-device round-robin state. V010 supersedes that dispatch policy with request-level FIFO batches distributed across healthy eligible Gateways; the older cursor tables remain for migration compatibility but are no longer used by the active claim function.
- Locks selected queue rows so two gateways cannot claim the same job.

### 3. Safe failure handling

- A lease that expires before the gateway acknowledges SENDING may be requeued.
- Once SENDING has been acknowledged, an expired attempt is treated as ambiguous.
- Ambiguous attempts quarantine their gateway and block further work.
- An operator must explicitly resolve the ambiguous attempt after verifying the phone's inbox/transaction is safe.
- A late result must still match the exact gateway, job, attempt, lease token, tenant and device.
- Daily cache upsert cannot replace a newer check with an older delayed result.

### 4. Existing SMS wait profiles

The controller accepts the query-profile timeout selected by the server:
- Standard Device Monitor wait: 120 seconds.
- Partner menu wait: 180 seconds.

A small transport grace period is added to the server lease. This does not change the user-visible SMS wait setting or existing retry policy.

### 5. Multi-Gateway request queue and analytics (V009–V013)

- One logical shared request queue is distributed across all eligible healthy Gateways. Whole request batches are allocated in FIFO order; different Gateways process requests concurrently.
- A started batch remains pinned to its Gateway while it remains healthy. If that Gateway goes offline, its remaining unstarted device jobs can fail over after any live send is safely resolved.
- Gateway load ties preserve the longer-ready Gateway as the first queue, while a Gateway already carrying active request work yields the next pending batch to a less-loaded Gateway.
- A 90-second missed-heartbeat threshold marks a READY Gateway OFFLINE. Health transitions are audited and feed observed uptime analytics.
- Queue snapshots expose devices and requests ahead on the assigned Gateway, global pending-device count, and approximate start/completion estimates based on recent Gateway reply times with 120-second Standard / 180-second Partner fallback profiles.
- Users can cancel their own request. Queued and pre-send leased jobs are stopped; already-sent or ambiguous attempts remain protected until safely resolved. Completed results are retained.
- Tenant OWNER/ADMIN analytics include request/device totals, cancellation/success/timeout rates, median queue wait and reply times, current Gateway health/utilization, observed uptime and sampled 30-day queue-depth history. Queue snapshots are sampled once per tenant per minute and retained for 90 days.
- New endpoints: `GET /v1/client/check-batches/{batch_id}/queue`, `POST /v1/client/check-batches/{batch_id}/cancel`, and `GET /v1/client/tenants/{tenant_id}/gateway-analytics`.

### 6. HTTP contract

The OpenAPI contract and reference handlers cover:
- Client batch submission and progress retrieval.
- Latest cached result and history retrieval.
- Personal gateway registration and owner-controlled pool participation.
- Gateway health heartbeat, active-attempt recovery metadata, job claim, send-state acknowledgement, definitive pre-transmission failure and result submission.

## Security requirements

- User identity comes from a Firebase Authentication ID token verified against the configured Firebase project (RS256 signature, exact project audience, issuer, expiry, issued-at time, authentication time and UID); internal user and tenant IDs are resolved server-side.
- The Firebase project ID is deployment configuration, not a client-supplied claim. Google OAuth access tokens and Google Drive tokens are not accepted as user authentication.
- Gateway identity comes from a verified random installation credential; the raw token is returned once and only its hash is stored.
- A personal gateway can be selected only by its owner.
- Tenant membership and active device grants are enforced before enqueue and cache/history reads.
- A shared-pool gateway receives only the one job it has claimed, not another tenant's unrelated device list or cached history.
- Quarantine release and ambiguous attempt resolution are privileged operator operations.
- No credentials or secrets belong in the source ZIP, APK, logs or Git history.

## What is not yet implemented

- No deployed HTTPS service. The reference FastAPI handlers are source-only and still require environment configuration, deployment hardening and integration tests.
- Firebase Authentication is selected, and the FastAPI service now verifies Firebase ID tokens. The existing `Fieldpulse` Firebase project still needs its Authentication feature and sign-in provider checked/enabled in Firebase Console; no project settings have been changed yet.
- No PostgreSQL instance, hosting provider, cloud project or paid service has been provisioned.
- GitHub Actions run [#10](https://github.com/Busayocode/DevMonitor-Windows/actions/runs/43427348246) successfully applied V001–V006 to PostgreSQL 16 and passed the first database smoke suite. The suite exercised personal gateway priority, reservation from pool workers, tenant round-robin, result persistence, safe pre-send requeue, post-send ambiguity/quarantine and operator recovery. A follow-up stale-cache test is now being added.
- Cross-process contention/load tests, identity-provider tests, full restart recovery and live gateway-phone tests have not yet run.
- No Android Gateway Phone App or Gateway Edition client is connected to the controller. The Full edition has not yet been wired to select/use a dedicated Gateway or display queue position, estimates and cancellation controls.
- No SMS has been sent.
- No production permission, manifest or build changes have been made.

## Required validation before phone integration

1. Apply V001–V013 to a clean PostgreSQL instance and verify migration success.
2. Test two or more workers concurrently claiming the same tenant queue; no duplicate job claim may occur.
3. Test request-level FIFO ordering and round-robin distribution of whole request batches across multiple Gateways.
4. Test personal owner priority, personal-only mode, pool opt-in/out and request-level Gateway reassignment.
5. Test a preferred Gateway going offline, stale-heartbeat detection and safe shared-pool failover.
6. Test lease expiry before send versus after send.
7. Test late replies, stale lease tokens, duplicate result submissions and delayed older results.
8. Restart the controller between each important lifecycle stage and verify durable recovery.
9. Test tenant/device authorization and cross-tenant cache isolation.
10. Verify standard 120-second and Partner 180-second wait profiles without changing existing SMS retry semantics.

## Next stage

Production-harden the reference HTTPS service: confirm Firebase Authentication and the chosen sign-in provider are enabled in the Fieldpulse project, configure the exact Firebase project ID and run Firebase ID-token integration tests, create least-privilege PostgreSQL roles, add rate limits and operational monitoring, run cross-process concurrency and restart-recovery tests, then deploy to a selected host. Only after those gates pass should a test gateway phone be registered. Backend provider and operating cost must be reviewed and explicitly approved before provisioning.
