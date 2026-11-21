# Cloudflare Worker runtime proof

This project proves only that the supported Cloudflare Python Worker runtime can load a FastAPI ASGI application and serve HTTPS health endpoints.

It is **not** the Gateway controller and must not be connected to the Android app.

## Local development

Requires Python 3.13+, Node.js, and uv:

```sh
uv sync
uv run pywrangler dev
```

Open `/health` on the local development address. The endpoint deliberately reports `production_ready: false`.

## Build-only check

```sh
uv run pywrangler deploy --dry-run
```

This compiles the Worker without deploying it.

## Not implemented here

- Firebase ID-token verification
- D1 persistence or schema
- Gateway registration, heartbeat, claim, result, or cancellation endpoints
- Cooldown and delayed-reply safety
- Scheduled recovery
- Android endpoint configuration

Do not add real credentials to this folder. Do not deploy this skeleton as a live Gateway service.
