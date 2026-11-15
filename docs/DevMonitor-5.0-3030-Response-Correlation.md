# DevMonitor 5.0 — 3030 Response Correlation

## Evidence from real 3030 messages

The supplied 12 November 2026 SMS conversation screenshot shows the normal 3030 exchange. The outgoing query contains a short device code, for example `GLO-GD-273M` and `GLO-GD-342F`. The corresponding incoming 3030 result contains:

- Date/time
- Today's SIMReg Count
- MTD SIMReg Count
- Active
- Bar 48
- Bar 96
- Duplicates
- Monthly Target

The incoming result does **not** echo the queried device code. The user confirms that this same response structure is used for company-device queries and partner-code queries. Therefore the response body cannot independently identify its target device or partner.

## Consequence for the current implementation

The current live checker associates the first valid response received after a send with the currently active device. Since the response body has no target identifier, a delayed response to a previous query can be mistaken for the response to the next query. Parsing success alone is not sufficient correlation.

This is a confirmed protocol limitation, not merely a parser defect. Do not attempt to solve it by extracting a device code from a response that does not contain one.

## Required safety model

### 1. One outstanding request per physical gateway phone/SIM

- A gateway phone may have at most one unresolved 3030 transaction.
- Do not pipeline or concurrently send queries through the same SIM.
- Different gateway phones may each have one active transaction, subject to conservative rate-limit testing.

### 2. Bind response only within an active transaction

For each transaction, record a local transaction ID, target device/partner, gateway phone/SIM identity, send timestamp, and SMS-store baseline. Accept a parsed 3030 response only while that transaction is the sole active transaction for that phone and the response is inside its configured response window.

The transaction ID is local metadata; it is not present in the carrier response and must never be represented as a server-provided correlation ID.

### 3. Timeout means an ambiguous channel

If a transaction times out, its response may still arrive later. Because the body has no target code, immediately starting another query on that same phone would create an attribution risk.

- Mark the phone's SMS channel as **AMBIGUOUS / QUARANTINED**.
- Do not assign subsequent 3030 responses to another device while the channel remains ambiguous.
- Do not automatically continue the same phone's queue after timeout.
- Other healthy gateway phones may continue their own queues.
- A recovery policy may release the phone only after an explicit drain/recovery procedure and a sufficiently long quiet period. The quiet period is a risk-reduction measure, not mathematical proof that no late response remains.
- If a strict guarantee is required, keep that phone paused until an operator explicitly resolves the ambiguous transaction.

### 4. Unsolicited and duplicate replies

A 3030 message received when there is no active transaction must be stored as unassigned diagnostic evidence, not attached to the next device. After a transaction completes, additional messages must not silently overwrite its result or be assigned to the next target.

### 5. Retry behavior

A retry is a new transaction only after the previous transaction has been safely resolved or the channel has passed the approved recovery procedure. Preserve the existing Full edition's current retry and timeout behavior until a separately approved implementation and regression plan is ready.

## Gateway implementation implications

- Keep transaction state isolated per physical gateway phone/SIM.
- The controller must receive an explicit result state such as `SUCCESS`, `NOT_FOUND`, `TIMEOUT_AMBIGUOUS`, or `UNASSIGNED_MESSAGE`; it must not treat a timeout as an ordinary failed query that can immediately be followed by another query on that same phone.
- Gateway phones must report their health/quarantine state to the controller.
- Controller scheduling should dispatch work only to healthy, available gateway phones.
- Persist the association between a completed transaction and its target so the result cannot be reassigned after app restart.

## Validation required before implementation

1. Collect redacted examples for company and partner queries (the user confirms the same response structure; preserve samples without personal numbers).
2. Measure normal response latency and late-response behavior over repeated real queries.
3. Test timeout followed by a deliberately delayed 3030 response.
4. Test duplicate responses and unsolicited 3030 messages.
5. Verify that one quarantined gateway phone does not stop healthy phones.
6. Verify persistence across process death and device reboot.

## Scope and status

This document records the protocol evidence supplied by the user and defines a safe design direction for DevMonitor 5.0. It does not change the existing Full edition, send SMS, remove permissions, or claim that the proposed recovery timings have been validated. No production SMS behavior is changed by this documentation commit.
