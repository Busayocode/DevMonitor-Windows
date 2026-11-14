import pytest
from fastapi import HTTPException

from app import require_tenant_device_access


class FakeAccessConnection:
    def __init__(self, *, member=True, grant=True):
        self.member = member
        self.grant = grant

    def execute(self, query, params):
        if "dm_tenant_memberships" in query:
            return self._result(self.member)
        if "dm_tenant_device_access" in query:
            return self._result(self.grant)
        raise AssertionError(f"Unexpected access query: {query}")

    @staticmethod
    def _result(found):
        return FakeResult({"allowed": True} if found else None)


class FakeResult:
    def __init__(self, row):
        self.row = row

    def fetchone(self):
        return self.row


def test_second_tenant_member_can_read_shared_device_cache_without_requesting_it():
    # Access is based on tenant membership and the tenant's active device grant,
    # not on whether this member created the original check batch.
    require_tenant_device_access(
        FakeAccessConnection(member=True, grant=True),
        user_id="authorised-member",
        tenant_id="shared-tenant",
        device_id="DEVICE-123",
    )


def test_user_outside_tenant_cannot_read_shared_cache():
    with pytest.raises(HTTPException) as error:
        require_tenant_device_access(
            FakeAccessConnection(member=False, grant=True),
            user_id="outsider",
            tenant_id="shared-tenant",
            device_id="DEVICE-123",
        )
    assert error.value.status_code == 404


def test_revoked_device_grant_blocks_history_and_latest_cache_reads():
    with pytest.raises(HTTPException) as error:
        require_tenant_device_access(
            FakeAccessConnection(member=True, grant=False),
            user_id="authorised-member",
            tenant_id="shared-tenant",
            device_id="DEVICE-123",
        )
    assert error.value.status_code == 404
