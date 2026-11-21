# DevMonitor Gateway 5.0 — Cloudflare adaptation track

Status: **architecture assessment started; not deployed; no production traffic switched**.

This branch is isolated from the existing PostgreSQL backend and Android branch. The original FastAPI/PostgreSQL implementation remains the reference and fallback.

## Why a direct upload will not work

The existing controller is not just a thin HTTP API:

- `controller/service/app.py` is a 49 KB FastAPI application using `psycopg` and `psycopg_pool`.
- PostgreSQL migrations V001–V014 define the data model and database-enforced behavior. The migrations currently contain 16 tables, 44 PL/pgSQL function definitions, and 9 triggers (counts are based on CREATE statements in the migration files; later migrations can replace earlier functions).
- Critical correctness depends on PostgreSQL row locks, transactions, constraints, and stored functions. Replacing PostgreSQL with D1 is a database and transaction redesign, not a connection-string change.
- The current service starts two infinite asyncio loops: lease/job recovery (default 15 seconds) and queue snapshots (default 60 seconds). A Worker cannot host those loops indefinitely.

## Cloudflare facts to design against

- Python Workers support FastAPI through Cloudflare's ASGI entrypoint.
- Workers Free currently allows 100,000 requests/day and 10 ms CPU per HTTP request. Cron Trigger invocations also have a 10 ms CPU allowance on Free.
- D1 Free currently allows 500 MB per database, 5 GB aggregate storage, and 7 days of Time Travel.
- Cron Triggers are scheduled invocations, not a permanently running process. They cannot reproduce the current 15-second loop cadence as-is.

These limits must be checked again before deployment because provider limits can change.

## Proposed implementation sequence

1. **Inventory and freeze contracts.** Record every route, SQL query/function dependency, state transition, and response shape from the current controller. Keep the existing PostgreSQL implementation unchanged.
2. **Cloudflare runtime proof.** Build a minimal Python Worker using the supported FastAPI ASGI entrypoint. Prove local build, health endpoint, Firebase token verification compatibility (the current PyJWT cryptography extra must be checked against the Python Workers package runtime; if unsupported, use the Workers WebCrypto API without weakening token validation), and deployed HTTPS hostname before porting business logic.
3. **Persistence/transaction proof.** Build a D1 schema prototype and prove atomic, concurrent operations for:
   - exact gateway/attempt cancellation;
   - the four-minute deadline, created only after confirmed SMS_SENT;
   - claim rejection until the persisted deadline expires;
   - quarantine of SENDING/AMBIGUOUS attempts;
   - duplicate and delayed-reply protection.
4. **Port the remaining API.** Reimplement Postgres functions as explicit application transactions/conditional SQL. Preserve endpoint paths, JSON contracts, authentication, tenant isolation, scheduler fairness, gateway health, and audit records.
5. **Scheduled recovery.** Replace infinite loops with idempotent, bounded scheduled passes. A missed or delayed pass must not weaken claim/cancel safety; deadlines remain enforced synchronously on every claim.
6. **Parity tests.** Run the same behavior scenarios against PostgreSQL and the Cloudflare implementation. Include concurrent claim/cancel races, restart/retry behavior, duplicate replies, delayed replies, cooldown expiry, expired leases, failover, and tenant authorization.
7. **Only then connect Android.** Configure the production Worker URL in a new Android build. Keep SMS dispatch disabled until authenticated registration, heartbeat, claim, cancel, and controlled Glo/3030 tests pass.

## Non-negotiable safety invariants

- Android remains the only SMS sender. The backend never sends SMS.
- The four-minute cooldown is a persisted timestamp, not a sleeping task or in-memory timer.
- Start cooldown only for cancellation of the exact active attempt after positive Android SMS_SENT evidence.
- A new claim must be rejected while the persisted deadline is in the future.
- A valid correlated reply permits the next query immediately; there is no added post-reply quiet period.
- Uncertain SENDING/AMBIGUOUS outcomes are quarantined and never freed for reuse merely because a timer elapsed.
- Never treat a delayed/unmatched 3030 reply as belonging to a new attempt.
- No paid plan, paid resource, billing upgrade, additional SMS provider, or live-SMS enablement without explicit approval.

## Current implementation status

- [x] Isolated Cloudflare adaptation branch created from `gateway-controller-cancel-cooldown-v1`.
- [x] Existing PostgreSQL controller and V001–V014 migration inventory inspected.
- [ ] FastAPI Python Worker skeleton and local build.
- [ ] D1 schema and atomic cancellation/claim prototype.
- [ ] Recovery scheduling prototype.
- [ ] Automated parity tests.
- [ ] Cloudflare account/Worker deployment.
- [ ] Android endpoint configuration and physical SMS tests.

**Important:** This track is an engineering prototype until every safety test passes. Do not point the existing Android APK at it or enable outbound SMS before the checklist is complete.
