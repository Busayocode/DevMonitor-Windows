from __future__ import annotations

import hashlib
import json
import os
from contextlib import asynccontextmanager
from datetime import date, datetime
from typing import Any
from uuid import UUID

import jwt
from fastapi import Depends, FastAPI, Header, HTTPException, Query
from fastapi.encoders import jsonable_encoder
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, ConfigDict, Field
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from psycopg_pool import ConnectionPool
from jwt import PyJWKClient

DATABASE_URL = os.getenv("DATABASE_URL")
OIDC_ISSUER = os.getenv("OIDC_ISSUER")
OIDC_AUDIENCE = os.getenv("OIDC_AUDIENCE")
OIDC_JWKS_URL = os.getenv("OIDC_JWKS_URL")

db_pool: ConnectionPool | None = None
jwks_client: PyJWKClient | None = None
user_bearer = HTTPBearer(auto_error=False)
gateway_bearer = HTTPBearer(auto_error=False)


@asynccontextmanager
async def lifespan(_: FastAPI):
    global db_pool, jwks_client
    if DATABASE_URL:
        db_pool = ConnectionPool(
            conninfo=DATABASE_URL,
            min_size=1,
            max_size=int(os.getenv("DB_POOL_MAX_SIZE", "10")),
            kwargs={"row_factory": dict_row},
            open=False,
        )
        db_pool.open(wait=True)
    if OIDC_JWKS_URL:
        jwks_client = PyJWKClient(OIDC_JWKS_URL, cache_keys=True)
    yield
    if db_pool is not None:
        db_pool.close()
        db_pool = None


app = FastAPI(
    title="DevMonitor Gateway Controller",
    version="0.1.0",
    description="Reference controller API. Configure PostgreSQL and an OIDC identity provider before use.",
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


def get_current_user(
    credentials: HTTPAuthorizationCredentials | None = Depends(user_bearer),
    connection=Depends(get_db),
) -> dict[str, Any]:
    token = bearer_token(credentials, "User authentication required")
    if not (OIDC_ISSUER and OIDC_AUDIENCE and jwks_client):
        raise HTTPException(status_code=503, detail="OIDC identity provider is not configured")
    try:
        signing_key = jwks_client.get_signing_key_from_jwt(token).key
        claims = jwt.decode(
            token,
            signing_key,
            algorithms=["RS256", "ES256"],
            issuer=OIDC_ISSUER,
            audience=OIDC_AUDIENCE,
            options={"require": ["exp", "iat", "sub"]},
        )
    except Exception as exc:
        raise HTTPException(status_code=401, detail="Invalid user access token") from exc
    subject = claims.get("sub")
    if not isinstance(subject, str) or not subject:
        raise HTTPException(status_code=401, detail="User token has no subject")
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
            SELECT id, request_fingerprint, status, total_jobs
              FROM dm_check_batches
             WHERE tenant_id=%s AND requested_by_user_id=%s AND client_idempotency_key=%s
            """,
            (body.tenant_id, user["id"], idempotency_key),
        ).fetchone()
        if existing:
            if bytes(existing["request_fingerprint"]) != fingerprint:
                raise HTTPException(status_code=409, detail="Idempotency key was already used for a different request")
            return {
                "batch_id": existing["id"],
                "status": existing["status"],
                "total_jobs": existing["total_jobs"],
                "replayed": True,
            }

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

    return {
        "batch_id": batch["id"],
        "status": batch["status"],
        "total_jobs": batch["total_jobs"],
        "replayed": False,
    }


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
        SELECT id, status, total_jobs, completed_jobs, failed_jobs, created_at, updated_at
          FROM dm_check_batches
         WHERE id=%s AND tenant_id=%s
        """,
        (batch_id, tenant_id),
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
