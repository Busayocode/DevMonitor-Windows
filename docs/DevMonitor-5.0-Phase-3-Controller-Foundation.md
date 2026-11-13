# DevMonitor 5.0 — Phase 3 Controller Foundation

**Branch:** `devmonitor-5.0-foundation`  
**Status:** PostgreSQL schema, OpenAPI contract and reference FastAPI service committed; CI database-tested, not deployed.  
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
- Uses tenant-level round-robin state for shared jobs.
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

### 5. HTTP contract

The OpenAPI contract and reference handlers cover:
- Client batch submission and progress retrieval.
- Latest cached result and history retrieval.
- Personal gateway registration and owner-controlled pool participation.
- Gateway health heartbeat, active-attempt recovery metadata, job claim, send-state acknowledgement, definitive pre-transmission failure and result submission.

## Security requirements

- User identity comes from a verified user JWT; internal user and tenant IDs are resolved server-side.
- Gateway identity comes from a verified random installation credential; the raw token is returned once and only its hash is stored.
- A personal gateway can be selected only by its owner.
- Tenant membership and active device grants are enforced before enqueue and cache/history reads.
- A shared-pool gateway receives only the one job it has claimed, not another tenant's unrelated device list or cached history.
- Quarantine release and ambiguous attempt resolution are privileged operator operations.
- No credentials or secrets belong in the source ZIP, APK, logs or Git history.

## What is not yet implemented

- No deployed HTTPS service. The reference FastAPI handlers are source-only and still require environment configuration, deployment hardening and integration tests.
- No selected identity provider or production authentication configuration.
- No PostgreSQL instance, hosting provider, cloud project or paid service has been provisioned.
- GitHub Actions run [#10](https://github.com/Busayocode/DevMonitor-Windows/actions/runs/43427348246) successfully applied V001–V006 to PostgreSQL 16 and passed the first database smoke suite. The suite exercised personal gateway priority, reservation from pool workers, tenant round-robin, result persistence, safe pre-send requeue, post-send ambiguity/quarantine and operator recovery. A follow-up stale-cache test is now being added.
- Cross-process contention/load tests, identity-provider tests, full restart recovery and live gateway-phone tests have not yet run.
- No Android Gateway Phone App or Gateway Edition client is connected.
- No SMS has been sent.
- No production permission, manifest or build changes have been made.

## Required validation before phone integration

1. Apply V001–V008 to a clean PostgreSQL instance and verify migration success.
2. Test two or more workers concurrently claiming the same tenant queue; no duplicate job claim may occur.
3. Test tenant fairness with one large batch and one smaller batch.
4. Test personal owner priority, personal-only mode and pool opt-in/out.
5. Test a preferred gateway going offline and safe shared-pool failover.
6. Test lease expiry before send versus after send.
7. Test late replies, stale lease tokens, duplicate result submissions and delayed older results.
8. Restart the controller between each important lifecycle stage and verify durable recovery.
9. Test tenant/device authorization and cross-tenant cache isolation.
10. Verify standard 120-second and Partner 180-second wait profiles without changing existing SMS retry semantics.

## Next stage

Implement the HTTPS controller service against this contract and database, with identity verification and transactional repository methods. Only after database integration tests pass should a test gateway phone be registered. Backend provider and operating cost must be reviewed before provisioning production hosting.
