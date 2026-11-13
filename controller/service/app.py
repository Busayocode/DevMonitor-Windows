from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
from contextlib import asynccontextmanager, suppress
from datetime import date, datetime
from typing import Any
from uuid import UUID

import jwt
from fastapi import Depends, FastAPI, Header, HTTPException, Query
from fastapi.responses import JSONResponse
from fastapi.encoders import jsonable_encoder
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, ConfigDict, Field
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from psycopg_pool import ConnectionPool
from jwt import PyJWKClient

DATABASE_URL = os.getenv("DATABASE_URL")
FIREBASE_PROJECT_ID = os.getenv("FIREBASE_PROJECT_ID")
FIREBASE_SECURE_TOKEN_JWKS_URL = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"

logger = logging.getLogger('devmonitor.gateway.controller')
db_pool: ConnectionPool | None = None
jwks_client: PyJWKClient | None = None
user_bearer = HTTPBearer(auto_error=False)
gateway_bearer = HTTPBearer(auto_error=False)


async def expired_lease_reaper() -> None:
    while True:
        await asyncio.sleep(max(5, int(os.getenv("REAPER_INTERVAL_SECONDS", "15"))))
        pool = db_pool
        if pool is None:
            continue
        try:
            def run_once():
                with pool.connection() as connection:
                    offline = connection.execute(
                        "SELECT dm_mark_stale_gateways_offline() AS gateways_marked_offline"
                    ).fetchone()
                    expired = connection.execute(
                        "SELECT * FROM dm_reap_expired_attempts()"
                    ).fetchone()
                    return {"health": offline, "leases": expired}
            counts = await asyncio.to_thread(run_once)
            health = counts["health"] or {}
            leases = counts["leases"] or {}
            if (health.get("gateways_marked_offline", 0)
                or leases.get("requeued_jobs", 0)
                or leases.get("quarantined_gateways", 0)):
                logger.warning("Gateway recovery and failover: %s", counts)
        except Exception:
            logger.exception("Gateway lease recovery pass failed")


async def queue_metrics_snapshotter() -> None:
    while True:
        await asyncio.sleep(max(30, int(os.getenv("QUEUE_SNAPSHOT_INTERVAL_SECONDS", "60"))))
        pool = db_pool
        if pool is None:
            continue
        try:
            def capture_once():
                with pool.connection() as connection:
                    return connection.execute("SELECT dm_capture_tenant_queue_snapshots() AS inserted").fetchone()
            result = await asyncio.to_thread(capture_once)
            logger.debug("Captured Gateway queue analytics snapshots: %s", result)
        except Exception:
            logger.exception("Gateway queue analytics snapshot failed")


@asynccontextmanager
async def lifespan(_: FastAPI):
    global db_pool, jwks_client
    reaper_task = None
    snapshot_task = None
    if DATABASE_URL:
        db_pool = ConnectionPool(
            conninfo=DATABASE_URL,
            min_size=1,
            max_size=int(os.getenv("DB_POOL_MAX_SIZE", "10")),
            kwargs={"row_factory": dict_row, "autocommit": True},
            open=False,
        )
        db_pool.open(wait=True)
        reaper_task = asyncio.create_task(expired_lease_reaper())
        snapshot_task = asyncio.create_task(queue_metrics_snapshotter())
    if FIREBASE_PROJECT_ID:
        jwks_client = PyJWKClient(FIREBASE_SECURE_TOKEN_JWKS_URL, cache_keys=True)
    try:
        yield
    finally:
        if reaper_task is not None:
            reaper_task.cancel()
            with suppress(asyncio.CancelledError):
                await reaper_task
        if snapshot_task is not None:
            snapshot_task.cancel()
            with suppress(asyncio.CancelledError):
                await snapshot_task
        if db_pool is not None:
            db_pool.close()
            db_pool = None


app = FastAPI(
    title="DevMonitor Gateway Controller",
    version="0.1.0",
    description="Reference controller API. Configure PostgreSQL and Firebase Authentication before use.",
    lifespan=lifespan,
)


class DeviceRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    device_id: str = Field(min_length=1, max_length=128)
    query_profile: str = Field(min_length=1, max_length=64)


class BatchRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    tenant_id: UUID
    preferred_gateway_id: UUID | None = None
    devices: list[DeviceRequest] = Field(min_length=1, max_length=100)
    app_version: str | None = Field(default=None, max_length=40)


class GatewayRegisterRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    display_name: str = Field(min_length=1, max_length=80)


class PoolParticipationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    accept_pool_jobs: bool


class HeartbeatRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    app_version: str = Field(max_length=40)
    sms_permission_ready: bool
    telephony_ready: bool
    network_ready: bool
    last_successful_3030_at: datetime | None = None


class AttemptRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    attempt_id: UUID
    lease_token: str = Field(min_length=32, max_length=128)


class AwaitingReplyRequest(AttemptRequest):
    sms_sent_at: datetime


class ResultValues(BaseModel):
    model_config = ConfigDict(extra="forbid")
    todayCount: int | None = None
    mtdCount: int | None = None
    active: int | None = None
    bar48: int | None = None
    bar96: int | None = None
    duplicates: int | None = None
    monthlyTarget: int | None = None


class ResultRequest(AttemptRequest):
    business_date: date
    checked_at: datetime
    received_at: datetime
    values: ResultValues
    parser_version: str = Field(min_length=1, max_length=40)


class AmbiguousRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    attempt_id: UUID
    reason: str = Field(min_length=1, max_length=160)


class SendFailureRequest(AttemptRequest):
    failure_code: str = Field(min_length=1, max_length=100)


class CancellationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    reason: str | None = Field(default=None, max_length=240)


def require_database() -> ConnectionPool:
    if db_pool is None:
        raise HTTPException(status_code=503, detail="Controller database is not configured")
    return db_pool


def gateway_token_digest(token: str) -> bytes:
    try:
        raw = bytes.fromhex(token)
    except ValueError as exc:
        raise ValueError("gateway credential is not valid hexadecimal") from exc
    if len(raw) != 32:
        raise ValueError("gateway credential has an invalid length")
    return hashlib.sha256(raw).digest()


def canonical_request_fingerprint(payload: dict[str, Any]) -> bytes:
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(canonical).digest()


def reply_timeout_for_profile(query_profile: str) -> int:
    """Keep the established standard (120s) and Partner (180s) waits."""
    normalized = query_profile.strip().lower().replace("-", "_").replace(" ", "_")
    return 180 if "partner" in normalized else 120


def validate_history_window(start: date, end: date, max_days: int = 62) -> None:
    if end < start:
        raise ValueError("history end date precedes start date")
    if (end - start).days > max_days:
        raise ValueError("history range exceeds the two-month retention window")


def get_db():
    pool = require_database()
    with pool.connection() as connection:
        yield connection


def bearer_token(credentials: HTTPAuthorizationCredentials | None, detail: str) -> str:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status_code=401, detail=detail)
    return credentials.credentials


def verify_firebase_id_token(token: str, project_id: str, key_client: PyJWKClient) -> dict[str, Any]:
    """Verify a Firebase Authentication ID token for exactly one Firebase project."""
    issuer = f"https://securetoken.google.com/{project_id}"
    signing_key = key_client.get_signing_key_from_jwt(token).key
    claims = jwt.decode(
        token,
        signing_key,
        algorithms=["RS256"],
        issuer=issuer,
        audience=project_id,
        options={"require": ["exp", "iat", "sub", "auth_time"]},
    )
    subject = claims.get("sub")
    auth_time = claims.get("auth_time")
    if not isinstance(subject, str) or not subject or len(subject) > 128:
        raise ValueError("Firebase token has an invalid UID")
    if not isinstance(auth_time, int) or auth_time <= 0:
        raise ValueError("Firebase token has an invalid authentication time")
    return claims


def get_current_user(
    credentials: HTTPAuthorizationCredentials | None = Depends(user_bearer),
    connection=Depends(get_db),
) -> dict[str, Any]:
    token = bearer_token(credentials, "User authentication required")
    if not (FIREBASE_PROJECT_ID and jwks_client):
        raise HTTPException(status_code=503, detail="Firebase Authentication is not configured")
    try:
        claims = verify_firebase_id_token(token, FIREBASE_PROJECT_ID, jwks_client)
    except Exception as exc:
        raise HTTPException(status_code=401, detail="Invalid Firebase ID token") from exc
    subject = claims["sub"]
    with connection.transaction():
        row = connection.execute(
            """
            INSERT INTO dm_users(auth_subject) VALUES (%s)
            ON CONFLICT (auth_subject) DO UPDATE SET auth_subject = EXCLUDED.auth_subject
            RETURNING id
            """,
            (subject,),
        ).fetchone()
    return {"id": row["id"], "subject": subject, "claims": claims}


def get_current_gateway(
    credentials: HTTPAuthorizationCredentials | None = Depends(gateway_bearer),
    connection=Depends(get_db),
) -> dict[str, Any]:
    token = bearer_token(credentials, "Gateway authentication required")
    try:
        token_hash = gateway_token_digest(token)
    except ValueError as exc:
        raise HTTPException(status_code=401, detail="Invalid gateway credential") from exc
    row = connection.execute(
        """
        SELECT id, owner_user_id, health_state, accept_pool_jobs, revoked_at
          FROM dm_gateways
         WHERE credential_hash = %s AND revoked_at IS NULL
        """,
        (token_hash,),
    ).fetchone()
    if row is None:
        raise HTTPException(status_code=401, detail="Gateway credential is invalid or revoked")
    return row


def require_tenant_device_access(connection, user_id: UUID, tenant_id: UUID, device_id: str) -> None:
    member = connection.execute(
        "SELECT 1 FROM dm_tenant_memberships WHERE tenant_id=%s AND user_id=%s",
        (tenant_id, user_id),
    ).fetchone()
    if member is None:
        raise HTTPException(status_code=404, detail="Tenant or device not found")
    grant = connection.execute(
        """
        SELECT 1 FROM dm_tenant_device_access
         WHERE tenant_id=%s AND canonical_device_id=%s AND access_state='ACTIVE'
        """,
        (tenant_id, device_id),
    ).fetchone()
    if grant is None:
        raise HTTPException(status_code=404, detail="Tenant or device not found")


@app.get("/health")
def health():
    return {"status": "ok", "database_configured": db_pool is not None}


@app.post("/v1/client/check-batches")
def create_check_batch(
    body: BatchRequest,
    idempotency_key: str = Header(alias="Idempotency-Key", min_length=12, max_length=128),
    user=Depends(get_current_user),
    connection=Depends(get_db),
):
    device_ids = [item.device_id.strip() for item in body.devices]
    if any(not value for value in device_ids) or len(set(device_ids)) != len(device_ids):
        raise HTTPException(status_code=400, detail="Device IDs must be non-empty and unique within a batch")

    fingerprint_payload = {
        "tenant_id": str(body.tenant_id),
        "preferred_gateway_id": str(body.preferred_gateway_id) if body.preferred_gateway_id else None,
        "devices": [{"device_id": item.device_id.strip(), "query_profile": item.query_profile} for item in body.devices],
        "app_version": body.app_version,
    }
    fingerprint = canonical_request_fingerprint(fingerprint_payload)

    with connection.transaction():
        membership = connection.execute(
            "SELECT 1 FROM dm_tenant_memberships WHERE tenant_id=%s AND user_id=%s",
            (body.tenant_id, user["id"]),
        ).fetchone()
        if membership is None:
            raise HTTPException(status_code=403, detail="You are not a member of this tenant")

        if body.preferred_gateway_id:
            owned = connection.execute(
                "SELECT 1 FROM dm_gateways WHERE id=%s AND owner_user_id=%s AND revoked_at IS NULL",
                (body.preferred_gateway_id, user["id"]),
            ).fetchone()
            if owned is None:
                raise HTTPException(status_code=403, detail="Selected personal gateway is not available to this account")

        grants = connection.execute(
            """
            SELECT canonical_device_id FROM dm_tenant_device_access
             WHERE tenant_id=%s AND access_state='ACTIVE'
               AND canonical_device_id = ANY(%s)
            """,
            (body.tenant_id, device_ids),
        ).fetchall()
        if {row["canonical_device_id"] for row in grants} != set(device_ids):
            raise HTTPException(status_code=403, detail="One or more devices are not authorized for this tenant")

        existing = connection.execute(
            """
            SELECT b.id, b.request_fingerprint,
                   (SELECT count(*) FROM dm_check_jobs j WHERE j.batch_id=b.id) AS total_jobs,
                   CASE
                     WHEN EXISTS (SELECT 1 FROM dm_check_jobs j WHERE j.batch_id=b.id
                                  AND j.state IN ('QUEUED','RETRY_PENDING','LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS'))
                       THEN 'RUNNING'
                     WHEN EXISTS (SELECT 1 FROM dm_check_jobs j WHERE j.batch_id=b.id AND j.state='FAILED')
                       AND NOT EXISTS (SELECT 1 FROM dm_check_jobs j WHERE j.batch_id=b.id AND j.state='COMPLETED')
                       THEN 'FAILED'
                     WHEN EXISTS (SELECT 1 FROM dm_check_jobs j WHERE j.batch_id=b.id AND j.state='FAILED')
                       THEN 'PARTIAL'
                     WHEN EXISTS (SELECT 1 FROM dm_check_jobs j WHERE j.batch_id=b.id)
                       AND NOT EXISTS (SELECT 1 FROM dm_check_jobs j WHERE j.batch_id=b.id AND j.state<>'COMPLETED')
                       THEN 'COMPLETED'
                     ELSE 'QUEUED'
                   END AS status
              FROM dm_check_batches b
             WHERE b.tenant_id=%s AND b.requested_by_user_id=%s AND b.client_idempotency_key=%s
            """,
            (body.tenant_id, user["id"], idempotency_key),
        ).fetchone()
        if existing:
            if existing["request_fingerprint"] is None or bytes(existing["request_fingerprint"]) != fingerprint:
                raise HTTPException(status_code=409, detail="Idempotency key was already used for a different request")
            return JSONResponse(
                status_code=200,
                content=jsonable_encoder({
                    "batch_id": existing["id"],
                    "status": existing["status"],
                    "total_jobs": existing["total_jobs"],
                    "replayed": True,
                }),
            )

        batch = connection.execute(
            """
            INSERT INTO dm_check_batches(
                tenant_id, requested_by_user_id, client_idempotency_key,
                app_edition, app_version, total_jobs, request_fingerprint
            ) VALUES (%s,%s,%s,'GATEWAY',%s,%s,%s)
            RETURNING id, status, total_jobs
            """,
            (body.tenant_id, user["id"], idempotency_key, body.app_version, len(body.devices), fingerprint),
        ).fetchone()

        for item in body.devices:
            connection.execute(
                """
                INSERT INTO dm_check_jobs(
                    batch_id, tenant_id, requested_by_user_id, canonical_device_id,
                    query_profile, preferred_gateway_id
                ) VALUES (%s,%s,%s,%s,%s,%s)
                """,
                (
                    batch["id"], body.tenant_id, user["id"], item.device_id.strip(),
                    item.query_profile, body.preferred_gateway_id,
                ),
            )

        # Allocate the complete request as a unit across currently ready Gateways.
        # The database function is deterministic and safe to call more than once.
        connection.execute("SELECT dm_rebalance_unstarted_batches()")

    return JSONResponse(
        status_code=202,
        content=jsonable_encoder({
            "batch_id": batch["id"],
            "status": batch["status"],
            "total_jobs": batch["total_jobs"],
            "replayed": False,
        }),
    )


@app.get("/v1/client/check-batches/{batch_id}")
def read_check_batch(
    batch_id: UUID,
    tenant_id: UUID,
    user=Depends(get_current_user),
    connection=Depends(get_db),
):
    member = connection.execute(
        "SELECT 1 FROM dm_tenant_memberships WHERE tenant_id=%s AND user_id=%s",
        (tenant_id, user["id"]),
    ).fetchone()
    if member is None:
        raise HTTPException(status_code=404, detail="Batch not found")
    batch = connection.execute(
        """
        SELECT b.id, b.created_at, b.updated_at, b.status,
               b.assigned_gateway_id, b.cancellation_requested_at, b.cancelled_at,
               (SELECT count(*) FROM dm_check_jobs j WHERE j.batch_id=b.id) AS total_jobs,
               (SELECT count(*) FROM dm_check_jobs j WHERE j.batch_id=b.id AND j.state='COMPLETED') AS completed_jobs,
               (SELECT count(*) FROM dm_check_jobs j WHERE j.batch_id=b.id AND j.state='FAILED') AS failed_jobs,
               (SELECT count(*) FROM dm_check_jobs j WHERE j.batch_id=b.id AND j.state='CANCELLED') AS cancelled_jobs,
               (SELECT count(*) FROM dm_check_jobs j WHERE j.batch_id=b.id
                  AND j.state IN ('QUEUED','RETRY_PENDING')) AS waiting_jobs,
               (SELECT count(*) FROM dm_check_jobs j WHERE j.batch_id=b.id
                  AND j.state IN ('LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS')) AS active_jobs
          FROM dm_check_batches b
         WHERE b.id=%s AND b.tenant_id=%s AND b.requested_by_user_id=%s
        """,
        (batch_id, tenant_id, user["id"]),
    ).fetchone()
    if batch is None:
        raise HTTPException(status_code=404, detail="Batch not found")
    jobs = connection.execute(
        """
        SELECT id, canonical_device_id, query_profile, state, attempt_count, enqueued_at, updated_at
          FROM dm_check_jobs WHERE batch_id=%s ORDER BY enqueued_at, id
        """,
        (batch_id,),
    ).fetchall()
    return jsonable_encoder({"batch": batch, "jobs": jobs})


@app.get("/v1/client/devices/{device_id}/latest")
def read_latest_result(
    device_id: str,
    tenant_id: UUID,
    user=Depends(get_current_user),
    connection=Depends(get_db),
):
    require_tenant_device_access(connection, user["id"], tenant_id, device_id)
    row = connection.execute(
        """
        SELECT canonical_device_id, business_date, checked_at, received_at,
               values_json, parser_version, gateway_id
          FROM dm_daily_sms_results
         WHERE tenant_id=%s AND canonical_device_id=%s
         ORDER BY checked_at DESC LIMIT 1
        """,
        (tenant_id, device_id),
    ).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="No cached result is available")
    return jsonable_encoder(row)


@app.get("/v1/client/devices/{device_id}/history")
def read_device_history(
    device_id: str,
    tenant_id: UUID,
    start: date = Query(alias="from"),
    end: date = Query(alias="to"),
    user=Depends(get_current_user),
    connection=Depends(get_db),
):
    require_tenant_device_access(connection, user["id"], tenant_id, device_id)
    try:
        validate_history_window(start, end)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    rows = connection.execute(
        """
        SELECT canonical_device_id, business_date, checked_at, received_at,
               values_json, parser_version, gateway_id
          FROM dm_daily_sms_results
         WHERE tenant_id=%s AND canonical_device_id=%s
           AND business_date BETWEEN %s AND %s
         ORDER BY business_date
        """,
        (tenant_id, device_id, start, end),
    ).fetchall()
    return jsonable_encoder({"device_id": device_id, "results": rows})


@app.get("/v1/client/gateways")
def list_personal_gateways(user=Depends(get_current_user), connection=Depends(get_db)):
    rows = connection.execute(
        """
        SELECT id, display_name, health_state, accept_pool_jobs, app_version,
               sms_permission_ready, telephony_ready, network_ready, heartbeat_at,
               last_successful_3030_at, revoked_at
          FROM dm_gateways WHERE owner_user_id=%s ORDER BY created_at
        """,
        (user["id"],),
    ).fetchall()
    return jsonable_encoder({"gateways": rows})


@app.post("/v1/client/gateways", status_code=201)
def register_personal_gateway(
    body: GatewayRegisterRequest,
    user=Depends(get_current_user),
    connection=Depends(get_db),
):
    row = connection.execute(
        "SELECT * FROM dm_register_personal_gateway(%s,%s)",
        (user["id"], body.display_name.strip()),
    ).fetchone()
    return {"gateway_id": row["gateway_id"], "gateway_token": row["gateway_token"]}


@app.patch("/v1/client/gateways/{gateway_id}/pool-participation")
def set_pool_participation(
    gateway_id: UUID,
    body: PoolParticipationRequest,
    user=Depends(get_current_user),
    connection=Depends(get_db),
):
    updated = connection.execute(
        "SELECT dm_set_gateway_pool_participation(%s,%s,%s) AS updated",
        (user["id"], gateway_id, body.accept_pool_jobs),
    ).fetchone()["updated"]
    if not updated:
        raise HTTPException(status_code=404, detail="Gateway not found")
    return {"gateway_id": gateway_id, "accept_pool_jobs": body.accept_pool_jobs}


@app.delete("/v1/client/gateways/{gateway_id}")
def revoke_personal_gateway(
    gateway_id: UUID,
    user=Depends(get_current_user),
    connection=Depends(get_db),
):
    revoked = connection.execute(
        "SELECT dm_revoke_personal_gateway(%s,%s) AS revoked",
        (user["id"], gateway_id),
    ).fetchone()["revoked"]
    if not revoked:
        raise HTTPException(status_code=404, detail="Gateway not found")
    return {"gateway_id": gateway_id, "revoked": True}


@app.post("/v1/gateway/heartbeat")
def gateway_heartbeat(
    body: HeartbeatRequest,
    gateway=Depends(get_current_gateway),
    connection=Depends(get_db),
):
    state = connection.execute(
        "SELECT dm_record_gateway_heartbeat(%s,%s,%s,%s,%s,%s) AS state",
        (
            gateway["id"], body.app_version, body.sms_permission_ready,
            body.telephony_ready, body.network_ready, body.last_successful_3030_at,
        ),
    ).fetchone()["state"]
    return {"gateway_id": gateway["id"], "health_state": state}


@app.get("/v1/gateway/active-attempt")
def gateway_active_attempt(gateway=Depends(get_current_gateway), connection=Depends(get_db)):
    row = connection.execute(
        """
        SELECT a.job_id, a.id AS attempt_id, a.state, a.lease_expires_at,
               j.canonical_device_id, j.query_profile
          FROM dm_job_attempts a
          JOIN dm_check_jobs j ON j.id=a.job_id
         WHERE a.gateway_id=%s
           AND a.state IN ('LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS')
         LIMIT 1
        """,
        (gateway["id"],),
    ).fetchone()
    return {"active_attempt": jsonable_encoder(row) if row else None,
            "note": "The lease token is never recoverable from the server; the gateway must retain it locally."}


@app.post("/v1/gateway/jobs/claim")
def claim_gateway_job(gateway=Depends(get_current_gateway), connection=Depends(get_db)):
    row = connection.execute("SELECT * FROM dm_claim_next_job(%s,%s)", (gateway["id"], 120)).fetchone()
    return {"job": jsonable_encoder(row) if row else None}


@app.post("/v1/gateway/jobs/{job_id}/sending")
def mark_job_sending(
    job_id: UUID,
    body: AttemptRequest,
    gateway=Depends(get_current_gateway),
    connection=Depends(get_db),
):
    accepted = connection.execute(
        "SELECT dm_mark_attempt_sending(%s,%s,%s,%s) AS accepted",
        (gateway["id"], job_id, body.attempt_id, body.lease_token),
    ).fetchone()["accepted"]
    if not accepted:
        raise HTTPException(status_code=409, detail="Attempt or lease is no longer valid")
    return {"job_id": job_id, "state": "SENDING"}


@app.post("/v1/gateway/jobs/{job_id}/awaiting-reply")
def mark_job_awaiting_reply(
    job_id: UUID,
    body: AwaitingReplyRequest,
    gateway=Depends(get_current_gateway),
    connection=Depends(get_db),
):
    job = connection.execute(
        """
        SELECT query_profile FROM dm_check_jobs
         WHERE id=%s AND state='SENDING'
        """,
        (job_id,),
    ).fetchone()
    if job is None:
        raise HTTPException(status_code=409, detail="Job is not in SENDING state")
    timeout = reply_timeout_for_profile(job["query_profile"])
    accepted = connection.execute(
        "SELECT dm_mark_attempt_awaiting_reply(%s,%s,%s,%s,%s,%s) AS accepted",
        (gateway["id"], job_id, body.attempt_id, body.lease_token, body.sms_sent_at, timeout),
    ).fetchone()["accepted"]
    if not accepted:
        raise HTTPException(status_code=409, detail="Attempt or lease is no longer valid")
    return {"job_id": job_id, "state": "AWAITING_REPLY", "reply_timeout_seconds": timeout}


@app.post("/v1/gateway/jobs/{job_id}/result")
def submit_gateway_result(
    job_id: UUID,
    body: ResultRequest,
    gateway=Depends(get_current_gateway),
    connection=Depends(get_db),
):
    accepted = connection.execute(
        """
        SELECT dm_complete_job_result(%s,%s,%s,%s,%s,%s,%s,%s,%s) AS accepted
        """,
        (
            gateway["id"], job_id, body.attempt_id, body.lease_token,
            body.business_date, body.checked_at, body.received_at,
            Jsonb(body.values.model_dump()), body.parser_version,
        ),
    ).fetchone()["accepted"]
    if not accepted:
        raise HTTPException(status_code=409, detail="Result does not match a live gateway attempt")
    return {"job_id": job_id, "state": "COMPLETED", "cache_updated_if_newer": True}


@app.post("/v1/gateway/jobs/{job_id}/ambiguous")
def mark_gateway_attempt_ambiguous(
    job_id: UUID,
    body: AmbiguousRequest,
    gateway=Depends(get_current_gateway),
    connection=Depends(get_db),
):
    accepted = connection.execute(
        "SELECT dm_mark_attempt_ambiguous(%s,%s,%s,%s) AS accepted",
        (gateway["id"], job_id, body.attempt_id, body.reason),
    ).fetchone()["accepted"]
    if not accepted:
        raise HTTPException(status_code=409, detail="Attempt is not active on this gateway")
    return {"job_id": job_id, "state": "AMBIGUOUS", "gateway_health": "QUARANTINED"}


@app.post("/v1/gateway/jobs/{job_id}/send-failed")
def mark_gateway_send_failed(
    job_id: UUID,
    body: SendFailureRequest,
    gateway=Depends(get_current_gateway),
    connection=Depends(get_db),
):
    accepted = connection.execute(
        "SELECT dm_mark_definitive_send_failure(%s,%s,%s,%s,%s) AS accepted",
        (gateway["id"], job_id, body.attempt_id, body.lease_token, body.failure_code),
    ).fetchone()["accepted"]
    if not accepted:
        raise HTTPException(status_code=409, detail="Attempt is not a definitive pre-transmission send failure")
    return {"job_id": job_id, "state": "RETRY_PENDING_OR_FAILED"}



def _queue_snapshot(connection, batch_id: UUID, tenant_id: UUID, user_id: UUID) -> dict[str, Any]:
    # Re-evaluate stale Gateway assignments before reporting the user's live queue.
    connection.execute("SELECT dm_rebalance_unstarted_batches()")
    batch = connection.execute(
        """
        SELECT id, tenant_id, requested_by_user_id, status, created_at,
               assigned_gateway_id, cancellation_requested_at, cancelled_at,
               cancellation_reason, total_jobs
          FROM dm_check_batches
         WHERE id=%s AND tenant_id=%s AND requested_by_user_id=%s
        """,
        (batch_id, tenant_id, user_id),
    ).fetchone()
    if batch is None:
        raise HTTPException(status_code=404, detail="Batch not found")

    counts = connection.execute(
        """
        SELECT
          count(*) AS total_devices,
          count(*) FILTER (WHERE state='COMPLETED') AS completed_devices,
          count(*) FILTER (WHERE state='FAILED') AS failed_devices,
          count(*) FILTER (WHERE state='CANCELLED') AS cancelled_devices,
          count(*) FILTER (WHERE state IN ('QUEUED','RETRY_PENDING')) AS waiting_devices,
          count(*) FILTER (WHERE state IN ('LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS')) AS active_devices,
          count(*) FILTER (WHERE state IN ('QUEUED','RETRY_PENDING','LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS')) AS remaining_devices
        FROM dm_check_jobs WHERE batch_id=%s
        """,
        (batch_id,),
    ).fetchone()

    if batch["assigned_gateway_id"] is not None:
        ahead = connection.execute(
            """
            WITH current_batch AS (
                SELECT b.id,b.created_at,b.assigned_gateway_id,g.owner_user_id,
                       EXISTS (
                           SELECT 1 FROM dm_check_jobs own_job
                            WHERE own_job.batch_id=b.id
                              AND own_job.preferred_gateway_id=b.assigned_gateway_id
                              AND own_job.requested_by_user_id=g.owner_user_id
                       ) AS personal_priority
                  FROM dm_check_batches b
                  LEFT JOIN dm_gateways g ON g.id=b.assigned_gateway_id
                 WHERE b.id=%s
            )
            SELECT count(j.id) AS devices_ahead,
                   count(DISTINCT b.id) AS requests_ahead
              FROM current_batch cb
              JOIN dm_check_batches b ON b.assigned_gateway_id=cb.assigned_gateway_id
                                     AND b.id<>cb.id
              JOIN dm_check_jobs j ON j.batch_id=b.id
             WHERE b.cancellation_requested_at IS NULL
               AND j.state IN ('QUEUED','RETRY_PENDING','LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS')
               AND (
                 (cb.personal_priority
                  AND EXISTS (
                      SELECT 1 FROM dm_check_jobs prior_personal
                       WHERE prior_personal.batch_id=b.id
                         AND prior_personal.preferred_gateway_id=cb.assigned_gateway_id
                         AND prior_personal.requested_by_user_id=cb.owner_user_id
                  )
                  AND (b.created_at,b.id)<(cb.created_at,cb.id))
                 OR
                 (NOT cb.personal_priority AND (
                    EXISTS (
                        SELECT 1 FROM dm_check_jobs prior_personal
                         WHERE prior_personal.batch_id=b.id
                           AND prior_personal.preferred_gateway_id=cb.assigned_gateway_id
                           AND prior_personal.requested_by_user_id=cb.owner_user_id
                    )
                    OR (
                        NOT EXISTS (
                            SELECT 1 FROM dm_check_jobs prior_personal
                             WHERE prior_personal.batch_id=b.id
                               AND prior_personal.preferred_gateway_id=cb.assigned_gateway_id
                               AND prior_personal.requested_by_user_id=cb.owner_user_id
                        )
                        AND (b.created_at,b.id)<(cb.created_at,cb.id)
                    )
                 ))
               )
            """,
            (batch_id,),
        ).fetchone()
        gateway = connection.execute(
            """
            SELECT display_name, health_state, heartbeat_at,
                   (health_state='READY' AND revoked_at IS NULL
                    AND heartbeat_at >= now()-INTERVAL '90 seconds'
                    AND sms_permission_ready AND telephony_ready AND network_ready) AS available
              FROM dm_gateways WHERE id=%s
            """,
            (batch["assigned_gateway_id"],),
        ).fetchone()
    else:
        ahead = connection.execute(
            """
            SELECT count(j.id) AS devices_ahead,
                   count(DISTINCT b.id) AS requests_ahead
              FROM dm_check_batches b
              JOIN dm_check_jobs j ON j.batch_id=b.id
             WHERE b.tenant_id=%s AND b.id<>%s
               AND (b.created_at,b.id) < (%s,%s)
               AND b.assigned_gateway_id IS NULL
               AND b.cancellation_requested_at IS NULL
               AND j.state IN ('QUEUED','RETRY_PENDING','LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS')
            """,
            (tenant_id, batch_id, batch["created_at"], batch_id),
        ).fetchone()
        gateway = None

    global_queue = connection.execute(
        """
        SELECT count(*) AS pending_devices
          FROM dm_check_jobs j
          JOIN dm_check_batches b ON b.id=j.batch_id
         WHERE b.cancellation_requested_at IS NULL
           AND j.state IN ('QUEUED','RETRY_PENDING','LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS')
        """
    ).fetchone()["pending_devices"]

    service_seconds = None
    if batch["assigned_gateway_id"] is not None:
        service_seconds = connection.execute(
            """
            SELECT percentile_cont(0.5) WITHIN GROUP (
                       ORDER BY extract(epoch FROM (a.reply_received_at-a.sms_sent_at))
                   ) AS median_seconds
              FROM dm_job_attempts a
             WHERE a.gateway_id=%s
               AND a.sms_sent_at IS NOT NULL
               AND a.reply_received_at IS NOT NULL
               AND a.reply_received_at >= now()-INTERVAL '30 days'
            """,
            (batch["assigned_gateway_id"],),
        ).fetchone()["median_seconds"]
    if service_seconds is None:
        service_seconds = connection.execute(
            """
            SELECT avg(CASE WHEN lower(query_profile) LIKE '%partner%' THEN 180.0 ELSE 120.0 END) AS fallback
              FROM dm_check_jobs WHERE batch_id=%s
            """,
            (batch_id,),
        ).fetchone()["fallback"] or 120.0

    devices_ahead = int(ahead["devices_ahead"] or 0)
    requests_ahead = int(ahead["requests_ahead"] or 0)
    remaining = int(counts["remaining_devices"] or 0)
    started = int(counts["active_devices"] or 0) > 0 or int(counts["completed_devices"] or 0) > 0
    can_estimate = bool(gateway and gateway["available"]) and batch["assigned_gateway_id"] is not None
    estimated_start = 0 if started else (round(devices_ahead * float(service_seconds)) if can_estimate else None)
    estimated_completion = (
        round((devices_ahead + remaining) * float(service_seconds))
        if can_estimate else None
    )
    queue_position = requests_ahead + 1 if batch["assigned_gateway_id"] is not None else requests_ahead + 1

    return jsonable_encoder({
        "batch_id": batch_id,
        "status": batch["status"],
        "gateway_id": batch["assigned_gateway_id"],
        "gateway_name": gateway["display_name"] if gateway else None,
        "gateway_available": gateway["available"] if gateway else False,
        "queue_position_on_gateway": queue_position,
        "requests_ahead_on_gateway": requests_ahead,
        "devices_ahead_on_gateway": devices_ahead,
        "global_pending_devices": int(global_queue or 0),
        "total_devices": int(counts["total_devices"] or 0),
        "completed_devices": int(counts["completed_devices"] or 0),
        "failed_devices": int(counts["failed_devices"] or 0),
        "cancelled_devices": int(counts["cancelled_devices"] or 0),
        "waiting_devices": int(counts["waiting_devices"] or 0),
        "active_devices": int(counts["active_devices"] or 0),
        "remaining_devices": remaining,
        "estimated_start_seconds": estimated_start,
        "estimated_completion_seconds": estimated_completion,
        "estimate_basis": "30-day median reply time for this Gateway; otherwise the configured 120s/180s query wait profile",
        "cancellation_requested_at": batch["cancellation_requested_at"],
        "cancelled_at": batch["cancelled_at"],
        "cancellation_reason": batch["cancellation_reason"],
    })


@app.get("/v1/client/check-batches/{batch_id}/queue")
def read_check_batch_queue(
    batch_id: UUID,
    tenant_id: UUID,
    user=Depends(get_current_user),
    connection=Depends(get_db),
):
    return _queue_snapshot(connection, batch_id, tenant_id, user["id"])


@app.post("/v1/client/check-batches/{batch_id}/cancel")
def cancel_check_batch(
    batch_id: UUID,
    tenant_id: UUID,
    body: CancellationRequest,
    user=Depends(get_current_user),
    connection=Depends(get_db),
):
    row = connection.execute(
        """
        SELECT * FROM dm_cancel_check_batch(%s,%s,%s,%s)
        """,
        (batch_id, tenant_id, user["id"], body.reason),
    ).fetchone()
    if row is None or not row["accepted"]:
        raise HTTPException(status_code=404, detail="Batch not found")
    cancellation = connection.execute(
        "SELECT cancellation_requested_at FROM dm_check_batches WHERE id=%s AND tenant_id=%s AND requested_by_user_id=%s",
        (batch_id, tenant_id, user["id"]),
    ).fetchone()
    return jsonable_encoder({
        "batch_id": batch_id,
        "status": row["batch_status"],
        "total_devices": row["total_jobs"],
        "completed_devices": row["completed_jobs"],
        "cancelled_devices": row["cancelled_jobs"],
        "in_flight_devices": row["in_flight_jobs"],
        "cancellation_requested": bool(cancellation and cancellation["cancellation_requested_at"]),
    })


@app.get("/v1/client/tenants/{tenant_id}/gateway-analytics")
def read_gateway_analytics(
    tenant_id: UUID,
    user=Depends(get_current_user),
    connection=Depends(get_db),
):
    membership = connection.execute(
        """
        SELECT role FROM dm_tenant_memberships
         WHERE tenant_id=%s AND user_id=%s
        """,
        (tenant_id, user["id"]),
    ).fetchone()
    if membership is None:
        raise HTTPException(status_code=404, detail="Tenant not found")
    if membership["role"] not in ("OWNER", "ADMIN"):
        raise HTTPException(status_code=403, detail="Tenant administrator access required")

    metrics = connection.execute(
        """
        WITH batches AS (
            SELECT * FROM dm_check_batches WHERE tenant_id=%s
        ),
        jobs AS (
            SELECT j.* FROM dm_check_jobs j WHERE j.tenant_id=%s
        ),
        attempts AS (
            SELECT a.*, j.enqueued_at
              FROM dm_job_attempts a
              JOIN jobs j ON j.id=a.job_id
        ),
        gateways AS (
            SELECT g.* FROM dm_gateways g
             WHERE g.accept_pool_jobs
                OR EXISTS (
                    SELECT 1 FROM dm_tenant_memberships m
                     WHERE m.user_id=g.owner_user_id AND m.tenant_id=%s
                )
        ),
        health_segments AS (
            SELECT h.gateway_id,h.new_state,h.event_at,
                   lead(h.event_at,1,now()) OVER (
                       PARTITION BY h.gateway_id ORDER BY h.event_at
                   ) AS next_at
              FROM dm_gateway_health_events h
              JOIN gateways g ON g.id=h.gateway_id
             WHERE h.event_at >= now()-INTERVAL '30 days'
        ),
        uptime AS (
            SELECT
              sum(extract(epoch FROM (least(next_at,now())-greatest(event_at,now()-INTERVAL '30 days'))))
                FILTER (WHERE new_state='READY') AS ready_seconds,
              sum(extract(epoch FROM (least(next_at,now())-greatest(event_at,now()-INTERVAL '30 days'))))
                AS observed_seconds
              FROM health_segments
        )
        SELECT
          (SELECT count(*) FROM batches) AS total_requests,
          (SELECT count(*) FROM jobs) AS total_devices_requested,
          (SELECT count(*) FROM jobs WHERE state='COMPLETED') AS completed_devices,
          (SELECT count(*) FROM jobs WHERE state='FAILED') AS failed_devices,
          (SELECT count(*) FROM jobs WHERE state='CANCELLED') AS cancelled_devices,
          (SELECT count(*) FROM jobs WHERE state IN ('QUEUED','RETRY_PENDING')) AS waiting_devices,
          (SELECT count(*) FROM jobs WHERE state IN ('LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS')) AS active_devices,
          (SELECT count(*) FROM batches WHERE cancellation_requested_at IS NOT NULL) AS cancelled_requests,
          (SELECT count(*) FROM attempts) AS total_attempts,
          (SELECT count(*) FROM attempts WHERE state='TIMED_OUT') AS timed_out_attempts,
          (SELECT count(*) FROM attempts WHERE state='AMBIGUOUS') AS ambiguous_attempts,
          (SELECT percentile_cont(0.5) WITHIN GROUP (
              ORDER BY extract(epoch FROM (send_started_at-enqueued_at))
           ) FROM attempts WHERE send_started_at IS NOT NULL) AS median_queue_wait_seconds,
          (SELECT percentile_cont(0.5) WITHIN GROUP (
              ORDER BY extract(epoch FROM (reply_received_at-sms_sent_at))
           ) FROM attempts WHERE sms_sent_at IS NOT NULL AND reply_received_at IS NOT NULL) AS median_reply_seconds,
          (SELECT count(*) FROM gateways) AS registered_gateways,
          (SELECT count(*) FROM gateways WHERE health_state='READY'
             AND revoked_at IS NULL AND heartbeat_at >= now()-INTERVAL '90 seconds'
             AND sms_permission_ready AND telephony_ready AND network_ready) AS ready_gateways,
          (SELECT count(*) FROM gateways WHERE health_state='QUARANTINED') AS quarantined_gateways,
          (SELECT count(*) FROM gateways WHERE health_state='OFFLINE'
             OR heartbeat_at IS NULL OR heartbeat_at < now()-INTERVAL '90 seconds') AS offline_gateways,
          (SELECT count(*) FROM dm_gateway_health_events h
             JOIN gateways g ON g.id=h.gateway_id
            WHERE h.event_at >= now()-INTERVAL '30 days') AS health_transitions_30d,
          (SELECT round(ready_seconds*100/nullif(observed_seconds,0),1) FROM uptime)
             AS observed_uptime_percent_30d
        """,
        (tenant_id, tenant_id, tenant_id),
    ).fetchone()

    daily = connection.execute(
        """
        SELECT date_trunc('day',b.created_at)::date AS day,
               count(DISTINCT b.id) AS requests,
               count(j.id) AS devices_requested,
               count(j.id) FILTER (WHERE j.state='COMPLETED') AS devices_completed,
               count(j.id) FILTER (WHERE j.state='CANCELLED') AS devices_cancelled
          FROM dm_check_batches b
          LEFT JOIN dm_check_jobs j ON j.batch_id=b.id
         WHERE b.tenant_id=%s AND b.created_at >= now()-INTERVAL '30 days'
         GROUP BY date_trunc('day',b.created_at)::date
         ORDER BY day
        """,
        (tenant_id,),
    ).fetchall()

    queue_history = connection.execute(
        """
        SELECT date_trunc('hour',captured_at) AS hour,
               max(pending_devices) AS peak_pending_devices,
               round(avg(pending_devices)::numeric,1) AS average_pending_devices,
               max(waiting_requests) AS peak_waiting_requests,
               max(ready_gateways) AS peak_ready_gateways,
               max(busy_gateways) AS peak_busy_gateways
          FROM dm_tenant_queue_snapshots
         WHERE tenant_id=%s AND captured_at >= now()-INTERVAL '30 days'
         GROUP BY date_trunc('hour',captured_at)
         ORDER BY hour
        """,
        (tenant_id,),
    ).fetchall()

    queue_peaks = connection.execute(
        """
        SELECT max(pending_devices) AS peak_pending_devices,
               round(avg(pending_devices)::numeric,1) AS average_pending_devices,
               max(waiting_requests) AS peak_waiting_requests
          FROM dm_tenant_queue_snapshots
         WHERE tenant_id=%s AND captured_at >= now()-INTERVAL '30 days'
        """,
        (tenant_id,),
    ).fetchone()

    total_requests = int(metrics["total_requests"] or 0)
    cancelled_requests = int(metrics["cancelled_requests"] or 0)
    total_attempts = int(metrics["total_attempts"] or 0)
    completed = int(metrics["completed_devices"] or 0)
    failed = int(metrics["failed_devices"] or 0)
    ready_gateways = int(metrics["ready_gateways"] or 0)
    busy_gateways = int(connection.execute(
        """
        SELECT count(*) FROM dm_gateways g
         WHERE g.health_state='READY' AND g.revoked_at IS NULL
           AND g.heartbeat_at >= now()-INTERVAL '90 seconds'
           AND EXISTS (SELECT 1 FROM dm_job_attempts a WHERE a.gateway_id=g.id
                        AND a.state IN ('LEASED','SENDING','AWAITING_REPLY','AMBIGUOUS'))
           AND (g.accept_pool_jobs OR EXISTS (
                SELECT 1 FROM dm_tenant_memberships m
                 WHERE m.user_id=g.owner_user_id AND m.tenant_id=%s
           ))
        """,
        (tenant_id,),
    ).fetchone()["count"] or 0)
    return jsonable_encoder({
        "period_days": 30,
        "metrics": {
            **metrics,
            "request_cancellation_rate_percent": round(cancelled_requests * 100 / total_requests, 1) if total_requests else 0,
            "device_success_rate_percent": round(completed * 100 / (completed + failed), 1) if completed + failed else None,
            "attempt_timeout_rate_percent": round(int(metrics["timed_out_attempts"] or 0) * 100 / total_attempts, 1) if total_attempts else None,
            "instantaneous_gateway_utilization_percent": round(busy_gateways * 100 / ready_gateways, 1) if ready_gateways else None,
            "peak_pending_devices_30d": queue_peaks["peak_pending_devices"],
            "average_pending_devices_30d": queue_peaks["average_pending_devices"],
            "peak_waiting_requests_30d": queue_peaks["peak_waiting_requests"],
        },
        "daily_activity": daily,
        "queue_depth_history_30d": queue_history,
        "note": "Metrics are tenant-scoped. Queue snapshots are sampled every minute while the controller is running; gaps can occur during controller downtime. Gateway utilization is an instantaneous busy/ready ratio. Observed uptime uses recorded health transitions and excludes periods without transition history.",
    })
