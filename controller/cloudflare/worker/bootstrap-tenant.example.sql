-- DevMonitor Gateway tenant bootstrap template.
-- Replace every placeholder before running this in the D1 Console.
-- The Firebase UID must already exist in dm_users (sign in once through the API first).

-- 1) Create the tenant.
INSERT INTO dm_tenants(id,display_name)
VALUES ('REPLACE_WITH_TENANT_UUID','REPLACE_WITH_TENANT_DISPLAY_NAME');

-- 2) Grant the intended Firebase user OWNER membership.
INSERT INTO dm_tenant_memberships(tenant_id,user_id,role)
SELECT 'REPLACE_WITH_TENANT_UUID',id,'OWNER'
FROM dm_users
WHERE auth_subject='REPLACE_WITH_FIREBASE_AUTH_UID';

-- 3) Grant only the device IDs that this tenant is authorized to check.
-- Add one VALUES row per approved canonical device ID.
INSERT INTO dm_tenant_device_access(tenant_id,canonical_device_id,access_state)
VALUES
 ('REPLACE_WITH_TENANT_UUID','REPLACE_WITH_APPROVED_DEVICE_ID','ACTIVE');

-- 4) Optional: make the intended administrator a controller operator.
-- This is a global, high-privilege role. Do not grant it to ordinary staff.
-- Uncomment only after verifying the Firebase UID.
-- INSERT INTO dm_controller_operators(user_id,active)
-- SELECT id,1 FROM dm_users WHERE auth_subject='REPLACE_WITH_OPERATOR_FIREBASE_UID'
-- ON CONFLICT(user_id) DO UPDATE SET active=1;
