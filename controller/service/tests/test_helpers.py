from datetime import date, datetime, timedelta, timezone

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa

from app import (
    canonical_request_fingerprint,
    gateway_token_digest,
    reply_timeout_for_profile,
    validate_history_window,
    verify_firebase_id_token,
)


def test_fingerprint_is_stable_for_object_key_order():
    first = {"tenant": "x", "devices": [{"id": "1", "profile": "standard"}]}
    second = {"devices": [{"profile": "standard", "id": "1"}], "tenant": "x"}
    assert canonical_request_fingerprint(first) == canonical_request_fingerprint(second)


def test_fingerprint_changes_when_device_order_changes():
    first = {"devices": ["A", "B"]}
    second = {"devices": ["B", "A"]}
    assert canonical_request_fingerprint(first) != canonical_request_fingerprint(second)


def test_gateway_token_hash_requires_32_bytes():
    token = "ab" * 32
    assert len(gateway_token_digest(token)) == 32
    with pytest.raises(ValueError):
        gateway_token_digest("not-hex")
    with pytest.raises(ValueError):
        gateway_token_digest("ab" * 8)


def test_existing_sms_wait_profiles_are_preserved():
    assert reply_timeout_for_profile("standard") == 120
    assert reply_timeout_for_profile("device_monitor") == 120
    assert reply_timeout_for_profile("partner_summary") == 180
    assert reply_timeout_for_profile("Partner Menu") == 180


def test_history_is_limited_to_two_month_window():
    validate_history_window(date(2026, 9, 1), date(2026, 11, 2))
    with pytest.raises(ValueError):
        validate_history_window(date(2026, 8, 1), date(2026, 11, 2))
    with pytest.raises(ValueError):
        validate_history_window(date(2026, 11, 2), date(2026, 11, 1))



class FakeSigningKey:
    def __init__(self, key):
        self.key = key

class FakeJwksClient:
    def __init__(self, key):
        self.key = key

    def get_signing_key_from_jwt(self, token):
        return FakeSigningKey(self.key)


def _firebase_test_token(private_key, *, audience="fieldpulse-test", issuer=None):
    now = int(datetime.now(timezone.utc).timestamp())
    return jwt.encode(
        {
            "iss": issuer or f"https://securetoken.google.com/{audience}",
            "aud": audience,
            "auth_time": now - 10,
            "user_id": "test-firebase-uid",
            "sub": "test-firebase-uid",
            "iat": now,
            "exp": now + 3600,
            "firebase": {"sign_in_provider": "google.com"},
        },
        private_key,
        algorithm="RS256",
        headers={"kid": "test-key"},
    )


def test_firebase_id_token_verifies_project_and_uid():
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    token = _firebase_test_token(private_key)
    claims = verify_firebase_id_token(token, "fieldpulse-test", FakeJwksClient(private_key.public_key()))
    assert claims["sub"] == "test-firebase-uid"
    assert claims["firebase"]["sign_in_provider"] == "google.com"


def test_firebase_id_token_rejects_wrong_project_audience():
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    token = _firebase_test_token(private_key, audience="another-project")
    with pytest.raises(jwt.InvalidAudienceError):
        verify_firebase_id_token(token, "fieldpulse-test", FakeJwksClient(private_key.public_key()))
