# Gateway Controller HTTP Service

This is the reference FastAPI service for the DevMonitor 5.0 controller. It is source code only; it is not deployed or connected to a live database.

## Local run

1. Create a dedicated PostgreSQL database.
2. Apply the migrations in `../postgres/` in numeric order, V001 through V008.
3. Copy `.env.example` to a private environment file and set:
   - `DATABASE_URL` to the controller database.
   - `OIDC_ISSUER`, `OIDC_AUDIENCE` and `OIDC_JWKS_URL` to the selected identity provider.
4. Install `requirements.txt`.
5. Start with `uvicorn app:app --app-dir controller/service --host 127.0.0.1 --port 8000`.

Do not expose the development server directly to the public internet. A deployment must terminate HTTPS at a trusted reverse proxy/load balancer, restrict database access, configure rate limits and protect operational endpoints.

## Authentication

- User routes require a JWT whose signature, issuer, audience, expiry and subject are verified against the configured OIDC JWKS.
- Gateway routes require the random 256-bit installation token returned once by personal gateway registration. Only its SHA-256 hash is stored in PostgreSQL.
- A revoked gateway token is rejected even if that phone continues to heartbeat.
- There is no development bypass or unauthenticated mode for client/gateway routes. `/health` exposes only basic service readiness.

The identity provider has deliberately not been selected or provisioned. The service accepts a generic OIDC configuration; it does not assume that the existing DevMonitor Firebase project has Firebase Authentication enabled.

## Runtime behaviour

- Client batch requests are transactional and idempotent. The same key and same request fingerprint returns the original batch; the same key with a different payload returns HTTP 409.
- Tenant membership and active device grants are checked before enqueue and before cache/history reads.
- The caller's personal gateway can be selected for priority work. The server checks that the gateway belongs to the authenticated requester.
- Gateway phones claim one job at a time and submit state transitions against a lease token.
- The service maps query profiles containing `partner` to the existing 180-second Partner wait; other standard profiles use 120 seconds.
- A background reaper runs every 15 seconds by default. Pre-send expired leases may be safely requeued; possible-send expiries become AMBIGUOUS and quarantine the phone.
- Batch status is derived from its per-device job states so ambiguous work remains visibly RUNNING until operator resolution.
- The server never returns a lease token from the active-attempt recovery endpoint. Gateway installations must persist it securely; if lost, the attempt must be treated as ambiguous.

## Not production-ready yet

The code still needs real identity-provider integration tests, explicit PostgreSQL runtime-role grants, rate limiting, structured security/audit monitoring, deployment secrets management, cross-process contention/load tests, restart recovery tests and a live gateway-phone test. No paid service or cloud host has been selected.
