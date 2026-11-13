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


## 14. Shared gateway data service, daily cache and cross-client history

The gateway is not merely an SMS relay. It is also a shared, authenticated data service for the DevMonitor ecosystem. It maintains the authoritative gateway-side record of checks it has completed and makes authorized history available to clients, including clients that did not initiate the original check.

### 14.1 Daily result storage

For every successfully parsed and safely correlated device check, the service stores a normalized result with at least:

- Canonical device/partner code (the full DevMonitor identity, not only the shortened SMS destination).
- Calendar date of the check, using the agreed service/business timezone.
- Exact check timestamp, including timezone/UTC offset.
- Today's SIMReg Count, MTD SIMReg Count, Active, Bar 48, Bar 96, Duplicates and Monthly Target when present.
- Gateway ID, source job/attempt ID and parser version.
- Result status and data-quality/validation metadata.

The daily history rule is **one current/latest accepted value per device per day**, not one row per SMS attempt. When a later valid check for the same device arrives on the same day, it updates that day's current value and its checked-at timestamp. The service may retain a separate restricted audit/attempt log for troubleshooting, but that log must not be confused with the user-facing daily history table.

A late response must never overwrite a newer daily value merely because it arrived later. The acceptance policy must compare transaction identity and timestamps, and reject/quarantine responses that cannot be safely attributed. Store both the time the 3030 result reports (if supplied) and the time the gateway received it; do not assume these are identical.

### 14.2 Shared history for clients that did not request the check

History belongs to the shared gateway service, not to the phone that initiated a request. Therefore an authorized DevMonitor client can request the history for a device even if that client has never requested an SMS check for that device.

Proposed read contract:

```http
GET /v1/client/devices/{device_id}/sms-history?from=YYYY-MM-DD&to=YYYY-MM-DD
Authorization: Bearer <user-access-token>
```

The controller returns the authorized daily records, including the last checked time and whether the value is the latest available for that day. The client should render this in the existing SMS Checker History table conventions.

This is shared service history, **not public history**. Before returning a record, the server must verify that the authenticated user/tenant is entitled to view that device under the configured company/partner access rules. A device code supplied by a client is not authorization.

### 14.3 Cached value and fresh-check request are separate operations

The client should be able to obtain the last available value immediately while independently requesting a fresh 3030 check.

Suggested flow:

1. Client requests the latest cached result for a device.
2. Controller returns the last accepted value, its original checked-at timestamp, and a clear freshness label.
3. Client may then submit a separate fresh-check request.
4. The fresh request enters the shared queue and returns a job ID.
5. The existing cached result remains visible while the new job is queued/running.
6. When a new valid result is accepted, the service updates the cache and that day's latest history record, then notifies/presents the new checked-at time.

Suggested cache read:

```http
GET /v1/client/devices/{device_id}/sms-latest
Authorization: Bearer <user-access-token>
```

The response should distinguish at least:
- `CACHED`: last accepted result; not freshly checked by this request.
- `FRESH`: result produced by the requested job.
- `NO_DATA`: no accepted gateway result exists yet.
- `STALE`: a cached result exists but is older than the configured freshness target.

Freshness must be based on the stored checked-at timestamp, not on when the client downloaded the cache. The UI must never make cached data look like a live response. A cache hit does not silently create an SMS job; fresh checking is an explicit separate request.

### 14.4 Shared multi-gateway work queue

All fresh-check requests enter one durable controller queue. Gateway phones do not maintain competing independent queues and do not choose arbitrary device work.

- Gateway X, Y and Z register as separate workers.
- Each worker claims the next eligible job when it is ready.
- The controller assigns work according to availability, health, current workload, configured SIM/carrier rate limits and fairness policy.
- Initially, each physical gateway phone/SIM may hold only one unresolved SMS transaction at a time because 3030 replies do not echo the queried device code.
- While X is waiting for its 3030 response, Y and Z can process other jobs independently.
- When X safely completes or resolves its transaction, it claims another queued job.
- Requests are queued when all eligible gateways are busy or unavailable.
- The controller prevents two gateways from needlessly checking the same device at the same time. It may coalesce equivalent pending fresh-check requests into one job when access scope, query profile and timing rules permit, then make the accepted result available to all authorized requesters.
- The controller applies fair scheduling so one user or tenant cannot monopolize all gateway capacity. Priority rules, if introduced, must be explicit and auditable.

The scheduler must not interpret a timed-out ambiguous transaction as free capacity. A gateway with an unresolved/late-response risk is quarantined from new SMS work, while healthy gateways continue processing their own assigned jobs.

### 14.5 Cache and queue data model additions

Add the following logical records/fields to the data model:

- **DeviceDailyLatest:** canonical device ID, business date, latest accepted normalized result, checked-at timestamp, received-at timestamp, source job/attempt, gateway ID and parser version. Enforce a unique key on (authorized scope, canonical device ID, business date).
- **DeviceLatestSnapshot:** optional materialized pointer/value for fast latest-result reads, derived only from accepted daily records.
- **GatewayQueue:** durable queued jobs with idempotency/coalescing key, owner/scope, target device, query profile, priority, enqueue time and state.
- **GatewayLease:** assigned gateway, job/attempt, lease token, lease expiry and worker heartbeat.
- **ResultAccess/AuditEvent:** record authorized history/cache reads and result distribution where needed for accountability.

Daily history retention must follow the product's approved retention policy. The gateway's central cache may serve multiple clients, but authorization and tenant boundaries must be enforced on every read and write. Do not copy a result into a user's scope unless that user is entitled to the underlying device data.

### 14.6 Additional acceptance criteria

The shared data-service milestone is not complete until tests prove that:

1. A successful result is stored with the correct canonical device ID and exact checked-at timestamp.
2. A second valid check on the same day updates that day's latest value without creating a second user-facing daily row.
3. A client that did not initiate the check can read the history if, and only if, it is authorized for that device.
4. A client can display cached data immediately and separately request a fresh check.
5. Cached results remain visibly timestamped and are never labelled as fresh.
6. Three gateway phones can consume one shared queue; while one waits for 3030, the others can process their own jobs.
7. When all gateways are busy, new requests remain queued and are not lost.
8. Duplicate equivalent requests can be safely coalesced without crossing authorization scopes.
9. A quarantined gateway receives no new SMS job, while healthy gateways continue.
10. Restarting the controller or a gateway does not lose daily history, queued work or active-transaction safety state.

## 15. Personal gateway ownership and priority

A standalone user who has another eligible DevMonitor phone may register that phone as a **Personal Gateway**. This is an ownership relationship between the authenticated account and a gateway installation; it is not a separate private server.

### 15.1 Pairing and selection

- The owner signs into DevMonitor on the second phone and chooses Register as Personal Gateway.
- The controller pairs the installation to the authenticated owner and records an immutable owner-scope association.
- From the main client, the owner selects one of their paired, healthy gateway phones as the preferred personal gateway for fresh checks.
- The client may display only gateways the authenticated owner is entitled to select. It must not accept an arbitrary gateway ID as proof of ownership.
- A user may later change the preferred gateway or revoke/unpair the phone. Revocation stops new assignments and safely resolves any active transaction before credentials are invalidated.

### 15.2 Personal fast lane

- Jobs submitted by the owner with their selected personal gateway are marked with a server-validated preferred gateway.
- That gateway claims its owner's queued jobs before shared-pool work, even if other users' shared jobs were enqueued earlier.
- Other workers leave those jobs reserved while the preferred gateway is healthy and READY. If it becomes OFFLINE, NOT_READY or QUARANTINED, the controller may fail over the job to another eligible shared worker.
- Personal priority never interrupts an already-started SMS transaction. If the phone is processing another user's query, it finishes or safely resolves that 3030 transaction; the owner's request is the next job claimed by that phone.
- The selected phone remains limited to one unresolved 3030 transaction at a time. This preserves response correlation because the reply does not echo the requested device code.

### 15.3 Optional shared-pool contribution

The owner chooses one of two modes:
- **Personal only:** the phone serves its owner and does not claim other users' jobs.
- **Personal + shared pool:** the phone serves owner jobs first and accepts shared jobs whenever it has no owner work waiting.

The owner may switch pool participation off at any time. Turning it off prevents new shared assignments but does not interrupt an SMS transaction already in progress. Owner jobs remain eligible.

### 15.4 Shared queue fairness

Shared-pool scheduling must rotate across authenticated user/tenant scopes rather than drain one user's entire batch first. For example, with User A's 50 jobs and User B's 10 jobs queued, workers should take turns across scopes (A1, B1, A2, B2, and so on) while preserving FIFO order within each scope. This is a scheduling policy, not a promise of equal total completion time: gateways claim jobs dynamically as they become ready, and real completion depends on SMS response times and gateway health.

Personal jobs reserved for a healthy paired gateway do not block unrelated users' shared work. Personal jobs become eligible for shared failover only when the preferred gateway is unavailable under the controller's health policy.

### 15.5 Security and audit requirements

- Ownership is derived from authenticated server identity, not a client-supplied scope string.
- A user cannot nominate, inspect, or reserve another user's personal gateway.
- Gateway credentials remain installation-bound and revocable.
- Audit personal gateway pairing, owner selection, pool opt-in/out, failover and unpairing.
- Shared-pool jobs must not expose the owner's private cached results or device entitlements to the gateway owner.

## 16. Personal gateway acceptance criteria

1. A user can pair a second eligible DevMonitor phone to their own account.
2. The owner can select one of their paired gateways as the preferred gateway.
3. Owner jobs are claimed ahead of older shared-pool jobs by that gateway.
4. The gateway completes an already-started SMS transaction safely before claiming an owner job.
5. The owner can choose personal-only or personal-plus-pool participation.
6. A personal-only gateway never claims another user's job.
7. A personal-plus-pool gateway serves owner work first and uses idle capacity for shared work.
8. Other tenants receive round-robin turns in the shared queue instead of waiting for one large batch to drain.
9. When a personal gateway is unavailable, an eligible pool gateway can take the job without weakening attempt/lease correlation.
10. No account can register or select another account's gateway as its personal gateway.

