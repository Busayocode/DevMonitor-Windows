# DevMonitor 5.0 — Phase 1 Source Audit (v340 Source Snapshot)

**Audited artifact:** Android source ZIP captured by successful GitHub Actions run #173  
**Run:** 43373118893  
**Build branch:** trial/direct-google-account-lan-sync  
**Source ZIP:** DevMonitor-An-v340-4.7.0-TARGET-SMS-CHECKER-DAILY-HISTORY-ORIGINAL-CARD-LEGIBILITY.zip  
**Audit branch:** devmonitor-5.0-foundation  
**Scope:** SMS request construction, send callbacks, inbound receiver, parser, live matching, inbox recovery, retry behavior and daily history.

**Version caveat:** The audited source input is v340. Run #173 produced a v349 APK after workflow overlays. This document is not a claim that every v349 overlay has been inspected.

## 1. Confirmed source architecture

The Android source has these distinct SMS components:

- `MainActivity.kt`: constructs and sends 3030 SMS requests, coordinates the SMS Checker run, monitors local captures and performs optional inbox recovery.
- `SmsReceiver.kt`: receives Android `SMS_RECEIVED` broadcasts and stores inbound message bodies in DevMonitor's local SMS capture table.
- `SmsParser.kt`: parses captured message bodies into SIM registration result fields.
- `SmsDeliveryTracker.kt`: tracks carrier sent/delivered callbacks for outbound messages.
- `SmsCheckerDailyHistoryStore.kt`: persists completed per-day results separately from the transient live SMS capture path.

The app sends to short code `3030`. The source documents that 3030 accepts a short device identifier form rather than the full hierarchical ID.

## 2. Exact request construction

The default destination is `3030`. The request normalizer converts a full GLO code to its short transmission form. Example from source:

`GLO-GD-OGN-da3-3GOGN047-666D` → `GLO-GD-666D`

The original long identifier remains the app's database/UI identity. The send path uses Android `SmsManager`, checks `SEND_SMS`, and uses the selected subscription when available, otherwise the default SMS manager.

The send helper can repeat a query up to the configured repeat count (clamped to 1–6). Each send attempt also has immediate delivery-report retries (up to two additional attempts). Sent/delivered callbacks are useful transport diagnostics, but a carrier callback is not proof that 3030 returned a valid result.

**Gateway requirement:** Extract this exact request builder into shared code. Full and Gateway must not independently reconstruct the outgoing message string.

## 3. Inbound broadcast and capture

`SmsReceiver` listens for `android.provider.Telephony.SMS_RECEIVED`, joins multipart message segments, takes the originating address from the first segment, and immediately stores the message in DevMonitor's local SMS capture table.

The normal live receive path is therefore broadcast-based. It does not need to query the Android inbox for each response.

The receiver captures incoming SMS generally; it does not attach a job ID or prove which request caused the message. The live matching logic is in `MainActivity.kt`.

## 4. Critical live response-matching finding

The parser has `SmsParser.extractCode(raw)`, which attempts to extract a GLO-GD/GLO-DD/GLO-FD code or a labelled Agent Code / Code / Device Code. However, the current live SMS Checker loop does **not** call `extractCode` before accepting a response.

For each device, the loop:
1. Captures the current local SMS-store ID as a baseline.
2. Records a query attempt and send timestamp.
3. Sends the SMS.
4. Watches for new captured messages from a sender whose last four digits match the configured destination.
5. Accepts the first message after the send start time whose body parses as SUCCESS or NOT_FOUND.
6. Assigns that parsed response to the device currently being queried.

The same general first-valid-response pattern is used in the Partner summary request path.

This means the current live path relies primarily on sequential execution and timing, not explicit device identity inside the response. A late reply from a previous request could potentially be accepted for the next device if it arrives during the next device's response window. The inbox recovery path has more careful matching logic, but it is a separate recovery path and does not fix the live path.

**This is the most important issue to solve before gateway scaling.** A gateway must not assume that the currently active request owns the next valid 3030 response.

### Required correlation design

- Inspect actual redacted 3030 reply samples from different device types and Partner queries.
- Determine whether responses reliably contain a unique identifier matching the requested device or partner.
- If they do, validate the returned identifier against the assigned job before accepting the result.
- If they do not, use a strict one-in-flight transaction per gateway phone, with a late-response quarantine/quiet period and explicit handling of ambiguous replies. Do not simply attach the next valid message to the next job.
- Do not increase same-phone concurrency until the correlation mechanism is proven.
- Multi-phone concurrency is a separate question: each phone has its own SIM/inbox, but the 3030 service's rate limits and reply routing still need testing.

## 5. Existing timing and queue behavior

The source sets the standard Device Monitor response wait to 120 seconds and the Partner path to 180 seconds.

The Device Monitor run processes its initial device list sequentially, then drains Rechecks added while the run is active. It records a query attempt before sending, stores completed outcomes immediately, and continues after timeout/send failure. The retry UI is a separate retry pass over unresolved records; the current loop does not automatically retry a timed-out device immediately before moving on.

The Partner summary flow also sends one request and waits up to three minutes for a valid response.

These existing timing defaults and retry semantics must be preserved during the first gateway integration. Any future late-response quiet period or gateway-specific timeout adjustment must be measured and documented rather than silently changing the user's established workflow.

## 6. Parser and normalized result

`SmsParser` recognizes a successful response when both `Todays SIMReg Count` and `MTD SIMReg Count` appear. It extracts Today, MTD, Active, Bar 48, Bar 96, Duplicates and Monthly Target. It separately recognizes an "agent code does not exist" response.

The parser can extract an identifier from some response formats, but the live loop currently does not use that capability to validate the active device.

The Gateway phone should use the same parser and normalized result model as Full edition. The Gateway Edition client should save verified normalized results through the existing daily history store.

## 7. READ_SMS is recovery, not the primary receive mechanism

The source uses `READ_SMS` for optional system inbox reconciliation. `SmsInbox.systemInbox()` checks the permission and queries Android's SMS Inbox provider.

The primary live response path is the SMS_RECEIVED broadcast and DevMonitor's own local capture table. This supports investigating READ_SMS removal in a separate trial, but does not prove that removal is already safe. A replacement recovery design and regression testing are still required.

The Full edition remains unchanged.

## 8. Gateway architecture implications

Existing SMS orchestration is embedded substantially in `MainActivity.kt`, rather than exposed as a clean transport abstraction. The safe design is to extract shared business/protocol logic and keep transport-specific implementations separate:

- Shared request builder and device-ID normalization.
- Shared response parser and normalized result model.
- Shared response validation and duplicate fingerprinting.
- Direct SMS transport for Full edition.
- HTTPS gateway transport for Gateway edition.
- Gateway-phone SMS transport for dedicated gateway devices.
- Existing client-side daily history remains the owner of local history.

Do not copy the whole MainActivity SMS loop into the gateway app. Extract small, testable components and preserve established behavior.

## 9. Outstanding questions

The source-only review cannot establish:

1. Whether every real 3030 reply includes a unique device/partner identifier.
2. Whether 3030 accepts concurrent queries from multiple SIMs without throttling or response-routing surprises.
3. The safe quiet period for late replies after a timeout.
4. Whether workflow overlays between v340 source input and v349 release APK alter SMS matching or retry behavior.
5. The exact persistence/recovery behavior if Android kills the process between the SMS send and reply.

These require redacted real reply samples, overlay inspection and controlled tests using the user's existing working phones. No test SMS was sent as part of this audit.

## 10. Safety and preservation

- No source code was modified during this audit.
- No Full edition behavior was changed.
- No production branch was changed.
- No SMS permission was removed.
- No SMS was sent.
- This is a source-level audit of the v340 input ZIP, not a live-network test against 3030.

## 11. Next development step

Before implementing a gateway phone or controller, complete a focused correlation review:

1. Obtain redacted samples of successful company-device, partner-device and partner-summary replies.
2. Determine if each response contains an identifier and whether it matches the short or long DevMonitor identity.
3. Inspect the workflow overlay files applied to the v340 input to produce v349, specifically any SMS-related modifications.
4. Design and test a response-correlation state machine with late-message quarantine and idempotent result submission.
5. Only then implement the smallest gateway-phone proof of concept.

**Preliminary conclusion:** A multi-phone gateway remains technically plausible. The source already has a broadcast-based receiver and reusable parser. However, the current live checker assigns the first valid response from 3030 to the currently active device without validating a response device identifier. Correct correlation—not phone count or raw SMS throughput—is the first engineering problem to solve. Initial gateway scheduling should be one active transaction per phone, with strict late-response handling, until the reply format and routing behavior are verified.
