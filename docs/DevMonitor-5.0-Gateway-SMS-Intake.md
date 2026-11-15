# Gateway Android SMS intake — guarded receiver foundation

**Branch:** `devmonitor-5.0-gateway-android-download`  
**Date:** 15 November 2026  
**Status:** Implemented in source; CI validation pending. No live SMS test or controller result submission is claimed.

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
