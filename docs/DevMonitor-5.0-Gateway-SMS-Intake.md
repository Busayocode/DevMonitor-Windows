# Gateway Android SMS intake — guarded receiver foundation

**Branch:** `devmonitor-5.0-gateway-android-download`  
**Date:** 15 November 2026  
**Status:** Implemented in source; final integrated CI validation is running. No live SMS test or controller result submission is claimed.

## What this slice adds

- An Android `SMS_RECEIVED` broadcast receiver protected by the platform `BROADCAST_SMS` permission.
- A strict sender policy: after presentation separators are removed, the complete originating address must equal the short code `3030`. Prefix/suffix matches and ordinary phone numbers are rejected.
- Multipart messages from the same originating address are assembled in timestamp order.
- Only 3030 messages are copied into a private app SQLite inbox. Unrelated personal SMS are ignored and not stored.
- Inbox rows include a SHA-256 duplicate fingerprint, receive timestamp, available Android subscription ID, message body and an explicit `RECEIVED_UNASSIGNED` state.
- The inbox is bounded to the newest 200 rows to prevent unbounded storage growth.

## Safety boundary

This receiver is intake only. It does **not** parse a 3030 result, associate it with a device/partner/job, mark a transaction complete, retry, or submit anything to the controller. Messages remain unassigned until the transaction gate and parser are wired and validated. It does not transmit SMS.

The 3030 protocol does not echo the queried device or partner code. Therefore a successful parse alone is not correlation. The existing worker-core gate remains the required authority: one unresolved transaction per physical Gateway phone, duplicate suppression, post-reply drain, and quarantine after timeout/late reply. No next request may start while the channel is ambiguous.

## Next work

1. Wire the durable inbox into the transaction gate and a versioned 3030 parser.
2. Verify sender, selected subscription identity, active transaction, receive timestamp and duplicate fingerprint before any result is accepted.
3. Persist accepted/unassigned/quarantined decisions and process-restart recovery.
4. Add explicit selected-subscription SMS sending only after the controller accepts `SENDING`, with SIM READY revalidation immediately before each send.
5. Keep heartbeat SMS/telephony readiness false until all runtime checks and real-device tests pass.

## Validation still required

- Unit tests for sender normalization, parser formats, duplicate/unsolicited/late replies and transaction-state restoration.
- Android CI build.
- Physical dual-SIM tests covering Glo auto-selection, manual override, SIM removal/lock, sender delivery and app process death.
- A controlled 3030 exchange using an approved test SIM before any live queue is enabled.


## Parser and gated inbox processing — 15 Nov 2026

- Added a conservative parser for the documented seven numeric report fields: Today's SIMReg Count, MTD SIMReg Count, Active, Bar 48, Bar 96, Duplicates and Monthly Target. It rejects missing, duplicate, negative or non-numeric fields rather than guessing.
- Date/time is retained as source text; the exact carrier date format is not normalized yet.
- Added parser unit tests for valid, incomplete, duplicate, empty, negative and non-numeric messages.
- Added durable inbox decision updates and a processor that submits each valid 3030 message to the existing per-phone transaction gate. Messages become accepted-pending-controller, unassigned, duplicate or quarantined; malformed replies are fingerprinted and remain unassigned.
- The processor is not wired to the receiver or active worker yet. It must only be invoked after the worker restores its durable channel state and configures an evidence-based post-reply quiet period.
- Accepted payloads are staged locally only. No controller submission, outbound SMS, automatic retry or queue advancement is enabled.
- Parser field labels are based on the supplied protocol description. A redacted real carrier response is still required to validate formatting and any optional labels before production parsing is enabled.


## Android SMS send-result callback — 15 Nov 2026

- Added a private, non-exported SMS_SENT result receiver. Each callback PendingIntent is package-bound and uniquely keyed by the durable attempt ID.
- The receiver records Android's send outcome against the matching attempt and job in one SQLite transaction.
- A successful Android send-result advances SENDING to AWAITING_REPLY; a failed result advances the attempt to SEND_FAILED and job to FAILED. Duplicate, late, unknown, or out-of-order callbacks are ignored unless both durable records are still in SENDING.
- No automatic resend, queue advancement, controller submission, or reply correlation is performed by this callback.
- Important distinction: RESULT_OK means Android reported the SMS send operation successful; it does not prove 3030 received or processed the query.
- The callback is implemented but not yet wired into a controller-authorized live worker path. Runtime delivery, process-death recovery, and dual-SIM physical testing remain outstanding.
