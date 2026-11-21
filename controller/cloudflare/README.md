# DevMonitor Gateway 5.0 — Cloudflare backend

Status: **backend implementation committed; GitHub validation has passed on earlier revisions; final current revision is awaiting its latest CI run. Not deployed. No production traffic switched.**

This branch is isolated from the Android branch and the existing FastAPI/PostgreSQL controller. The PostgreSQL implementation remains unchanged as the reference and fallback.

## Why the backend was ported rather than uploaded unchanged

The existing FastAPI controller depends on PostgreSQL-specific transactions, row locks, PL/pgSQL functions and two infinite asyncio loops. Cloudflare Workers cannot host those loops indefinitely, and D1 is SQLite rather than PostgreSQL.

The Cloudflare implementation is therefore a TypeScript Worker using D1 and explicit conditional SQL transactions. Firebase ID tokens are verified using Google's public signing keys and the jose library. This avoids relying on Python native cryptography packages or a permanently running Python server.

## Implemented

The Worker under controller/cloudflare/worker includes:

- Firebase ID-token validation for the configured project, issuer, audience, expiry, subject and auth_time.
- Gateway registration with random credentials; only SHA-256 credential digests are stored.
- Tenant membership and approved device-grant enforcement.
- Idempotent check-batch creation, whole-batch assignment, queue status, queue estimates and batch cancellation.
- Personal Gateway listing, pool participation, revocation and health heartbeats.
- Requester- and tenant-aware fair dispatch.
- Atomic single-active-attempt claiming and exact Gateway/job/attempt/lease-token state transitions.
- Standard 120-second and Partner 180-second reply deadlines.
- Daily SMS result cache, latest result, two-month history retention and tenant analytics.
- Four-minute cancellation cooldown, persisted in D1 and enforced at every claim.
- Safe pre-send retry only; uncertain SENDING/AWAITING_REPLY/AMBIGUOUS attempts are quarantined.
- Explicit controller-operator routes for resolving ambiguous attempts and releasing a Gateway only after all ambiguous attempts are resolved.
- Bounded minute-based scheduled recovery, stale-Gateway transitions, expired-lease processing and queue snapshots.
- Audit and Gateway health-transition records.

## Safety invariants

- Android remains the only SMS sender. The backend never sends SMS.
- Cooldown begins only when the exact active attempt has persisted SMS_SENT evidence and is then cancelled.
- Cooldown is a durable four-minute expiry timestamp, not an in-memory timer.
- A valid correlated reply makes the next job immediately eligible; there is no extra post-reply quiet period.
- A definitive LEASED pre-send expiry may be retried. A SENDING or AWAITING_REPLY timeout is ambiguous and quarantined.
- Ambiguous attempts cannot be automatically retried or released. A controller operator must resolve them explicitly.
- A result must match the authenticated Gateway, exact job, exact attempt and lease token.
- Gateway pool opt-out is checked again at claim time.
- No paid plan, paid resource, billing upgrade, additional SMS provider or live-SMS enablement without explicit approval.

## D1 and scheduled-work differences

- D1 uses atomic batch transactions and conditional updates instead of PostgreSQL row-locked procedures. Concurrency parity still needs live D1 integration tests.
- Scheduled recovery runs once per minute, rather than the old 15-second recovery loop. Safety-critical claim and cooldown rules are enforced synchronously and do not depend on Cron.
- Recovery is bounded to keep within the Cloudflare Free CPU budget. A delayed Cron run can delay maintenance, but it must not release uncertain SMS attempts.
- D1 and Workers free-plan quotas apply. If usage exceeds the free limits, stop and review rather than silently enabling a paid plan.

## Validation workflow

GitHub Actions runs strict TypeScript checking, focused safety tests, local D1 schema validation and a Wrangler dry-run build. The workflow does not deploy the Worker.

Workflow: .github/workflows/gateway-cloudflare-runtime.yml.

## Remaining deployment steps

No physical card, domain purchase, Oracle account, VPS or SMS provider is required for this route.

1. Create a free Cloudflare account.
2. Create a D1 database named devmonitor-gateway.
3. Replace the all-zero database ID in controller/cloudflare/worker/wrangler.jsonc with the real D1 database ID.
4. Apply controller/cloudflare/worker/schema.sql once to the new, empty D1 database.
5. Connect the GitHub repository using Cloudflare Workers Builds, root directory controller/cloudflare/worker, build command npm run build, deploy command npm run deploy, and branch gateway-5.0-cloudflare-adaptation.
6. Set the Worker variable FIREBASE_PROJECT_ID to the exact Firebase Project ID. The existing Fieldpulse project is intended, but its exact Project ID must be checked in Firebase settings.
7. Use the free workers.dev HTTPS hostname. No custom hostname or certificate purchase is needed.
8. Provision the existing tenant, OWNER membership and approved device grants using bootstrap-tenant.example.sql. Do not invent or broaden device grants.
9. Grant controller-operator status only to a trusted administrator through the D1 Console.
10. Run deployed D1 integration tests, including concurrent claim/cancel races, cooldown persistence, delayed replies, stale lease recovery, failover, tenant isolation and operator quarantine resolution.

## Not yet production-verified

- The Cloudflare account and D1 database have not been provisioned.
- The Worker has not been deployed and no public endpoint is active.
- The latest GitHub Actions run must pass after the final code changes.
- Real Firebase token verification and concurrent D1 behaviour require deployed integration tests.
- Android has not been configured to use this endpoint.
- Live Glo/3030 SMS tests have not been performed. Outbound SMS remains disabled until all backend and physical-device tests pass.

Do not merge this branch into the Android or main application branch until the deployed backend passes the parity and safety checks.
