import hashlib

import pytest
from fastapi import HTTPException
from fastapi.security import HTTPAuthorizationCredentials

from app import get_current_gateway


class FakeResult:
    def __init__(self, row):
        self.row = row

    def fetchone(self):
        return self.row


class FakeGatewayConnection:
    def __init__(self, row=None):
        self.row = row
        self.query = None
        self.params = None

    def execute(self, query, params):
        self.query = query
        self.params = params
        return FakeResult(self.row)


def credentials(token):
    return HTTPAuthorizationCredentials(scheme="Bearer", credentials=token)


def test_gateway_auth_looks_up_sha256_digest_not_plaintext_token():
    token = "ab" * 32
    expected_digest = hashlib.sha256(bytes.fromhex(token)).digest()
    gateway = {"id": "gateway-1", "owner_user_id": "owner-1", "health_state": "READY"}

    connection = FakeGatewayConnection(row=gateway)
    result = get_current_gateway(credentials(token), connection)

    assert result == gateway
    assert connection.params == (expected_digest,)
    assert token not in str(connection.params)
    assert "credential_hash" in connection.query
    assert "revoked_at IS NULL" in connection.query


def test_gateway_auth_rejects_revoked_or_unknown_credentials():
    token = "cd" * 32
    connection = FakeGatewayConnection(row=None)

    with pytest.raises(HTTPException) as error:
        get_current_gateway(credentials(token), connection)

    assert error.value.status_code == 401
    assert "invalid or revoked" in error.value.detail


@pytest.mark.parametrize("token", ["not-hex", "ab" * 8, ""])
def test_gateway_auth_rejects_malformed_credentials_before_database_lookup(token):
    connection = FakeGatewayConnection(row=None)

    with pytest.raises(HTTPException) as error:
        get_current_gateway(credentials(token), connection)

    assert error.value.status_code == 401
    assert connection.query is None
