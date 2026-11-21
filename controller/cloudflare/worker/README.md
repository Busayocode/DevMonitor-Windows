# DevMonitor Gateway 5.0 — Cloudflare backend

This branch contains the isolated Cloudflare implementation of the existing Gateway controller contract. It does not change the Android application or the original FastAPI/PostgreSQL backend.

## What is implemented

- Firebase Authentication ID-token verification against the configured Firebase project, issuer, audience, expiry and required claims.
- Random 256-bit Gateway installation credentials; only SHA-256 digests are stored.
- Tenant membership and active device-grant checks.
- Idempotent client batch creation, whole-batch Gateway assignment, queue visibility and cancellation.
- Personal Gateway registration, listing, pool participation, revocation and heartbeat health transitions.
- Single-active-attempt Gateway claiming with a 120-second lease.
- Standard reply timeout 120 seconds; Partner reply timeout 180 seconds.
- Exact attempt/lease-token checks for send-start, SMS_SENT acknowledgement, result, cancellation, ambiguous outcome and definitive pre-send failure.
- Durable four-minute cooldown, created only when an exact AWAITING_REPLY attempt has persisted SMS_SENT evidence. Every claim checks the saved deadline.
- SENDING/AMBIGUOUS cancellation or expiry quarantines the Gateway. Such attempts are never automatically retried.
- Daily result cache with two-month retention; scheduled queue snapshots with 30-day retention.
- Bounded scheduled recovery once per minute. This replaces the old infinite 15-second/60-second loops; safety-critical deadlines are checked synchronously by API operations.
- Tenant-admin analytics and controller-operator actions for explicit ambiguous-attempt resolution and Gateway quarantine release.

## Safety rules preserved

1. Android remains the only SMS sender. The backend never sends an SMS.
2. Cancellation before send is only safe for LEASED with no send-start or SMS_SENT evidence.
3. The four-minute cooldown applies only after confirmed SMS_SENT and cancellation of that exact attempt.
4. No additional post-reply quiet period: a valid completed reply makes the next claim eligible immediately.
5. SENDING, AWAITING_REPLY timeout, and AMBIGUOUS outcomes are quarantined rather than blindly retried.
6. The backend never exposes a stored lease token through active-attempt recovery.
7. A result must match the authenticated Gateway, exact job, exact attempt and hashed lease token.

## Important implementation differences from PostgreSQL

The existing PostgreSQL controller has row-locked PL/pgSQL procedures. D1 uses SQLite transactions through atomic D1Database.batch() calls and conditional updates. The implementation is a port, not a SQL migration. Concurrency, idempotency and transition tests are required before production traffic is switched.

D1 is single-threaded per database. The scheduler assigns whole batches and each Gateway has at most one active SMS attempt. If usage grows beyond free D1 limits or query throughput, reassess before scaling; do not silently upgrade to a paid plan.

Scheduled recovery runs once per minute, not every 15 seconds. Claim/cooldown enforcement does not depend on Cron. A missed Cron run may delay stale status/queue maintenance, but must not release uncertain SMS attempts.

## Local validation

The GitHub Actions workflow performs npm dependency installation, strict TypeScript checking, safety invariant tests, local D1 schema initialization/validation and a Wrangler Worker dry-run build.

Workflow: .github/workflows/gateway-cloudflare-runtime.yml.

## Cloudflare account setup — after CI is green

No bank card, domain purchase, Oracle account, VPS, paid service or SMS provider is required for this code path.

1. Create a free Cloudflare account.
2. In Workers & Pages → D1 SQL Database, create a database named devmonitor-gateway.
3. Copy its database ID and replace the all-zero placeholder in wrangler.jsonc.
4. In the D1 Console, apply schema.sql once to the newly created empty database. Never reapply it to a populated production database as a reset.
5. In Workers & Pages, connect the repository using Cloudflare Workers Builds. Set the project root to controller/cloudflare/worker, the build command to npm run build and the deploy command to npm run deploy. Select the gateway-5.0-cloudflare-adaptation branch.
6. Set Worker variable FIREBASE_PROJECT_ID to the exact Firebase Project ID (the existing Fieldpulse Firebase project is intended; verify the exact ID in Firebase Project settings).
7. Keep the default workers.dev HTTPS hostname. No custom domain or DuckDNS is needed.
8. Verify GET /health, Firebase user authentication, Gateway registration, heartbeat, batch create/read, claim, cancellation and result flows before building an Android app configured with this URL.

Do not copy Firebase service-account private keys into this Worker. ID-token verification uses Google's public signing keys. Do not put Gateway bearer credentials in source control.

## Tenant provisioning

The API intentionally does not allow arbitrary callers to create tenants or grant device access. Tenant records, OWNER membership and approved device grants must be provisioned by an administrator using the D1 Console. Use the template in bootstrap-tenant.example.sql, replacing every placeholder with verified values. The Firebase user must sign in once so their UID is present in dm_users. Use the original tenant/device authorization source of truth; do not invent grants merely to make a test pass. The authenticated Firebase UID is stored as dm_users.auth_subject on first authenticated request.

Controller operator access is a separate global privilege. Grant it only to a trusted administrator by inserting their verified user row into dm_controller_operators. The operator endpoints are POST /v1/operator/attempts/{attempt_id}/resolve and POST /v1/operator/gateways/{gateway_id}/release-quarantine. Ambiguous attempts must be explicitly resolved before a quarantined Gateway can be released.

## Not yet production-verified

- Cloudflare account and D1 database have not been provisioned.
- No Worker has been deployed and no public endpoint is active.
- GitHub Actions passed at commit f523c0dd: TypeScript validation, safety tests, local D1 schema validation and Wrangler dry-run build.
- Real Firebase tokens, D1 concurrent-claim races, Cron execution, limits and restart/recovery behaviour still require integration testing.
- Android is not pointed at this endpoint; outbound SMS remains disabled pending controlled physical Glo/3030 tests.

The original PostgreSQL backend branch remains the reference/fallback until parity tests pass. Do not merge this branch into the Android or main application branch without an explicit review.
