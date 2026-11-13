# DevMonitor 5.0 — Multi-Gateway Queue, Wait Estimates, Cancellation and Analytics

**Branch:** `devmonitor-5.0-foundation`  
**Status:** Product/architecture requirements recorded; implementation and live operation not yet verified.  
**Scope:** Gateway controller and Gateway/Full edition client integration. This document does not authorize deployment, paid infrastructure, changes to the Full edition release, or merging into `main`.

## 1. Core model: one logical queue, multiple physical gateways

DevMonitor must present one logical shared workload across all eligible, online Gateways. It must not treat each Gateway as an isolated queue with no awareness of the others.

The controller is the durable source of truth for requests, device jobs, assignments, progress, cancellation and queue estimates. Each physical Gateway advertises health/readiness and claims only work assigned to it through the controller.

A request remains a single user-owned batch containing individually tracked device jobs. This preserves request identity and allows per-request progress, cancellation and analytics while devices are processed individually.

### 1.1 Scheduling and distribution

- Preserve a global arrival sequence for every request and its device jobs.
- Allocate pending work across all healthy, eligible Gateways so multiple Gateways can process in parallel.
- Respect personal-Gateway ownership/preference and explicit shared-pool opt-in rules already defined by the controller.
- Use request-level FIFO ordering: older request batches are allocated before later batches on each Gateway queue. Do not interleave the devices of a later request ahead of unfinished devices in an earlier request assigned to the same Gateway.
- A Gateway owns one request batch at a time and processes that batch's devices in request order. Other Gateways process other request batches concurrently. This supersedes the earlier per-device tenant/requester round-robin dispatch rule; tenant membership, authorization and data isolation remain mandatory.
- Rebalance **unstarted** work when a new eligible Gateway becomes available or an existing Gateway becomes unavailable.
- Do not hard-code alternating assignment as the only algorithm. Alternating distribution is a simple illustration; actual assignment should account for Gateway health, capacity, eligibility, owner preference and outstanding workload.

### 1.2 Example: a second Gateway joins

Suppose one Gateway initially has five users' requests in the shared workload, ordered User 1, User 2, User 3, User 4, User 5. When Gateway B becomes available, the scheduler can redistribute the unstarted requests, for example:

- Gateway A: User 1, User 3, User 5.
- Gateway B: User 2, User 4.

This illustrates the intended round-robin request allocation when the two Gateways have comparable load. Actual assignment also respects Gateway health, personal ownership/preference, shared-pool opt-in and any request already in progress.

If Gateway B later becomes unavailable, its unstarted work returns to the scheduling pool and can be claimed by Gateway A or another healthy Gateway. Queue positions and estimates are recalculated.

### 1.3 Safe reassignment

- A job that has not begun transmission can be safely released and reassigned.
- A job whose SMS send was acknowledged is potentially ambiguous if the Gateway disappears. Do not blindly send it again from another Gateway.
- Preserve the existing attempt lease, reply-correlation, quarantine and operator-resolution safeguards. An ambiguous attempt must be resolved safely before another Gateway retries that device.
- Completed results remain attached to the original request and are never erased by reassignment.
- Use idempotent request/job identifiers and duplicate-result protection so a late reply or repeated submission cannot corrupt results.

## 2. User-visible queue position and progress

The user must be able to understand the workload ahead of their own request without seeing other users' identities, device IDs, phone numbers or results.

For each request, show as applicable:

- Total devices submitted.
- Devices completed, currently processing, waiting and remaining.
- Number of devices ahead of this request in the effective scheduling order.
- Current assigned Gateway status, or a neutral status such as "being reassigned" while assignment changes.
- Whether the request is queued, processing, partially complete, cancellation requested, cancelled or complete.

Queue position is dynamic. When Gateways join/leave, requests are redistributed and the displayed position is refreshed. Do not expose other users' private request details; aggregate counts are sufficient.

Because multiple Gateways work in parallel, "devices ahead" means eligible pending device jobs the scheduler expects to serve before this request under the current priority/fairness policy. It must not be presented as a fixed global ordinal that ignores parallel processing or reassignment.

## 3. Estimated start and completion

Show two separate estimates:

1. **Estimated start:** approximate time until the first device in the user's request is expected to begin processing.
2. **Estimated completion:** approximate time until all non-cancelled devices in the request are expected to finish.

Estimates must use observed Gateway performance and current state, including:

- Pending device count and ordering ahead of the request.
- Number of healthy eligible Gateways and their recent throughput.
- Per-Gateway response-time history and the relevant query wait profile.
- Active sends, timeouts, retry policy and recent failure rate.
- Gateway health/heartbeat and any reassignment in progress.

Recalculate estimates when requests arrive/cancel, devices complete or time out, a Gateway joins/leaves, or a request is reassigned. Present estimates as approximate ranges where uncertainty is high; never promise a precise completion time. Do not change existing SMS timeout/retry semantics merely to improve estimates.

## 4. Request cancellation

A user may cancel their own request.

- A wholly queued request can be cancelled immediately.
- For a request already processing, cancellation stops its remaining not-yet-started device jobs.
- If an SMS attempt is already in flight, finish or safely resolve that attempt according to the existing send/lease/ambiguity rules; cancellation must not create unsafe duplicate sends.
- Keep completed device results and the request's audit history.
- Do not interrupt or cancel another user's request.
- Recalculate queue positions and estimates after cancellation.
- Record cancellation actor, timestamp, reason if supplied, number completed, number skipped and any in-flight attempt disposition.

Cancellation must be idempotent so repeated taps or network retries do not produce inconsistent states.

## 5. Gateway analytics

Provide an administrator-facing Gateway Analytics view with aggregate, time-filterable metrics:

- Requests submitted and devices requested.
- Devices completed, successful, timed out, failed, skipped and cancelled.
- Average and percentile queue wait time.
- Average and percentile per-device processing/response time.
- Estimated-versus-actual start and completion accuracy.
- Queue depth and waiting-device count over time, including peak periods.
- Cancellation count/rate and cancellation position/wait context.
- Gateway uptime, downtime, health transitions and utilization.
- Work assigned/completed per Gateway and redistribution/failover events.
- Retry and ambiguous-attempt/quarantine counts.
- Request volume and workload distribution by tenant/requester, subject to administrator authorization and privacy controls.

Keep an auditable event history for request submission, assignment, reassignment, claim, send acknowledgement, result, timeout, cancellation and Gateway health changes. Retain analytics at aggregate level without exposing one tenant's private device data to another.

## 6. Full edition integration

The Full edition must be able to use an authorized dedicated Gateway, in addition to its existing direct-SMS capability.

- Users with large device collections can route checks through their dedicated Gateway to increase throughput.
- The dedicated Gateway remains owner-controlled; shared-pool use requires explicit opt-in and existing authorization rules.
- The Full edition should show the same request progress, queue position, estimates and cancellation state whether work is routed through a dedicated Gateway or the eligible shared pool.
- Preserve direct-SMS fallback where appropriate, but never allow direct and Gateway paths to process the same device attempt concurrently.
- Keep Full edition and Gateway edition as alternative app editions, not side-by-side installations, unless separately approved.

## 7. Required tests before release

1. One Gateway with multiple users: queue ordering, privacy and correct "devices ahead" counts.
2. Add a second Gateway while requests are pending: safe redistribution and parallel processing.
3. Remove/disable a Gateway: unstarted jobs fail over and estimates recalculate.
4. Gateway disappears before send acknowledgement versus after send acknowledgement.
5. Late replies, duplicate replies, stale leases and duplicate client submissions.
6. Cancel queued, partially processed and in-flight requests.
7. Verify cancellation never affects another user's request.
8. Compare estimates with actual wait/completion under low, medium and high load.
9. Verify fairness across tenants/requesters while preserving queue-order expectations.
10. Full edition uses its dedicated Gateway without duplicate direct-SMS processing.
11. Verify analytics totals reconcile with request/job/event records and enforce tenant privacy.
12. Restart controller and Gateway processes during each important lifecycle stage; durable queue and audit state must recover.

## 8. Implementation and deployment guardrails

- This is a requirements/design record, not proof that the features have been implemented.
- Extend the existing controller schema and scheduler rather than introducing a competing queue implementation.
- Keep the V340 Full edition source baseline and release behaviour untouched unless explicitly authorized.
- Keep work isolated on `devmonitor-5.0-foundation`; do not merge to `main` without explicit approval.
- No production hosting, paid service, billing attachment, database provisioning or deployment without explaining costs/requirements and receiving explicit approval.
- Do not commit Firebase credentials, real `google-services.json`, gateway installation tokens or other secrets.
