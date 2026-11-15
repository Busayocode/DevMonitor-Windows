from contextlib import contextmanager
from datetime import datetime, timezone
from uuid import uuid4

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import HTTPException
from fastapi.security import HTTPAuthorizationCredentials

import app as controller
from app import get_current_user, read_check_batch


class FakeSigningKey:
    def __init__(self, key):
        self.key = key


class FakeJwks:
    def __init__(self, key):
        self.key = key

    def get_signing_key_from_jwt(self, token):
        return FakeSigningKey(self.key)


class FakeResult:
    def __init__(self, row=None):
        self.row = row

    def fetchone(self):
        return self.row


class FakeUserConnection:
    def __init__(self, row=None):
        self.row = row
        self.executed = []
        self.transaction_count = 0

    @contextmanager
    def transaction(self):
        self.transaction_count += 1
        yield

    def execute(self, query, params):
        self.executed.append((query, params))
        return FakeResult(self.row)


def make_firebase_token(private_key, project="fieldpulse-test"):
    now = int(datetime.now(timezone.utc).timestamp())
    return jwt.encode(
        {
            "iss": f"https://securetoken.google.com/{project}",
            "aud": project,
            "auth_time": now - 30,
            "user_id": "verified-user",
            "sub": "verified-user",
            "iat": now,
            "exp": now + 3600,
            "firebase": {"sign_in_provider": "google.com"},
        },
        private_key,
        algorithm="RS256",
        headers={"kid": "integration-test-key"},
    )


def test_current_user_verifies_firebase_identity_and_resolves_internal_user(monkeypatch):
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    monkeypatch.setattr(controller, "FIREBASE_PROJECT_ID", "fieldpulse-test")
    monkeypatch.setattr(controller, "jwks_client", FakeJwks(private_key.public_key()))
    internal_id = uuid4()
    connection = FakeUserConnection(row={"id": internal_id})
    auth = HTTPAuthorizationCredentials(
        scheme="Bearer",
        credentials=make_firebase_token(private_key),
    )

    user = get_current_user(auth, connection)

    assert user["id"] == internal_id
    assert user["subject"] == "verified-user"
    assert user["claims"]["sub"] == "verified-user"
    assert connection.transaction_count == 1
    assert len(connection.executed) == 1
    query, params = connection.executed[0]
    assert "INSERT INTO dm_users(auth_subject)" in query
    assert "ON CONFLICT (auth_subject)" in query
    assert params == ("verified-user",)


def test_current_user_rejects_missing_bearer_credentials():
    connection = FakeUserConnection()

    with pytest.raises(HTTPException) as error:
        get_current_user(None, connection)

    assert error.value.status_code == 401
    assert connection.executed == []


def test_current_user_fails_closed_when_firebase_is_not_configured(monkeypatch):
    monkeypatch.setattr(controller, "FIREBASE_PROJECT_ID", None)
    monkeypatch.setattr(controller, "jwks_client", None)
    connection = FakeUserConnection()
    auth = HTTPAuthorizationCredentials(scheme="Bearer", credentials="any-token")

    with pytest.raises(HTTPException) as error:
        get_current_user(auth, connection)

    assert error.value.status_code == 503
    assert connection.executed == []


def test_current_user_rejects_token_signed_by_untrusted_key(monkeypatch):
    trusted = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    attacker = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    monkeypatch.setattr(controller, "FIREBASE_PROJECT_ID", "fieldpulse-test")
    monkeypatch.setattr(controller, "jwks_client", FakeJwks(trusted.public_key()))
    connection = FakeUserConnection()
    auth = HTTPAuthorizationCredentials(
        scheme="Bearer",
        credentials=make_firebase_token(attacker),
    )

    with pytest.raises(HTTPException) as error:
        get_current_user(auth, connection)

    assert error.value.status_code == 401
    assert error.value.detail == "Invalid Firebase ID token"
    assert connection.executed == []


def test_batch_read_is_scoped_to_membership_tenant_and_requesting_user():
    tenant_id = uuid4()
    batch_id = uuid4()
    user_id = uuid4()

    class BatchConnection:
        def __init__(self, member, batch):
            self.member = member
            self.batch = batch
            self.queries = []

        def execute(self, query, params):
            self.queries.append((query, params))
            if "dm_tenant_memberships" in query:
                return FakeResult({"allowed": True} if self.member else None)
            if "FROM dm_check_batches b" in query:
                return FakeResult(self.batch)
            raise AssertionError(f"Unexpected query: {query}")

    connection = BatchConnection(True, None)
    with pytest.raises(HTTPException) as error:
        read_check_batch(batch_id, tenant_id, {"id": user_id}, connection)

    assert error.value.status_code == 404
    batch_query, params = connection.queries[1]
    assert "b.id=%s AND b.tenant_id=%s AND b.requested_by_user_id=%s" in batch_query
    assert params == (batch_id, tenant_id, user_id)


def test_batch_read_hides_tenant_from_non_member():
    tenant_id = uuid4()
    batch_id = uuid4()
    user_id = uuid4()

    class BatchConnection:
        def execute(self, query, params):
            assert "dm_tenant_memberships" in query
            return FakeResult(None)

    with pytest.raises(HTTPException) as error:
        read_check_batch(batch_id, tenant_id, {"id": user_id}, BatchConnection())

    assert error.value.status_code == 404
