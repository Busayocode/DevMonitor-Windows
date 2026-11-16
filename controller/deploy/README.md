# Gateway Controller deployment starter

This is a **self-hosted Docker Compose starter**, not a deployed service and not a production hosting decision. It gives the controller a dedicated PostgreSQL 16 database, applies the ordered V001–V014 migrations on first database creation, creates a separate runtime API role, and binds the HTTP service to loopback only.

## Prepare secrets

From the repository root:

1. Copy `controller/deploy/.env.example` to `controller/deploy/.env`.
2. Replace both database passwords with different cryptographically random 64-character hexadecimal strings. Do not reuse Firebase credentials or commit the real `.env`.
3. Set `FIREBASE_PROJECT_ID` to the exact Firebase Project ID. The existing Fieldpulse project is the intended identity provider, but verify its Authentication sign-in provider is enabled before deployment.

Example password generation on a trusted machine:

```sh
openssl rand -hex 32
```

Generate two different values. The hexadecimal-only format is intentional so the password can safely be embedded in the PostgreSQL connection URL.

## Start a local/private instance

With Docker Engine and the Compose plugin installed:

```sh
docker compose --env-file controller/deploy/.env -f controller/deploy/compose.yaml up -d --build
docker compose --env-file controller/deploy/.env -f controller/deploy/compose.yaml ps
```

The controller listens on `127.0.0.1:8000` by default. Verify local process readiness at `http://127.0.0.1:8000/health`. The health endpoint is not an authenticated operational API and does not prove Firebase sign-in or SMS operation.

## Database and migration behaviour

- PostgreSQL 16 is used in a dedicated named Docker volume.
- On a **new empty volume**, the official PostgreSQL entrypoint runs V001–V014 in filename order, then `999-create-api-role.sh`.
- Migrations execute as the separate database owner; FastAPI connects as `dm_controller_api`, not as the database owner.
- The API role receives schema usage, table DML, sequence access and function execution required by the current service implementation. It is not a superuser and cannot create extensions or alter schema objects.
- The initialization scripts run only when the PostgreSQL data directory is empty. Updating an existing volume does not automatically apply later migrations. Back up the database and apply new migrations deliberately using the migration-owner credentials.
- Do not delete the named volume to force migrations; that destroys the database.

## Before exposing it to a phone or the internet

This starter deliberately binds only to loopback. A real deployment still requires a selected host, trusted HTTPS reverse proxy/load balancer, firewall rules, managed secrets, database backups and restore testing, monitoring/alerting, rate limiting, security review and an operational recovery plan. Do not expose port 8000 directly or change the bind address to a public interface without that protection.

The Firebase Project ID must match the Firebase Authentication ID tokens used by the Android client. The service does not use a Firebase service-account private key for ID-token signature verification.

## Gateway Android connection

The Android app must be configured with the final HTTPS base URL after deployment. The current APK does not contain a production host. Keep outbound SMS dispatch disabled until the deployed controller passes authenticated registration/heartbeat/claim/cancel tests and the controlled Glo/3030 physical-device test plan is completed.

## Status

- [x] Container image builds in Gateway Controller CI.
- [x] PostgreSQL V001–V014 and cancellation/cooldown integration tests pass in CI.
- [x] Local Compose deployment starter and separate runtime role are prepared.
- [ ] Real host/provider selected and provisioned.
- [ ] Production HTTPS and secrets configured.
- [ ] Database backup/restore and monitoring verified.
- [ ] Firebase Authentication provider verified against the exact project.
- [ ] Android connected to the deployed controller.
- [ ] Controlled multi-SIM and Glo/3030 tests completed.
- [ ] Outbound SMS dispatch explicitly enabled after safety sign-off.
