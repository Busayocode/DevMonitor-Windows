# Gateway Controller HTTP Service

This is the reference FastAPI service for the DevMonitor 5.0 controller. It is source code only; it is not deployed or connected to a live database.

## Local run

1. Create a dedicated PostgreSQL database.
2. Apply the migrations in `../postgres/` in numeric order, V001 through V013.
3. Copy `.env.example` to a private environment file and set:
   - `DATABASE_URL` to the controller database.
   - `FIREBASE_PROJECT_ID` to the exact Project ID shown in Firebase Console > Project settings > General.
4. Install `requirements.txt`.
5. Start with `uvicorn app:app --app-dir controller/service --host 127.0.0.1 --port 8000`.

Do not expose the development server directly to the public internet. A deployment must terminate HTTPS at a trusted reverse proxy/load balancer, restrict database access, configure rate limits and protect operational endpoints.

## Authentication

- User routes require a Firebase Authentication ID token. The controller verifies its RS256 signature using Google's Secure Token public keys and checks the exact Firebase project ID as audience, the matching `https://securetoken.google.com/<projectId>` issuer, expiry, issued-at time, authentication time and UID.
- The Firebase project must have Firebase Authentication enabled and at least one sign-in provider configured. The Android client signs in with Firebase and sends its current ID token as the bearer token. The controller does not accept Google OAuth access tokens or Drive tokens as substitutes.
- Gateway routes require the random 256-bit installation token returned once by personal gateway registration. Only its SHA-256 hash is stored in PostgreSQL.
- A revoked gateway token is rejected even if that phone continues to heartbeat.
- There is no development bypass or unauthenticated mode for client/gateway routes. `/health` exposes only basic service readiness.

Firebase Authentication is the selected identity provider. The existing `Fieldpulse` project is the intended project, but its Firebase Authentication status and sign-in providers still need to be checked in Firebase Console. No Firebase project settings have been changed by this source-code update. The project ID must be supplied through deployment configuration; no service-account private key is needed for this ID-token signature verification path.

## Runtime behaviour

- Client batch requests are transactional and idempotent. The same key and same request fingerprint returns the original batch; the same key with a different payload returns HTTP 409.
- Tenant membership and active device grants are checked before enqueue and before cache/history reads.
- The caller's personal gateway can be selected for priority work. The server checks that the gateway belongs to the authenticated requester.
- The controller assigns whole request batches across healthy, eligible Gateways in arrival order. When a Gateway becomes ready or leaves the pool, unstarted batches are redistributed; a started batch stays pinned to its Gateway while that Gateway remains healthy. If it goes offline, the remaining devices can fail over after any live/ambiguous attempt is safely resolved. Each Gateway processes one SMS attempt at a time.
- Queue snapshots show the request's assigned Gateway, requests/devices ahead on that Gateway, global pending-device count and approximate start/completion estimates. Estimates use the Gateway's recent median reply time, falling back to the existing 120-second Standard or 180-second Partner wait profile.
- Users can cancel their own batch. Queued and pre-send leased jobs are stopped; an SMS already in progress or ambiguous is not forcibly interrupted. Completed results remain available.
- Tenant OWNER/ADMIN users can read aggregate Gateway analytics for their tenant. The current API reports request/device totals, cancellation/timeout/success rates, median queue/reply times, current Gateway health, observed uptime and 30-day queue-depth/daily activity.
- The service maps query profiles containing `partner` to the existing 180-second Partner wait; other standard profiles use 120 seconds.
- A background reaper runs every 15 seconds by default. It marks stale READY Gateways OFFLINE after 90 seconds without a heartbeat and redistributes unstarted batches; pre-send expired leases may be safely requeued, while possible-send expiries become AMBIGUOUS and quarantine the phone.
- Batch status is derived from its per-device job states so ambiguous work remains visibly RUNNING until operator resolution.
- The server never returns a lease token from the active-attempt recovery endpoint. Gateway installations must persist it securely; if lost, the attempt must be treated as ambiguous.

## Not production-ready yet

The code still needs real identity-provider integration tests, explicit PostgreSQL runtime-role grants, rate limiting, structured security/audit monitoring, deployment secrets management, cross-process contention/load tests, restart recovery tests, broader queue-rebalance/failover tests and a live gateway-phone test. Queue depth is sampled every minute while the controller is running and exposed as a 30-day trend. Observed Gateway uptime is calculated from recorded health transitions (periods without transition history are excluded); utilization is currently a point-in-time busy/ready ratio. No paid service or cloud host has been selected.
