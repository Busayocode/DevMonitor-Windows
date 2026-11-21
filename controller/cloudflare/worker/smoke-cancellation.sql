-- Local-only D1 smoke test for the three cancellation outcomes.
-- This runs against Wrangler's disposable local D1 database in CI.

CREATE TABLE IF NOT EXISTS smoke_assertions (ok INTEGER NOT NULL CHECK(ok=1));

INSERT INTO dm_users(id,auth_subject) VALUES
 ('11111111-1111-4111-8111-111111111111','smoke-test-firebase-uid');
INSERT INTO dm_tenants(id,display_name) VALUES
 ('22222222-2222-4222-8222-222222222222','Smoke test tenant');
INSERT INTO dm_tenant_memberships(tenant_id,user_id,role) VALUES
 ('22222222-2222-4222-8222-222222222222','11111111-1111-4111-8111-111111111111','OWNER');
INSERT INTO dm_tenant_device_access(tenant_id,canonical_device_id,access_state) VALUES
 ('22222222-2222-4222-8222-222222222222','SMOKE-DEVICE-1','ACTIVE'),
 ('22222222-2222-4222-8222-222222222222','SMOKE-DEVICE-2','ACTIVE'),
 ('22222222-2222-4222-8222-222222222222','SMOKE-DEVICE-3','ACTIVE');
INSERT INTO dm_gateways(id,owner_user_id,display_name,credential_hash,health_state,accept_pool_jobs,sms_permission_ready,telephony_ready,network_ready,heartbeat_at,created_at,updated_at)
VALUES('33333333-3333-4333-8333-333333333333','11111111-1111-4111-8111-111111111111','Smoke Gateway',lower(hex(randomblob(32))),'READY',1,1,1,1,strftime('%Y-%m-%dT%H:%M:%fZ','now'),strftime('%Y-%m-%dT%H:%M:%fZ','now'),strftime('%Y-%m-%dT%H:%M:%fZ','now'));

-- After-SMS cancellation: must reject the attempt, cancel the job and save a four-minute deadline.
INSERT INTO dm_check_batches(id,tenant_id,requested_by_user_id,client_idempotency_key,status,total_jobs,request_fingerprint,assigned_gateway_id)
VALUES('44444444-4444-4444-8444-444444444444','22222222-2222-4222-8222-222222222222','11111111-1111-4111-8111-111111111111','smoke-after-send-key','RUNNING',1,'smoke-fingerprint','33333333-3333-4333-8333-333333333333');
INSERT INTO dm_check_jobs(id,batch_id,tenant_id,requested_by_user_id,canonical_device_id,query_profile,preferred_gateway_id,state,attempt_count,active_attempt_id)
VALUES('55555555-5555-4555-8555-555555555555','44444444-4444-4444-8444-444444444444','22222222-2222-4222-8222-222222222222','11111111-1111-4111-8111-111111111111','SMOKE-DEVICE-1','standard','33333333-3333-4333-8333-333333333333','AWAITING_REPLY',1,'66666666-6666-4666-8666-666666666666');
INSERT INTO dm_job_attempts(id,job_id,gateway_id,attempt_number,lease_token_hash,state,leased_at,lease_expires_at,send_started_at,sms_sent_at,reply_deadline_at,created_at)
VALUES('66666666-6666-4666-8666-666666666666','55555555-5555-4555-8555-555555555555','33333333-3333-4333-8333-333333333333',1,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','AWAITING_REPLY',strftime('%Y-%m-%dT%H:%M:%fZ','now'),strftime('%Y-%m-%dT%H:%M:%fZ','now','+2 minutes'),strftime('%Y-%m-%dT%H:%M:%fZ','now'),strftime('%Y-%m-%dT%H:%M:%fZ','now'),strftime('%Y-%m-%dT%H:%M:%fZ','now','+2 minutes'),strftime('%Y-%m-%dT%H:%M:%fZ','now'));

INSERT OR IGNORE INTO dm_gateway_attempt_cancellations(attempt_id,job_id,gateway_id,outcome,reason,cooldown_until,created_at)
SELECT a.id,a.job_id,a.gateway_id,
 CASE WHEN a.state='LEASED' AND j.state='LEASED' AND a.send_started_at IS NULL AND a.sms_sent_at IS NULL THEN 'CANCELLED_BEFORE_SEND'
      WHEN a.state='AWAITING_REPLY' AND j.state='AWAITING_REPLY' AND a.send_started_at IS NOT NULL AND a.sms_sent_at IS NOT NULL THEN 'CANCELLED_AFTER_SEND'
      WHEN a.state IN('SENDING','AMBIGUOUS') OR j.state IN('SENDING','AMBIGUOUS') THEN 'BLOCKED_QUARANTINED' END,
 'smoke test',CASE WHEN a.state='AWAITING_REPLY' AND j.state='AWAITING_REPLY' AND a.send_started_at IS NOT NULL AND a.sms_sent_at IS NOT NULL THEN strftime('%Y-%m-%dT%H:%M:%fZ','now','+4 minutes') ELSE NULL END,
 strftime('%Y-%m-%dT%H:%M:%fZ','now')
FROM dm_job_attempts a JOIN dm_check_jobs j ON j.id=a.job_id
WHERE a.id='66666666-6666-4666-8666-666666666666' AND a.job_id='55555555-5555-4555-8555-555555555555'
 AND a.gateway_id='33333333-3333-4333-8333-333333333333'
 AND a.lease_token_hash='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
 AND j.active_attempt_id=a.id
 AND a.state='AWAITING_REPLY' AND j.state='AWAITING_REPLY' AND a.send_started_at IS NOT NULL AND a.sms_sent_at IS NOT NULL;

UPDATE dm_job_attempts SET state='REJECTED',completed_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'),failure_code='USER_CANCELLED_AFTER_SEND'
WHERE id='66666666-6666-4666-8666-666666666666' AND state='AWAITING_REPLY'
 AND EXISTS(SELECT 1 FROM dm_gateway_attempt_cancellations WHERE attempt_id='66666666-6666-4666-8666-666666666666' AND outcome='CANCELLED_AFTER_SEND');
UPDATE dm_check_jobs SET state='CANCELLED',active_attempt_id=NULL,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')
WHERE id='55555555-5555-4555-8555-555555555555' AND active_attempt_id='66666666-6666-4666-8666-666666666666' AND state='AWAITING_REPLY';
UPDATE dm_gateways SET cancellation_cooldown_until=(SELECT cooldown_until FROM dm_gateway_attempt_cancellations WHERE attempt_id='66666666-6666-4666-8666-666666666666'),updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')
WHERE id='33333333-3333-4333-8333-333333333333' AND EXISTS(SELECT 1 FROM dm_gateway_attempt_cancellations WHERE attempt_id='66666666-6666-4666-8666-666666666666' AND outcome='CANCELLED_AFTER_SEND');
INSERT INTO smoke_assertions(ok)
SELECT CASE WHEN
 (SELECT state FROM dm_job_attempts WHERE id='66666666-6666-4666-8666-666666666666')='REJECTED'
 AND (SELECT state FROM dm_check_jobs WHERE id='55555555-5555-4555-8555-555555555555')='CANCELLED'
 AND (SELECT cancellation_cooldown_until FROM dm_gateways WHERE id='33333333-3333-4333-8333-333333333333') IS NOT NULL
 AND abs((julianday((SELECT cancellation_cooldown_until FROM dm_gateways WHERE id='33333333-3333-4333-8333-333333333333'))-julianday((SELECT created_at FROM dm_gateway_attempt_cancellations WHERE attempt_id='66666666-6666-4666-8666-666666666666')))*86400.0-240)<2
 THEN 1 ELSE 0 END;

-- Pre-send cancellation: no cooldown is created.
INSERT INTO dm_check_batches(id,tenant_id,requested_by_user_id,client_idempotency_key,status,total_jobs,request_fingerprint,assigned_gateway_id)
VALUES('44444444-4444-4444-8444-444444444445','22222222-2222-4222-8222-222222222222','11111111-1111-4111-8111-111111111111','smoke-before-send-key','RUNNING',1,'smoke-fingerprint-2','33333333-3333-4333-8333-333333333333');
INSERT INTO dm_check_jobs(id,batch_id,tenant_id,requested_by_user_id,canonical_device_id,query_profile,preferred_gateway_id,state,attempt_count,active_attempt_id)
VALUES('55555555-5555-4555-8555-555555555556','44444444-4444-4444-8444-444444444445','22222222-2222-4222-8222-222222222222','11111111-1111-4111-8111-111111111111','SMOKE-DEVICE-2','standard','33333333-3333-4333-8333-333333333333','LEASED',1,'66666666-6666-4666-8666-666666666667');
INSERT INTO dm_job_attempts(id,job_id,gateway_id,attempt_number,lease_token_hash,state,leased_at,lease_expires_at,created_at)
VALUES('66666666-6666-4666-8666-666666666667','55555555-5555-4555-8555-555555555556','33333333-3333-4333-8333-333333333333',1,'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb','LEASED',strftime('%Y-%m-%dT%H:%M:%fZ','now'),strftime('%Y-%m-%dT%H:%M:%fZ','now','+2 minutes'),strftime('%Y-%m-%dT%H:%M:%fZ','now'));
INSERT OR IGNORE INTO dm_gateway_attempt_cancellations(attempt_id,job_id,gateway_id,outcome,reason,cooldown_until,created_at)
SELECT a.id,a.job_id,a.gateway_id,'CANCELLED_BEFORE_SEND','smoke test',NULL,strftime('%Y-%m-%dT%H:%M:%fZ','now')
FROM dm_job_attempts a JOIN dm_check_jobs j ON j.id=a.job_id
WHERE a.id='66666666-6666-4666-8666-666666666667' AND a.job_id='55555555-5555-4555-8555-555555555556'
 AND a.gateway_id='33333333-3333-4333-8333-333333333333'
 AND a.lease_token_hash='bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
 AND j.active_attempt_id=a.id AND a.state='LEASED' AND j.state='LEASED'
 AND a.send_started_at IS NULL AND a.sms_sent_at IS NULL;
UPDATE dm_job_attempts SET state='REJECTED',failure_code='USER_CANCELLED_BEFORE_SEND',completed_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id='66666666-6666-4666-8666-666666666667' AND state='LEASED';
UPDATE dm_check_jobs SET state='CANCELLED',active_attempt_id=NULL WHERE id='55555555-5555-4555-8555-555555555556' AND state='LEASED';
INSERT INTO smoke_assertions(ok)
SELECT CASE WHEN (SELECT outcome FROM dm_gateway_attempt_cancellations WHERE attempt_id='66666666-6666-4666-8666-666666666667')='CANCELLED_BEFORE_SEND'
 AND (SELECT cancellation_cooldown_until FROM dm_gateways WHERE id='33333333-3333-4333-8333-333333333333')=(SELECT cooldown_until FROM dm_gateway_attempt_cancellations WHERE attempt_id='66666666-6666-4666-8666-666666666666')
 THEN 1 ELSE 0 END;

-- Uncertain transmission: block cancellation and quarantine the Gateway.
INSERT INTO dm_check_batches(id,tenant_id,requested_by_user_id,client_idempotency_key,status,total_jobs,request_fingerprint,assigned_gateway_id)
VALUES('44444444-4444-4444-8444-444444444446','22222222-2222-4222-8222-222222222222','11111111-1111-4111-8111-111111111111','smoke-ambiguous-key','RUNNING',1,'smoke-fingerprint-3','33333333-3333-4333-8333-333333333333');
INSERT INTO dm_check_jobs(id,batch_id,tenant_id,requested_by_user_id,canonical_device_id,query_profile,preferred_gateway_id,state,attempt_count,active_attempt_id)
VALUES('55555555-5555-4555-8555-555555555557','44444444-4444-4444-8444-444444444446','22222222-2222-4222-8222-222222222222','11111111-1111-4111-8111-111111111111','SMOKE-DEVICE-3','standard','33333333-3333-4333-8333-333333333333','SENDING',1,'66666666-6666-4666-8666-666666666668');
INSERT INTO dm_job_attempts(id,job_id,gateway_id,attempt_number,lease_token_hash,state,leased_at,lease_expires_at,send_started_at,created_at)
VALUES('66666666-6666-4666-8666-666666666668','55555555-5555-4555-8555-555555555557','33333333-3333-4333-8333-333333333333',1,'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','SENDING',strftime('%Y-%m-%dT%H:%M:%fZ','now'),strftime('%Y-%m-%dT%H:%M:%fZ','now','+2 minutes'),strftime('%Y-%m-%dT%H:%M:%fZ','now'),strftime('%Y-%m-%dT%H:%M:%fZ','now'));
INSERT OR IGNORE INTO dm_gateway_attempt_cancellations(attempt_id,job_id,gateway_id,outcome,reason,cooldown_until,created_at)
SELECT a.id,a.job_id,a.gateway_id,'BLOCKED_QUARANTINED','smoke test',NULL,strftime('%Y-%m-%dT%H:%M:%fZ','now')
FROM dm_job_attempts a JOIN dm_check_jobs j ON j.id=a.job_id
WHERE a.id='66666666-6666-4666-8666-666666666668' AND a.job_id='55555555-5555-4555-8555-555555555557'
 AND a.gateway_id='33333333-3333-4333-8333-333333333333'
 AND a.lease_token_hash='cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'
 AND j.active_attempt_id=a.id AND (a.state IN('SENDING','AMBIGUOUS') OR j.state IN('SENDING','AMBIGUOUS'));
UPDATE dm_gateways SET health_state='QUARANTINED',accept_pool_jobs=0,quarantined_reason='Smoke test uncertain transmission'
WHERE id='33333333-3333-4333-8333-333333333333'
 AND EXISTS(SELECT 1 FROM dm_gateway_attempt_cancellations WHERE attempt_id='66666666-6666-4666-8666-666666666668' AND outcome='BLOCKED_QUARANTINED');
INSERT INTO smoke_assertions(ok)
SELECT CASE WHEN (SELECT outcome FROM dm_gateway_attempt_cancellations WHERE attempt_id='66666666-6666-4666-8666-666666666668')='BLOCKED_QUARANTINED'
 AND (SELECT health_state FROM dm_gateways WHERE id='33333333-3333-4333-8333-333333333333')='QUARANTINED'
 THEN 1 ELSE 0 END;

SELECT 'PASS: after-send cooldown, pre-send no-cooldown and uncertain-send quarantine' AS smoke_test_result;
