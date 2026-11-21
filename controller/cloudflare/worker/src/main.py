"""Cloudflare Python Worker runtime proof for DevMonitor Gateway.

This is deliberately only a health-check skeleton. It does not implement
Gateway operations or connect to production data.
"""
from fastapi import FastAPI
from workers import asgi

app = FastAPI(
    title="DevMonitor Gateway Cloudflare Prototype",
    version="0.1.0",
    description="Runtime compatibility proof only; not a production controller.",
)


@app.get("/")
async def root():
    return {
        "service": "DevMonitor Gateway",
        "status": "prototype",
        "production_ready": False,
    }


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "service": "devmonitor-gateway-cloudflare-prototype",
        "production_ready": False,
    }


# Cloudflare supplies the ASGI server; do not start Uvicorn in a Worker.
Default = asgi.entrypoint(app)
