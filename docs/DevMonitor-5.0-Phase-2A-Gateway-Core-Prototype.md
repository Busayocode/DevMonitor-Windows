# Gateway Core Prototype — Phase 2A

**Branch:** `devmonitor-5.0-foundation`  
**Status:** Isolated Kotlin prototype; smoke-tested, not integrated into either release edition.  
**Source of truth:** `prototypes/gateway-core/src/main/kotlin/com/aziitech/devmonitor/gateway/GatewayCore.kt`

## Implemented prototype behaviours

- One daily record per authorized scope + canonical device ID + business date.
- Each record preserves the checked-at and gateway-received timestamps separately.
- A later accepted check replaces the current daily value; an older delayed result cannot overwrite a newer checked-at value.
- Cached latest and date-range history reads do not require the requesting client to have initiated the original check. A production API must still enforce the caller's server-side device entitlement.
- Fresh jobs enter one shared FIFO queue and are claimed by ready, idle gateway workers.
- Each gateway has at most one active lease in this prototype.
- Result acceptance verifies gateway, lease, attempt, access scope and canonical device identity.
- An ambiguous timeout quarantines only that gateway. Its job is not silently requeued, and other healthy workers remain eligible.
- Idempotency keys prevent a retried client submission from creating a second job in the same access scope.

## Validation performed

A dependency-free Kotlin smoke test was compiled and executed with the Kotlin compiler. It exercises:
1. Two workers claiming separate queued jobs.
2. A successful result entering the daily cache.
3. A timed-out worker becoming quarantined.
4. A quarantined worker being unable to claim new work.
5. A delayed older result not replacing the latest daily value.
6. An idempotent resubmission returning the original job.

**Result: PASS.**

## Explicit limitations — not production-ready

- `InMemoryDailyResultStore` is volatile and is only a test adapter. A production implementation must use durable storage and atomic upserts.
- `GatewayJobCoordinator` is a single-process reference implementation, not a distributed server. It does not provide cross-process locking, durable leases, authentication, authorization, tenant isolation, abuse controls, or server-side rate limits.
- The FIFO scheduler is a first prototype. Per-tenant fairness, duplicate-request coalescing and priority policy remain to be designed and tested.
- Cached reads are scoped by an access-scope key in this prototype. Production authorization must derive scope from the authenticated principal, never trust a client-supplied scope string.
- No SMS is sent. No Android SMS permission or manifest has been changed.
- No Firebase project, Cloud Function, database rules, hosting service or paid infrastructure has been provisioned.
- No Full or Gateway APK has been built from this prototype.

## Next implementation step

Implement a durable controller adapter and authenticated API around these interfaces, then connect a separate gateway-phone proof of concept. Before live SMS testing, preserve the confirmed 3030 correlation rule: one unresolved transaction per physical phone/SIM, and quarantine after ambiguous timeout. Keep the Full edition unchanged.
