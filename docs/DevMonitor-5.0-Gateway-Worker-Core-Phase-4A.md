# Gateway Worker Core — Phase 4A

**Branch:** `devmonitor-5.0-gateway-android-download`  
**Status:** Implemented as a pure Kotlin transaction-safety module; CI validation pending. Not yet an Android SMS service and not connected to a live controller or SIM.

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

1. Run and pass this module's unit tests in GitHub Actions.
2. Implement a durable Android persistence adapter and process-restart tests.
3. Build a dedicated worker application target with its own Firebase registration and package ID.
4. Verify Android 14+ foreground-service type, notification and boot-start rules for the chosen distribution channel.
5. Add SMS sender validation/receiver and SIM readiness, then controlled transmission only after a test controller and user-approved test SIM are ready.
6. Connect worker registration/heartbeat/claim/result endpoints using revocable installation credentials.
