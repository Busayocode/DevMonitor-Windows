# DevMonitor 5.0 — Gateway Architecture and Protocol (Milestone 1)

**Branch:** `devmonitor-5.0-foundation`  
**Status:** Architecture proposal for implementation planning  
**Scope:** Multi-phone SMS gateway, controller, client protocol and reliability model  
**Non-goal:** This milestone does not change the existing Full edition or production SMS workflow.

## 1. System overview

DevMonitor 5.0 has two user-selected editions:

- **DevMonitor Full:** keeps its current direct SMS implementation.
- **DevMonitor Gateway:** has no native SMS send/receive permissions and submits SMS Checker work to the gateway service.

The gateway infrastructure consists of:

1. **DevMonitor Gateway Controller (server):** authenticates clients and gateway phones, owns the durable job queue, assigns work, tracks status, validates results and returns them to the requesting user.
2. **Gateway Phone App:** runs on dedicated Android phones with a working SIM. It receives assigned work, sends SMS to 3030, receives SMS replies and submits normalized results.
3. **DevMonitor Gateway Edition:** submits work and displays progress/results using the existing DevMonitor presentation and persistence rules.

The gateway controller is the authority for job ownership and assignment. Gateway phones must not independently choose jobs or access another user's work.

## 2. Logical topology

```text
DevMonitor Gateway Edition (Android / Windows)
                  |
             HTTPS + Auth
                  |
        DevMonitor Gateway Controller
          /          |           \
   Gateway 1     Gateway 2     Gateway 3
    Android       Android       Android
    SIM/SMS       SIM/SMS       SIM/SMS
       \             |            /
          SMS network — 3030
```

The gateway phones may be in different physical locations. They need mobile network coverage and internet connectivity. The controller coordinates them over authenticated internet connections.

## 3. Responsibilities and trust boundaries

### Controller responsibilities

- Authenticate every client and gateway.
- Enforce tenant/user ownership on every request and result.
- Maintain durable jobs, assignments, attempts and status.
- Assign jobs only to healthy, eligible gateways.
- Apply per-gateway concurrency and rate limits.
- Match replies to the correct job and device.
- Handle timeout, retry, cancellation and late response policies.
- Expose sanitized status and result data to the owning client.
- Record an audit trail without retaining unnecessary SMS content.

### Gateway phone responsibilities

- Register with the controller and report health.
- Maintain an authenticated connection or poll for work.
- Accept only controller-assigned jobs.
- Send the approved query text to 3030.
- Receive SMS responses through Android's SMS broadcast mechanism.
- Correlate the reply with the active assigned job.
- Submit the response and delivery metadata.
- Persist its active job locally so process/network interruption can recover safely.
- Never receive credentials or data belonging to another user.

The gateway phone app will require the SMS permissions and installer authorization applicable to the Android version and distribution method. The ordinary Gateway Edition client must not declare SMS permissions.

### Client responsibilities

- Authenticate the user.
- Submit a batch of device checks and a client-generated idempotency key.
- Display queued, assigned, running, completed, partial, failed or cancelled status.
- Save returned results using the existing DevMonitor daily-history rules.
- Avoid treating an accepted job as a completed SMS result.

## 4. Proposed API contract

All communication uses HTTPS. JSON examples below are illustrative protocol contracts, not implemented endpoints yet. Exact paths and authentication provider are to be finalized during backend selection.

### Client submits a batch

```http
POST /v1/client/check-batches
Authorization: Bearer <user-access-token>
Idempotency-Key: <client-generated-unique-key>
Content-Type: application/json
```

```json
{
  "client_batch_id": "uuid",
  "request_type": "sms_checker",
  "requested_at": "ISO-8601 timestamp",
  "devices": [
    {
      "device_id": "GLO-GD-example",
      "query_profile": "existing-devmonitor-profile"
    }
  ],
  "client_context": {
    "app_edition": "gateway",
    "app_version": "version"
  }
}
```

The server validates the user, allowed device scope, payload size and duplicate key. It returns a batch ID and accepted status. It must not report a device check as complete merely because the batch was accepted.

### Client checks progress

```http
GET /v1/client/check-batches/{batch_id}
Authorization: Bearer <user-access-token>
```

The response contains aggregate counts and per-device state. The client can poll with server-provided backoff guidance. A later version may add push notifications, but polling is sufficient for the first reliable prototype.

### Gateway registers and reports health

```http
POST /v1/gateway/register
POST /v1/gateway/heartbeat
Authorization: Bearer <gateway-device-token>
```

Health data should include gateway ID, app version, connectivity, SMS permission state, SIM/telephony readiness, network availability, current workload and last successful 3030 exchange. Do not expose phone numbers or SIM identifiers to ordinary clients.

### Gateway claims work

```http
POST /v1/gateway/jobs/claim
Authorization: Bearer <gateway-device-token>
```

The controller returns either no work or one bounded assignment. A job assignment contains a unique job ID, lease token, expiry, device identifier, query profile, response timeout and attempt number. It must not include another user's unrelated device list.

### Gateway submits a result

```http
POST /v1/gateway/jobs/{job_id}/result
Authorization: Bearer <gateway-device-token>
```

The payload includes the lease token, attempt ID, send timestamp, reply timestamp, normalized parsed values, parser version and a response fingerprint. Raw SMS text should be excluded by default; if temporarily required for parser diagnostics, it must be tightly access-controlled, minimized and retained only briefly.

## 5. Job lifecycle

A device check follows this lifecycle:

1. **Accepted:** controller validates and durably stores the request.
2. **Queued:** request waits for an eligible gateway.
3. **Leased:** controller assigns it to one gateway with a time-limited lease.
4. **Sending:** gateway confirms that it is starting the SMS operation.
5. **Awaiting reply:** SMS send attempt has been made; response timer starts.
6. **Completed:** valid reply is correlated and parsed.
7. **Retry pending:** retry policy permits another attempt.
8. **Failed / timed out:** no safe retry remains or the request cannot complete.
9. **Cancelled:** client cancellation is accepted before the job reaches a non-cancellable send stage.

Every transition is recorded with a server timestamp. The controller is authoritative; a gateway's local state is recoverable evidence, not the global source of truth.

## 6. Critical SMS correlation rule

The most important technical risk is matching a 3030 reply to the exact device query that caused it.

Before enabling parallel SMS on a gateway, inspect real 3030 reply formats and the existing DevMonitor parser. Determine whether replies reliably include a device identifier or other unique correlation data.

- If the reply contains a reliable device identifier, the controller can support controlled parallel work subject to rate limits.
- If replies do not contain reliable correlation data, each gateway must serialize requests that could produce ambiguous replies. It must not send the next ambiguous query until the previous response is received or its safe timeout window expires.
- Late replies must not be assigned to a newer job simply because it is currently active.
- A response fingerprint and processed-message deduplication are required to prevent duplicate history rows.

This rule takes priority over throughput. Incorrect device attribution is worse than slower processing.

## 7. Multi-gateway scheduling

The controller should use a central scheduler.

Initial scheduling inputs:

- Gateway online/heartbeat status.
- SMS and telephony permission readiness.
- SIM/network readiness.
- Current active job count.
- Recent send failures and response timeouts.
- Configured per-gateway rate/concurrency limits.
- Gateway maintenance or operator-disabled state.

For the first prototype, use conservative assignment: one active SMS transaction per gateway until response correlation and carrier behavior are proven. Increase concurrency only after measured tests demonstrate safe matching and acceptable 3030/carrier behavior.

If a gateway disconnects:

- Its lease expires after a defined grace period.
- The controller marks the job recoverable.
- It does not immediately duplicate a potentially sent SMS.
- Reassignment follows an explicit uncertainty policy to avoid duplicate queries.
- A late result from an expired lease is rejected or quarantined unless it can be safely proven to belong to that job.

## 8. Retry, timeout and duplicate protection

- Use unique batch, job, attempt and lease identifiers.
- Client retries with the same idempotency key must return the original batch rather than create duplicate checks.
- Gateway result submissions must be idempotent.
- Separate SMS-send failure from reply timeout.
- Retry only according to the existing DevMonitor SMS Checker retry intent and a centrally configured policy.
- Avoid automatic retries when the controller cannot determine whether the SMS was already sent.
- Preserve the existing rule that timed-out devices are retried only after the first pass has finished, unless the user-approved behavior is deliberately changed later.
- Do not silently change the existing default wait periods or Partner menu wait period during gateway development.

## 9. Security model

- Use TLS for all network traffic.
- Use short-lived user access tokens and revocable gateway device credentials.
- Bind each gateway credential to one registered gateway installation.
- Store credentials in Android Keystore where supported.
- Enforce server-side authorization for every batch, device, job and result.
- Never trust a client-supplied user ID as proof of ownership.
- Rate-limit batch creation, gateway claims and result submissions.
- Keep secrets out of APK source, ZIPs, logs and Git history.
- Audit registration, assignment, cancellation, retry and result acceptance.
- Define retention and deletion rules before production rollout.

## 10. Data model (logical)

Minimum logical records:

- **User/Tenant:** authenticated owner and allowed device scope.
- **Gateway:** registered phone, health, capabilities, status and credential reference.
- **CheckBatch:** owner, client idempotency key, request time and aggregate status.
- **CheckJob:** batch, device, query profile, state, priority and result reference.
- **JobAttempt:** gateway, lease, send state, timestamps, timeout and attempt outcome.
- **CheckResult:** normalized response fields, parser version, source job and accepted timestamp.
- **AuditEvent:** security- and reliability-relevant state transition.

The gateway service should not replace DevMonitor's established local history databases. It returns verified results; each client continues to store its own authorized history according to existing application rules.

## 11. Backend technology decision

Do not assume that Google Drive is the gateway backend. Drive remains a backup/reporting integration and is not the job scheduler.

The backend must provide authenticated APIs, durable transactional job state, secure device registration, rate limiting and observable operations. Firebase services may be evaluated because the project already uses Firebase, but the final choice must account for server-side authorization, reliable job leasing, operating cost, maintainability and offline recovery.

No paid infrastructure should be provisioned until the design, expected workload and cost are reviewed.

## 12. Implementation sequence

### Phase 1 — Source and protocol audit
- Inspect the current SMS request builder, SMS receiver, parser, retry queue and history persistence in the actual source ZIP.
- Document the exact 3030 request and reply formats.
- Confirm whether a reply contains an unambiguous device identifier.
- Record current SMS timing and Partner-specific behavior.

### Phase 2 — Gateway phone proof of concept
- Build a separate Android Gateway Phone App.
- Register one test phone.
- Receive one assigned job.
- Send one real query to 3030.
- Receive and parse its reply.
- Submit the result to a test controller.
- Test screen-off, process interruption, network loss and duplicate broadcasts.

### Phase 3 — Controller prototype
- Implement authenticated registration, durable queue, job leases, status and result submission.
- Test with two gateway phones.
- Verify no cross-user result access and no duplicate device results.

### Phase 4 — Gateway Edition integration
- Add the separate Gateway Android build flavour.
- Verify its final merged manifest contains no SMS send/receive permissions and its code path cannot invoke native SMS.
- Connect SMS Checker to the controller behind a gateway-specific transport interface.
- Preserve existing history and reporting behavior.

### Phase 5 — Scale and release validation
- Test three or more phones, disconnect/reconnect, queue recovery, late replies, duplicate replies and gateway failover.
- Measure throughput and failure rates before increasing concurrency.
- Prepare separate Full and Gateway release artifacts.
- Keep Full edition as the direct-SMS reference and regression-test it.

## 13. Acceptance criteria

The gateway foundation is not ready for release until:

- A Gateway Edition client can submit a batch without SMS permissions.
- Two or more gateway phones can process distinct assigned jobs.
- Every accepted result is attributed to the correct device and user.
- Duplicate submissions and duplicate SMS broadcasts do not create duplicate history entries.
- Interrupted gateway work recovers without unsafe duplicate sending.
- Client history is populated using the established daily result rules.
- Full edition still sends, receives, retries and stores SMS results as before.
- The Gateway Edition's final merged manifest and dependency manifests contain no SMS permissions.
- Gateway credentials can be revoked and user data remains isolated.

**Guiding engineering principle:** correctness and isolation first; parallel throughput second.
