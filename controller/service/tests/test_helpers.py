from datetime import date

import pytest

from app import (
    canonical_request_fingerprint,
    gateway_token_digest,
    reply_timeout_for_profile,
    validate_history_window,
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
