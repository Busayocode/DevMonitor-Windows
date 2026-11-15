# Gateway Worker Core — Phase 4A

**Branch:** `devmonitor-5.0-gateway-android-download`  
**Status:** Pure Kotlin transaction-safety module plus Android SQLite persistence, guarded 3030 intake/parser, subscription-pinned sender and SMS_SENT callback are now present. Gateway workflow #117 passed Android build and source packaging on 15 Nov 2026. The foreground service remains heartbeat-only; no live controller-authorized SMS execution or physical SIM test has been completed.

## Why this is the first worker slice

The real 3030 reply does not echo the queried device/partner code. Therefore the worker must protect the phone's single SMS channel before adding a long-running Android service or enabling SMS transmission. The core is deliberately separate from the ordinary Gateway client and has no Android SMS permissions.

## Implemented behavior

- One active 3030 transaction per worker channel.
- Transaction metadata includes local transaction, job, attempt, device, query profile, send time and response deadline.
- A valid reply completes only the currently active transaction and moves the channel into a configurable post-reply drain period.
- No next request can begin during the drain period. Further messages during that period are recorded as unassigned and extend the drain period.
- Duplicate SMS row fingerprints are rejected.
- Unsolicited messages are unassigned.
- A reply arriving after its deadline quarantines the channel.
- A timeout quarantines the channel and never automatically releases it.
- Quarantine release requires an explicit operator ID, resolution note and confirmation that the inbox/transaction was reviewed and drained.
- The transaction state is behind a persistence interface so the Android implementation can use an atomic durable store.

## Important limitations and safety boundaries

1. The current adapter is in-memory for deterministic tests only. It is not crash-safe. The Android SQLite/Room persistence adapter is still to be implemented.
2. The post-reply quiet period is required configuration. The 10-second duration used in unit tests is only a test fixture, not an approved production setting. It must be measured with real 3030 latency/duplicate samples before any phone is allowed to process live work.
3. A quiet period reduces risk but cannot mathematically prove that no late reply exists. Operator recovery and quarantine remain necessary after ambiguous timeouts.
4. The core accepts already-identified 3030 messages. Android SMS sender verification, SMS_RECEIVED handling, permissions, SIM selection and transmission are not implemented here.
5. No foreground service type is declared yet. Android 14+ service type and distribution-policy requirements must be verified before selecting a compliant type; do not misuse `dataSync` as an unlimited daemon or add battery-optimization exemption prematurely.
6. This module is not included by the ordinary Gateway client APK, so the client manifest remains free of SMS permissions.

## Next worker steps

1. Add deterministic tests for the Android SQLite state adapter and callback transitions, including duplicate/stale callbacks and process restart.
2. Implement a controller-aware worker coordinator: server-accepted SENDING, local durable state, selected-SIM revalidation, attempt-specific SMS_SENT callback and exactly one send.
3. Reconcile local callback outcomes with the real controller contract; do not assume a send-failed endpoint exists.
4. Restore the durable transaction gate and inbox before processing any post-restart 3030 message.
5. Set a measured response timeout and post-reply quiet period from real carrier evidence; test fixtures are not production settings.
6. Complete controlled physical-device tests with an approved SIM and redacted real 3030 samples before enabling queue execution.


## Controller-authorised dispatch coordinator — 15 Nov 2026

The Android app now contains an explicit `GatewaySmsDispatchCoordinator` that sequences the existing server `SENDING` transition, durable local attempt metadata, one-at-a-time transaction gate, explicit selected-SIM SMS submission and attempt-specific callback. It also exposes restart-replay reconciliation for locally persisted SMS_SENT success/failure outcomes, using only the controller's existing `awaiting-reply` and `ambiguous` transitions.

The transaction gate now has an explicit safety-quarantine operation, covered by a unit test. The coordinator remains a callable foundation only: the foreground service is still heartbeat-only, does not claim jobs, and does not invoke this coordinator. Callback reconciliation, complete process-death recovery, parsing-to-controller result submission, and physical SIM/3030 tests remain outstanding. No production timeout or drain duration has been selected.
