# DevMonitor 5.0 — Master Development Sheet

**Last updated:** 15 November 2026  
**Project status:** Active development — foundation and prototypes exist; Gateway 5.0 is not release-ready.  
**Primary repository:** `Busayocode/DevMonitor-Windows`

## 1. Purpose and release boundary

DevMonitor 5.0 is one ecosystem with two user-selected editions:

- **DevMonitor Full:** preserves the existing direct-SMS implementation and established business features.
- **DevMonitor Gateway:** ordinary client edition has no native SMS send/receive permissions; it submits checks through dedicated Gateway phones and receives verified results.

The dedicated Gateway phone is a separate worker role/app installation. It is not the same thing as the ordinary Gateway client.

**Protection rule:** do not change the production Full edition, its SMS flow, payment/MOM rules, Google Drive integration, or the main branch as part of this isolated Gateway work. Do not merge or deploy without explicit approval.

## 2. Branch and package map

| Location | Purpose | Current role |
|---|---|---|
| `devmonitor-5.0-foundation` | Architecture, 3030 protocol findings, Kotlin reference core, PostgreSQL migrations, OpenAPI and FastAPI controller reference service | Main Gateway engineering/foundation branch |
| `devmonitor-5.0-gateway-android-download` | Separate Android Gateway authentication/client prototype and GitHub Actions APK build | Current Android prototype package branch |
| Main / production branch | Existing DevMonitor Full app and release workflow | Must remain untouched by Gateway experiments |

The Android prototype ZIP does not contain the complete controller or the future SMS worker. The controller source and its tests remain on `devmonitor-5.0-foundation`.

## 3. Verified progress

| Workstream | Status | Evidence / boundary |
|---|---|---|
| Two-edition architecture | **Completed — design** | Foundation and architecture documents define Full vs Gateway separation |
| 3030 protocol/source audit | **Completed — source/protocol review** | v340 source audit; user-supplied real reply evidence confirms replies do not echo the queried device/partner code |
| Safe reply-correlation design | **Defined; live validation pending** | One unresolved transaction per physical phone/SIM; timeout creates an ambiguous/quarantined channel; no automatic attribution of late/unsolicited replies |
| Kotlin Gateway core | **Prototype smoke-tested** | Phase 2B scheduler covers leases/cache/idempotency/ambiguity/personal priority/fairness; not integrated into a release app |
| Durable controller foundation | **Implemented in source; CI validated** | PostgreSQL migrations V001–V013, OpenAPI contract, FastAPI reference service, scheduler/queue/cancellation/analytics work; workflow run #43511984083 passed as recorded in Phase 3 notes |
| Firebase token verification | **Implemented in reference service** | Controller verifies Firebase ID-token claims against configured project; production identity integration still pending |
| Gateway Android auth/client prototype | **Build passed** | Run #43681725883 succeeded on 15 Nov 2026; Firebase config check, unit tests, APK build and artifact upload passed |
| Firebase app registration/config | **Configured in prototype branch** | Separate package `com.aziitech.devmonitor.gateway`, app-specific config and matching signing fingerprint were checked by CI; real-device sign-in still needs explicit confirmation |
| APK build artifact | **Available** | `DevMonitor-Gateway-5.0-debug`, approximately 4.33 MB, Actions artifact expires 29 Nov 2026 |
| Gateway worker-core CI | **Passed** | Run #43682711793: `:gateway-worker-core:test` and APK build job succeeded; source ZIP packaging also succeeded |

### What the successful APK build proves

It proves that the current authentication/client prototype compiles, its included unit tests pass, the configured Firebase file identifies the expected Android package, and GitHub uploaded the debug APK.

It does **not** prove live Google/Firebase sign-in, a successful request to a deployed controller, real SMS transmission to 3030, a working Gateway worker, multi-phone scheduling, or production reliability.

## 4. Remaining implementation work

### A. Authentication and client connection
- [ ] Install and test the current debug APK on a real device.
- [ ] Verify Google sign-in for the distinct Gateway package.
- [ ] Confirm a Firebase ID token is obtained without displaying, persisting or logging it.
- [ ] Deploy nothing yet; first complete the controller configuration and identity integration tests.
- [ ] Verify authenticated client calls against a controlled HTTPS test controller when one is approved and available.

### B. Dedicated Android Gateway phone worker
- [x] Add isolated Kotlin transaction-safety core with tests; GitHub CI passed in run #43682711793.
- [x] Implement a durable Android SQLite adapter for the worker-core snapshot contract (single-row atomic snapshot, transaction metadata, drain/quarantine state and duplicate-message fingerprints); compile validation pending.
- [x] Add durable SQLite records for controller job leases and attempt lifecycle, including tenant/client/gateway ownership, lease expiry, attempt state, send timestamps, response deadline, terminal outcome and normalized result payload; claim + first-attempt persistence is atomic.
- [ ] Create the actual worker app/service; the current APK is only an ordinary client/auth prototype.
- [ ] Register a dedicated phone installation and securely store its revocable credential.
- [ ] Implement controller-assigned job receipt and health heartbeats.
- [ ] Implement SMS permissions, SIM/subscription readiness and SMS send/receive handling on the dedicated worker only.
- [ ] Reuse the audited request builder, parser and normalized result model rather than copying the Full edition's MainActivity SMS loop.
- [ ] Enforce one unresolved 3030 transaction per physical phone/SIM.
- [ ] Persist transaction state and submit results idempotently.

### C. 3030 response safety
- [ ] Implement the confirmed no-device-code response rule.
- [ ] Quarantine a phone after an ambiguous timeout; do not attach late or unsolicited messages to the next job.
- [ ] Implement a deliberate operator/recovery procedure and test its quiet-period assumptions.
- [ ] Test duplicate broadcasts, duplicate replies, late replies, process death and reboot.
- [ ] Preserve existing Standard 120-second and Partner 180-second wait profiles and the existing retry-after-first-pass intent.

### D. Controller hardening and deployment
- [ ] Apply V001–V013 to a clean, dedicated PostgreSQL test database.
- [ ] Run cross-process/concurrent claim tests, controller restart/recovery tests and full authorization/isolation tests.
- [ ] Complete Firebase identity-provider integration tests.
- [ ] Review rate limits, secret handling, database roles, backups, monitoring and operational runbooks.
- [ ] Select a hosting provider and estimate costs.
- [ ] Obtain explicit approval before provisioning paid infrastructure or deploying a public service.

### E. Multi-Gateway, queue and personal Gateway
- [ ] Test two and then three or more physical Gateway phones.
- [ ] Verify request-level FIFO allocation across healthy gateways, dynamic assignment and safe failover.
- [ ] Verify one phone waiting for a 3030 reply does not block other healthy phones.
- [ ] Test queue position, estimates, cancellation, analytics and privacy.
- [ ] Implement pairing/owner controls and Personal Only / Personal + Shared Pool modes in the app.
- [ ] Verify personal work priority, fair shared-pool scheduling and owner-only access on real devices.

### F. Android availability and reliability
- [x] Add a user-started Android foreground-service foundation with persistent notification and Pause/Resume/Stop actions; it currently runs the heartbeat loop only (no SMS or job claiming).
- [x] Add a persistent notification with truthful heartbeat-only state and Pause/Resume/Stop controls.
- [x] Extend durable persistence from the SMS channel snapshot to controller job, attempt, lease and send lifecycle records (initial local schema/API implemented; CI and recovery tests pending).
- [ ] Wire durable job/attempt transitions into the controller polling/claim client and the eventual foreground worker service.
- [x] Review Android 14+ foreground-service type/permission requirements and Android 15 dataSync timeout/boot restrictions. `specialUse` is used provisionally for the long-running dedicated SMS Gateway relay; Google Play declaration/review remains pending. No BOOT_COMPLETED auto-start is declared.
- [x] Add a battery-optimization status check and user-guided shortcut to Android battery-optimization settings on the dedicated Gateway phone screen. It opens the system settings list; the operator chooses whether to exempt the app. No direct exemption request or automatic grant is performed.
- [ ] Validate battery-optimization status and OEM-specific background controls on physical Gateway phones.
- [ ] Test screen-off, Doze, Wi-Fi/mobile transitions, process kill, swipe-away, reboot and force-stop.
- [ ] Never promise uninterrupted operation when Android/OEM restrictions prevent it.

### G. Gateway Edition integration and release
- [ ] Integrate the controller transport into the actual DevMonitor app codebase.
- [ ] Preserve Device Monitor, My Partners, SMS Checker history, Loaded Reports, MOM, Conversion, Google Drive and established business rules.
- [ ] Verify the final merged Gateway client manifest has no SMS send/receive/read permissions.
- [ ] Keep the Full edition direct-SMS path intact and regression-test it.
- [ ] Prepare separate, clearly named Full and Gateway release artifacts only after acceptance tests pass.

## 5. Architecture rules that must not be weakened

1. One central durable controller queue; Gateway phones claim work and never choose another user's jobs.
2. One unresolved 3030 transaction per physical Gateway phone/SIM unless future protocol evidence proves safe correlation.
3. A timeout is ambiguous, not an ordinary retryable failure. Quarantine the affected phone until safely resolved.
4. Different healthy phones may work in parallel.
5. Personal Gateway ownership and shared-pool participation are explicit and server-enforced.
6. Cached history and fresh-check requests are separate operations. Cached data must show its original check time.
7. One latest accepted daily result per tenant/device/business date; older delayed results cannot overwrite newer values.
8. Full and Gateway editions share business logic where practical, but use separate SMS transports.
9. No paid backend or public deployment without cost review and explicit approval.

## 6. Existing DevMonitor rules to preserve

- Full edition continues its direct SMS implementation.
- Standard Device Monitor wait: 120 seconds.
- Partner menu wait: 180 seconds.
- Timed-out devices are retried only after the first pass, according to existing behavior.
- Existing daily history, two-month retention, reports, MOM, Conversion, payments and Google Drive behavior remain unchanged.
- Do not alter finalized payment/MOM formulas or business rules under the Gateway workstream.

## 7. Immediate next sequence

1. Verify real Google/Firebase sign-in on the current Gateway APK.
2. Build and inspect the new registration UI/identity integration, then complete a live registration round-trip only after an approved HTTPS controller endpoint is configured.
3. Complete controller integration/security tests on an isolated test database; no paid deployment yet.
4. Worker-core CI passed (run #43682711793). The Android branch now contains SQLite persistence for the SMS channel snapshot plus controller job/lease and attempt lifecycle records. Validate the integrated Android build, then wire the ledger to controller claim and worker state transitions before selecting the compliant foreground-service type.
4. Connect one worker to a test controller and run one controlled 3030 transaction with ambiguity protection.
5. Add a second worker and validate queue assignment, isolation and recovery.
6. Integrate the Gateway transport into the separate Gateway edition and run permission/regression checks.
7. Expand to three or more workers, then review release readiness and infrastructure cost.

## 8. Latest Gateway worker-core CI evidence

- Workflow run: [#11 — 43682711793](https://github.com/Busayocode/DevMonitor-Windows/actions/runs/43682711793)
- Branch: `devmonitor-5.0-gateway-android-download`
- Commit: `d03e44b09e472c7ec72fe2e54ac3e204b7e48f78`
- Result: **success**. The build job ran `:gateway-auth-android:testDebugUnitTest`, `:gateway-worker-core:test`, `:gateway-auth-android:assembleDebug`, and `:gateway-app:assembleDebug`; the source ZIP packaging job also succeeded.
- Artifacts: `DevMonitor-Gateway-5.0-debug` (~4.33 MB) and `DevMonitor-Gateway-5.0-Source` (~82.6 KB). Both are temporary Actions artifacts, expiring 29 Nov 2026.
- Scope: automated compile/unit-test/package validation only. No live-device SMS or controller test is implied.

## 10. Gateway registration UI and encrypted identity integration — 15 Nov 2026

- [x] Wire the existing controller registration call into the Android Gateway app's setup screen.
- [x] Add an editable installation display name (defaults to the Android device model).
- [x] Obtain a fresh Firebase ID token only for the client registration request; never display, log or persist it.
- [x] Save the controller-issued opaque Gateway bearer credential and Gateway ID through the Android Keystore AES-256-GCM credential store.
- [x] Load and display the registered Gateway ID after app restart without exposing the credential.
- [x] Prevent a second registration from the same installation once a stored Gateway ID is available.
- [x] Keep the Firebase user token and Gateway worker credential as separate authentication domains.
- [ ] Run the updated Gateway Android build and inspect compiler/test results.
- [ ] Configure an approved HTTPS controller endpoint and complete a real registration round-trip. The controller has not been deployed or configured in this app yet; no successful live registration is claimed.
- [ ] Add a safe operator recovery/revocation path for an inaccessible or invalidated Android Keystore credential. Do not silently create a replacement Gateway identity.
- [ ] Persist the controller URL and verify it belongs to the intended environment before enabling unattended worker operations.

**Implementation boundary:** registration wiring is present in the app source, but it does not make the phone a running SMS worker. Heartbeats, worker foreground service, SMS permission/SIM checks, claim loop, SMS send/receive, and result submission remain separate unfinished work.

## 9. Gateway visual parity work

- Reference inspected: v337 Android source archive retrieved and inspected internally.
- Design system: Aurora / Jetpack Compose / Material 3.
- Implemented in source: Gateway-owned copies of FieldPulseUi.kt, StateComponents.kt and GatewayAuroraTheme.kt; Compose enabled in Gateway build; bare diagnostic layout replaced by an Aurora-based four-section Gateway shell.
- Visual components reused: Aurora palette/background, gradient and outlined buttons, glass cards, hero, metric cards, section headers, status pills, custom navigation glyphs and loading/empty/error/offline state components.
- CI: new Gateway Compose build validation is pending. Do not ask the user to install this intermediate build until the run passes.
- Implemented in source: branded modal navigation drawer, four workspace destinations, animated fade/slide screen transitions, floating Aurora wait overlay with animated indeterminate progress rail, and original DevMonitor toolbar/drawer icon.
- Not yet complete: verify launcher branding, dialogs, screenshot comparisons, responsive QA, full interaction polish and CI validation.
- Acceptance: do not claim 100% parity until all those areas are compared against the main app at matching device sizes.

## 10. Evidence and status discipline

Use these labels consistently:

- **Design approved:** requirements documented; no implementation implied.
- **Implemented in source:** code/schema exists; not necessarily tested.
- **CI validated:** automated checks passed in GitHub; not equivalent to live service/device tests.
- **Device tested:** explicitly exercised on a physical device.
- **Deployed:** running in a selected environment with operational evidence.
- **Release accepted:** all agreed acceptance tests passed and the user approved promotion.

Never describe the current Gateway APK as the completed Gateway 5.0 product. It is an authentication/client prototype only.


## 10. Gateway visual baseline and latest implementation

- Reference: DevMonitor v337 Aurora Compose/Material 3 source, inspected as a design reference only.
- Gateway now has the shared Aurora palette, dark/light theme definitions, original reusable Aurora component set, branded toolbar and modal drawer, four navigation destinations, animated screen transitions, floating indeterminate wait overlay, and loading/empty/error/offline states.
- Original DevMonitor launcher icon is now assigned in the Gateway manifest.
- Latest visual CI run #43684136038 exposed missing Compose imports in the waiting-state file. The imports have been corrected in commit `8671a7cb84de5649b19686e93d6aa25a8b2ca520`; the launcher icon was applied in commit `fe0110efab534040be8b9e625957f2bb71221fd5`. A fresh CI pass is still required.
- Visual parity is the continuing baseline: device screenshot comparisons, interaction QA, dialogs, accessibility and responsive refinements will be done as each feature is developed. No claim of pixel-perfect parity or release readiness is made yet.

## 11. Durable Android worker state — first implementation

- Added `gateway-app/.../worker/SqliteSmsChannelStateStore.kt`, an Android-only SQLiteOpenHelper adapter implementing the pure Kotlin `SmsChannelStateStore` interface.
- Persists the channel state, active transaction identity and timing, drain deadline, duplicate-message fingerprints and operator resolution note in a single JSON snapshot row.
- Snapshot replacement is enclosed in a SQLite transaction; the worker-core module remains Android-free.
- This is the first durable persistence layer only. It is not yet wired to a live SMS receiver/service and does not yet persist controller job leases or outbound send lifecycle records.
- Validation pending the next Gateway CI run; process-death/reboot recovery tests remain required.


## 12. Gateway SQLite job and attempt ledger — implementation update

- The Android app now explicitly depends on the Android-free `:gateway-worker-core` module, so the SQLite adapter can implement `SmsChannelStateStore`.
- Fixed the prior CI integration errors: missing Compose layout-size/Color imports, missing worker-core project dependency, and Java time desugaring required by the app's minSdk 23.
- Expanded `SqliteSmsChannelStateStore` database schema to version 2 with `gateway_jobs` and `gateway_attempts` tables and an indexed job-to-attempt lookup.
- Job records preserve tenant/client/gateway identity, lease ID/expiry, request payload, lifecycle state and update time.
- Attempt records preserve attempt number/state, transaction identity, send-start/sent timestamps, response deadline, completion time, error details and normalized result payload.
- `saveClaim` commits a claimed job and its first attempt in one SQLite transaction; `saveJobAndAttempt` commits subsequent paired lifecycle updates atomically.
- CI run #43696679196 is the first run for the complete dependency/import/persistence integration; result is pending at the time of this update.
- Not yet wired to a live controller or SMS service. Controller protocol validation, process-death recovery, lease-expiry reconciliation, and device-level SQLite tests remain outstanding.


## 13. Controller claim contract alignment and lease-secret handling

- The local job record was aligned to the actual reference controller claim function fields: job ID, gateway ID, canonical device ID, query profile, attempt number and lease expiry. It does not invent tenant/client/payload fields that the claim endpoint does not return.
- Local job and attempt lifecycle enums now match the controller's reference states (including LEASED, SENDING, AWAITING_REPLY, AMBIGUOUS, COMPLETED, SEND_FAILED and TIMED_OUT).
- The raw `lease_token` is a secret. It is deliberately excluded from the SQLite JSON records. Added `AndroidGatewayLeaseTokenStore`, which encrypts per-attempt tokens using an AES-256-GCM key held by Android Keystore; encrypted values are kept in private app preferences and can be retrieved/removed by attempt ID.
- Controller claim contract confirmed against the foundation branch: `POST /v1/gateway/jobs/claim` returns a `job` object with `job_id`, `attempt_id`, `lease_token`, `canonical_device_id`, `query_profile`, `attempt_number`, and `lease_expires_at`. The existing reference API also defines `/sending`, `/awaiting-reply`, `/result`, `/send-failed`, `/ambiguous`, heartbeat and active-attempt recovery.
- Next implementation: build an authenticated worker transport around these exact routes; after claim, persist job+attempt atomically and persist the lease token in Android Keystore before acknowledging work locally. Do not start SMS transmission until the server has accepted the SENDING transition.
- The latest complete Android build run for the job-ledger and Keystore changes is still queued/pending at the time of this update. No APK build success is claimed for these newest changes.


## 14. Authenticated Gateway worker transport — first integration slice

- Added `GatewayWorkerControllerClient.kt` using the dedicated opaque Gateway bearer credential over HTTPS; it does not reuse Firebase user-token signing.
- Implemented the exact reference-controller `POST /v1/gateway/jobs/claim` contract, including its nested `job` response and returned job/attempt/device/profile/lease-expiry fields.
- A successful claim stores the raw lease token in `AndroidGatewayLeaseTokenStore` (Android Keystore AES-GCM encryption) and atomically stores the job + attempt in SQLite. The token is not copied into the SQLite record or returned in the claimed-work model.
- Implemented server-acknowledged state operations for `/sending`, `/awaiting-reply`, `/ambiguous` and `/result`. Local lifecycle state is updated only after the controller accepts the transition.
- Result submission is bound to the job, attempt and lease token; on accepted completion the local records are marked COMPLETED and the encrypted lease token is removed.
- This is a transport/integration slice only. No SMS is transmitted by this client. The caller must still wait for a successful SENDING acknowledgement and the dedicated worker service must enforce transaction-safety rules before any future SMS operation.
- Still required: real controller integration tests, active-attempt restart recovery, heartbeat, definitive pre-send failure response reconciliation, controller registration/credential provisioning, and wiring to a foreground worker service.
- Latest Gateway CI run for this code: [#43696953884](https://github.com/Busayocode/DevMonitor-Windows/actions/runs/43696953884). Source packaging succeeded; APK compilation is still in progress as last checked.

## 15. Gateway registration, health heartbeat and restart reconciliation — implementation slice

- Added an Android Keystore-backed AndroidGatewayCredentialStore. The Gateway's opaque bearer credential is encrypted with AES-256-GCM and is not stored in plaintext preferences, SQLite or logs.
- Added the exact reference-controller registration call: signed-in Firebase user token -> POST /v1/client/gateways with display_name; the returned gateway_id and one-time gateway_token are stored encrypted locally.
- Added the Gateway-authenticated POST /v1/gateway/heartbeat request using the controller's exact fields: app version, SMS permission readiness, telephony readiness, network readiness and optional last successful 3030 timestamp.
- Added GET /v1/gateway/active-attempt recovery inspection. The controller intentionally does not return a lease token. The client reports whether the matching encrypted local lease token still exists; it must not fabricate or reacquire a missing token. Missing token means safe server-side expiry/reconciliation and operator visibility are required.
- Strengthened atomic SQLite job/attempt updates: matching job ID, Gateway ID, attempt number and lease expiry are now required before a paired lifecycle update is accepted.
- Moved blocking SQLite and Android Keystore operations in the suspend transport methods onto Dispatchers.IO.
- Implemented transport and secure storage only. The registration screen/onboarding, persisted credential-provider wiring, periodic heartbeat scheduler, active-attempt state reconciliation UI, and foreground worker service remain outstanding.
- SMS readiness must remain false until runtime permission and SIM/subscription readiness checks are actually implemented. No SMS send/receive capability is implied by these APIs.
- CI must validate the complete integrated Android build before this slice is considered CI validated. No physical-device or live-controller test is implied.


## 16. Gateway heartbeat and foreground-service foundation — 15 Nov 2026

- Added `GatewayForegroundService` as a dedicated Android Service, not an Activity-owned coroutine.
- The service is started only by an explicit user action after the app has a registered Gateway identity and an HTTPS controller URL. It is not auto-started at boot.
- Added a persistent low-priority notification with Pause, Resume and Stop actions, plus a notification tap target back into the Gateway app.
- Added controller endpoint pinning inside the encrypted Gateway identity record. The registered endpoint is restored on launch, locked in the UI after registration, and checked again before the heartbeat service can use the bearer credential. Older credential records without a pinned endpoint are blocked from service startup rather than sending the credential to an unverified host.
- Added a controller heartbeat loop using the encrypted Gateway bearer credential. The heartbeat reports the app version, validated network readiness, and deliberately reports both SMS permission readiness and telephony readiness as **false** until those checks are implemented.
- Added a Gateway phone screen with service status and Start/Stop controls. The persistent notification also exposes Pause/Resume/Stop while active.
- Persisted the controller URL locally as non-secret configuration; the bearer credential remains encrypted by Android Keystore.
- **Foreground-service classification:** Android 14+ requires a declared service type and corresponding permission. The app targets API 35, so `dataSync` is unsuitable for an always-on worker because Android 15 imposes a six-hour-per-24-hour limit and restricts boot-start. This prototype declares `specialUse` with a narrow subtype description for the dedicated user-started SMS Gateway relay. Google Play declaration/review is still required before Play distribution; this is not a policy approval claim.
- No boot receiver, automatic battery-optimization exemption request, SMS permissions, SMS send/receive, controller job claim, or job execution was added. The Gateway screen now checks `PowerManager.isIgnoringBatteryOptimizations()` and provides a user-controlled shortcut to Android's battery-optimization settings; this does not override OEM background restrictions. The heartbeat cadence is currently 60 seconds and should be tuned against the deployed controller's operational limits before production. The app also checks whether its service is actually running before displaying a persisted RUNNING/PAUSED state.
- CI validation and real-device notification/action/heartbeat tests remain pending.


## 17. User-guided battery optimisation controls — 15 Nov 2026

- Added a live Android battery-optimisation exemption status check using `PowerManager.isIgnoringBatteryOptimizations(packageName)`.
- Added a dedicated Gateway status card with a system-settings shortcut using `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`.
- The operator must choose the app and approve any change inside Android Settings. The app does not request the restricted direct-exemption permission and does not silently change device settings.
- The UI explains that exemption from standard Android battery optimisation does not guarantee immunity from OEM background limits, Doze edge cases, force-stop or network loss.
- Physical-device verification and manufacturer-specific guidance remain pending. Heartbeat-only operation remains unchanged; no SMS permission, job claiming or SMS processing is enabled.


## 18. SMS permission and SIM-readiness foundation — 15 Nov 2026

- Added optional telephony hardware declaration so the app can still install on devices without a modem; a Gateway phone without a usable SIM cannot become an SMS worker.
- Declared Android SEND_SMS, RECEIVE_SMS and READ_PHONE_STATE permissions for the dedicated Gateway role. No READ_SMS permission was added.
- Added an explicit operator action on the Gateway phone screen to request only missing runtime permissions. The app does not prompt on first launch or automatically request permissions when starting the heartbeat service.
- Added live permission status and active subscription/SIM state checks. On multi-SIM Android devices, each active subscription is checked for SIM_STATE_READY; on API 23 the available single-SIM state is checked.
- Denials and missing/locked SIMs are surfaced as not-ready states. Permission grant alone does not mark a SIM ready.
- IMPORTANT BOUNDARY: this is readiness discovery only. No SMS sender, receiver, inbox access, sender verification, message correlation or transaction execution is implemented. The controller heartbeat deliberately continues reporting sms_permission_ready=false and telephony_ready=false until the complete SMS transport is implemented and validated.
- SMS permissions are restricted/sensitive in distribution policy. Before any public Play release, verify current Google Play SMS/Call Log policy and declaration eligibility for the app's core dedicated Gateway use case. This branch is not being promoted for public release at this stage.
- Physical-device tests still required: permission grant/denial, no SIM, SIM absent/locked/ready, dual SIM, SIM removed while app is running, and permission revoked in Settings.


## 19. Explicit multi-SIM selection foundation — 15 Nov 2026

- Added a Gateway SIM selector that lists each active Android subscription separately by SIM slot and carrier name; it does not request or display the phone number.
- The operator must explicitly choose a SIM. The app does not silently select the default subscription or switch lines when multiple SIMs are present.
- The selected subscription ID is persisted locally so the choice survives an app restart. On each readiness refresh, the saved selection is checked against the currently active subscriptions; if the SIM is removed, replaced or no longer visible, the selection is cleared and must be made again.
- SIMs not reporting READY are visible but disabled in the selector. A selected SIM is accepted only while it remains present and READY.
- This is selection and readiness groundwork only. The selected subscription is not yet passed to SmsManager, no SMS is sent or received, and controller heartbeat readiness remains false. Before enabling SMS, the sender must revalidate the selected subscription immediately before every send and pause rather than fall back if it is unavailable.
- Physical-device validation still required on single-SIM, dual-SIM and multi-SIM devices, including SIM swap/removal, disabled SIM, locked SIM, reboot and subscription-ID changes.

- CI verification note: the first SIM-selector APK compile exposed a missing Compose `mutableStateOf` import in `GatewayHomeScreen.kt`; this was corrected in commit `0575912`. A fresh build is required before treating this milestone as build-verified.


## 20. Globacom-first SIM selection — 15 Nov 2026

- [x] Detect a Nigerian Globacom SIM by home-operator MCC/MNC 621/50, using the carrier label only when the operator code is unavailable.
- [x] Automatically select a READY Glo subscription only when exactly one is available; if multiple READY Glo SIMs are present, require the operator to choose rather than guessing by slot order.
- [x] Persist selection mode separately from the selected subscription: AUTO_GLO or MANUAL.
- [x] Preserve an explicit manual override; never replace a manually selected SIM silently.
- [x] Add an Auto-select Glo control to return to preferred automatic selection.
- [x] Show Glo identity and readiness in the Gateway SIM selector, including an explicit multiple-Glo choice prompt.
- [ ] Connect the selected subscription to SmsManager.createForSubscriptionId() in the eventual worker sender; revalidate active + READY immediately before each send.
- [ ] If a manually selected subscription disappears or becomes unavailable, pause SMS work and require operator action; never fall back to Android's default SMS subscription.
- [ ] Add unit/instrumentation coverage for multiple Glo SIMs, mixed carriers, no Glo SIM, SIM lock/removal, and manual override.
- [x] Build and verify the current Gateway branch after this UI/state update — Gateway workflow run #91 (`43711966513`) passed on 15 Nov 2026; APK and source package uploaded. No real-device SIM/SMS test is implied.
- **Selection ambiguity safeguard:** automatic mode selects only a single READY Glo subscription. Two or more READY Glo subscriptions leave selection empty until the operator chooses one; manual selection remains pinned.
- **Current boundary:** this remains selection/readiness groundwork. No SMS is sent or received; heartbeat SMS/telephony readiness remains false.


## 21. Guarded Android 3030 SMS intake — 15 Nov 2026

- Added `GatewaySmsReceiver`, registered for Android `SMS_RECEIVED` broadcasts and protected by the platform `BROADCAST_SMS` permission.
- Added a strict pure-Kotlin sender policy: the complete normalized originating address must equal `3030`; arbitrary phone numbers and partial matches are rejected.
- Multipart messages from one originating address are assembled in timestamp order.
- Only verified 3030 messages are stored in a private, bounded SQLite inbox; unrelated personal SMS are ignored. Inbox rows retain a duplicate fingerprint, receive timestamp, available Android subscription ID, body and `RECEIVED_UNASSIGNED` processing state.
- Inbox storage is capped at the newest 200 rows. Message bodies are not logged.
- Added unit coverage for exact short-code matching and rejection of prefix/suffix/ordinary-number lookalikes.
- This is intake only: no parsing, device/partner attribution, transaction completion, controller result submission, retry or outbound SMS is enabled. All received messages remain unassigned until the transaction gate and parser are integrated.
- Documentation: `docs/DevMonitor-5.0-Gateway-SMS-Intake.md`.
- CI run for this slice is pending. Physical-device and live 3030 validation remain outstanding.


## 22. 3030 result parser and gated inbox processor — 15 Nov 2026

- Added Android-free `Gateway3030ResponseParser` and parser tests. It requires the seven documented numeric metrics and rejects incomplete, duplicate, negative or non-numeric values.
- Added `Gateway3030InboxProcessor`: verified 3030 inbox rows are evaluated against the existing per-Gateway transaction gate; duplicates, unsolicited, late, drain-window and quarantined messages are not attributed to a new job.
- Inbox decisions are persisted. A matched result is staged as `ACCEPTED_PENDING_CONTROLLER`, not submitted to the controller.
- Processing remains an explicit worker-stage API and is not automatically invoked by the broadcast receiver. No outbound SMS, controller result submission, retry or queue advancement is enabled.
- CI run is pending. Actual 3030 message formatting must still be checked against a redacted live sample; exact carrier date formatting remains unnormalized.


## 23. Subscription-pinned SMS sender and durable send-result callback — 15 Nov 2026

- Added a subscription-specific `SmsManager` sender primitive targeting only short code `3030`; it requires explicit controller `SENDING` acceptance, an explicit selected subscription ID, SEND_SMS + READ_PHONE_STATE permissions, active-subscription membership and SIM READY checks immediately before submission. There is no default-SIM fallback.
- Added attempt-specific, package-bound `SMS_SENT` PendingIntent creation and a private, non-exported result receiver.
- The callback atomically updates the durable SQLite job + attempt: Android success moves SENDING to AWAITING_REPLY; failure moves the attempt to SEND_FAILED and job to FAILED. Stale/duplicate callbacks are ignored. No automatic retry occurs.
- Android's successful SMS_SENT result is not proof of 3030 receipt or processing.
- The sender/callback are still not wired into a live controller-authorized worker flow. Controller protocol integration, durable pre-send SENDING transition, worker recovery, reply correlation, response timeout and measured drain period remain prerequisites.
- Integrated Gateway workflow run #117 (`43717000034`) passed on 15 Nov 2026, including Android build and source packaging. This is CI compile/package validation only; no physical SIM/SMS test is implied. No physical SIM/SMS test is claimed.


## 24. Gateway sender/callback CI verification and integration gate — 15 Nov 2026

- [x] Integrated Gateway workflow #117 (`43717000034`) passed on branch `devmonitor-5.0-gateway-android-download`, commit `638744dd995726a6dfe91cb6f54a308bf1b0ee39`.
- [x] Android build and source-package job completed successfully; the prior superseded run #116 was cancelled by the newer push and is not a failure.
- [x] Source review confirms the foreground service remains heartbeat-only; no claim/send loop is active.
- [x] Source review confirms `GatewayWorkerControllerClient.markSending` first receives controller acceptance, then persists the local job/attempt as SENDING. The sender independently blocks unless its caller supplies that acceptance.
- [x] The SMS_SENT callback persists only the local Android platform outcome. It does not yet notify the controller or establish 3030 receipt.
- [ ] Implement a controller-aware worker coordinator that calls markSending, persists/revalidates the selected SIM, creates the attempt-specific PendingIntent, and submits exactly one SMS.
- [ ] Reconcile callback outcomes with controller state using only endpoints/actions actually supported by the deployed controller; do not invent a send-failed endpoint.
- [ ] Restore active transaction/inbox state after process death before accepting any 3030 message.
- [ ] Configure a measured response timeout and post-reply quiet period. The 10-second unit-test fixture is not a production value.
- [ ] Add deterministic tests for send success/failure, duplicate/stale callback, process restart, lease expiry and controller/local-state disagreement.
- [ ] Run controlled physical-device tests with the selected Glo SIM and a redacted real 3030 response before enabling queue execution.

**Current operational boundary:** sender and callback primitives compile, but the foreground service still reports SMS/telephony readiness as false and does not claim or execute jobs. No live SMS capability is claimed.


## 25. Controller-authorised dispatch coordinator — 15 Nov 2026

- Added an explicit `GatewaySmsDispatchCoordinator` API; it is not yet invoked by the foreground service or a background queue loop.
- The coordinator requires a previously claimed job, an explicit selected subscription ID, encrypted lease-token store, durable SQLite ledger, transaction gate and a positive caller-supplied response timeout.
- It first asks the controller to accept `SENDING`. If acceptance is uncertain, it attempts the existing `ambiguous` transition and never transmits.
- After controller acceptance, it opens the one-at-a-time durable transaction gate, persists transaction ID/send start/response deadline, creates the attempt-specific SMS_SENT PendingIntent and calls the subscription-pinned sender exactly once.
- Missing/unready SIM, permission loss, persistence failure or Android submission rejection causes safety quarantine and an attempt to use the existing controller `ambiguous` transition. No default SIM fallback and no automatic retry.
- Added restart-replay API for the durable SMS_SENT callback: successful platform callback is reported through the existing `awaiting-reply` endpoint; failed callback uses the existing `ambiguous` endpoint. No unsupported send-failed controller route is assumed.
- Added explicit transaction-gate quarantine operation and a unit test proving new work remains blocked until operator resolution.
- **Not yet operational:** foreground service still does not claim or dispatch jobs; callback reconciliation is not scheduled; active transaction/inbox restoration and controller result submission are not integrated. The caller-supplied response timeout and post-reply quiet period require measured carrier evidence.
- CI for this commit sequence is pending. No physical SIM or real 3030 exchange is claimed.

## 26. Restart recovery policy and identity hardening — 15 Nov 2026

- [x] Gateway workflow #130 (`43718884534`) passed on commit `5c360283e1e9e3b17b1e409aeb6554f200f78c18`. Both Android test/build and source-package jobs completed successfully.
- [x] Added a pure worker-core recovery assessment for controller/local state after process restart. It distinguishes no work, eligible leased work, successful-send callback reconciliation, reply-wait resumption and quarantine.
- [x] Recovery never retransmits an uncertain `SENDING` attempt. A matching durable Android success callback can be reconciled without resending; reply intake can resume only when controller, local ledger and active transaction agree.
- [x] Hardened identity checks to verify the local attempt's owning job as well as controller job/attempt IDs; added a regression test for a mismatched attempt owner.
- [ ] Build and verify the identity-hardening follow-up commit now queued by this change.
- [ ] Add a production adapter that snapshots controller state, local SQLite job/attempt state, encrypted lease-token availability and restored channel state, then invokes this policy before any worker action.
- [ ] Restore and reconcile the persisted channel transaction and inbox after process death; prove the restore sequence is atomic and fail-closed.
- [ ] Keep foreground service heartbeat-only until the recovery adapter, controller reconciliation and controlled physical-device tests are complete.

**Current operational boundary:** recovery assessment is tested as a pure policy, but it is not yet wired into service startup or a live dispatch loop. No live SMS execution, automatic retry, 3030 correlation or process-restart recovery is claimed. Physical SIM testing and a redacted real 3030 response sample remain outstanding.


## 27. Android Gateway restart-recovery adapter — 15 Nov 2026

- Added a production Android recovery adapter around the pure worker-core recovery policy.
- Added a durable SQLite query for all unresolved local attempts, so recovery can detect orphaned or conflicting work instead of inspecting only the server's active attempt.
- Recovery compares controller and local job/attempt IDs, Gateway ownership, device ID, query profile, lease identity, encrypted lease-token availability and the persisted SMS channel transaction.
- A successful Android `SMS_SENT` callback is reconciled with the controller's existing `awaiting-reply` endpoint without resending.
- A matching active reply wait can resume intake only when its persisted response deadline remains valid.
- An expired lease, expired/missing response deadline, missing lease token, orphaned local attempt, identity mismatch or multiple unresolved attempts is quarantined. The adapter never sends SMS, claims work or retries.
- A leased attempt is marked eligible only while its lease is live and the persisted quiet/drain window has elapsed.
- **Not yet operational:** this adapter is not wired into foreground-service startup. The service remains heartbeat-only; no job claim, SMS send, or automatic reply/result processing is enabled.
- CI build and source package verification are required for this commit. Physical Glo SIM and live 3030 testing remain outstanding.


## 11. Durable Android worker persistence verification — 16 Nov 2026

- [x] Added Robolectric/JUnit coverage for the Android SQLite job/attempt ledger.
- [x] Added a store-recreation test proving claimed job and attempt records survive adapter recreation.
- [x] Added exactly-once SMS_SENT callback tests for success and failure, including duplicate/out-of-order callback rejection.
- [x] Added unknown-attempt and non-SENDING callback rejection coverage.
- [ ] Await Gateway Android workflow validation of these tests and the integrated APK build.
- [ ] Expand tests to restart recovery reconciliation and channel snapshot restoration before enabling any claim/send loop.

**Next implementation slice:** after these persistence tests pass, connect the restart-recovery adapter and dispatch coordinator to a worker lifecycle in a controlled, user-started foreground service. Keep the service heartbeat-only until controller claim/recovery, SIM readiness, transaction restore, reply processing and result submission are all wired and tested. Do not enable unattended SMS dispatch or select production response/drain timing from test fixtures.


## 28. 3030 reply correlation and guarded controller result pipeline — 16 Nov 2026

- [x] Extended the private 3030 inbox schema to retain the correlated controller job ID, attempt ID and canonical parsed payload. Added SQLite v3 migration for existing installs.
- [x] Valid 3030 replies are staged only after the single-active-transaction gate accepts them. A failed durable stage quarantines the channel rather than treating the reply as accepted.
- [x] Added an explicit processor API to validate a staged response against its original body and matching durable `AWAITING_REPLY` attempt before constructing the controller result payload.
- [x] Added controller submission of all parsed report metrics with business date, send/check timestamp, received timestamp and parser version.
- [x] Added a write-ahead `SUBMISSION_IN_PROGRESS` state before the network request. If the process dies during submission, the row is not automatically replayed; it remains an operator-reconciliation case.
- [x] HTTP/transport uncertainty, missing attempt identity, changed payload or attempt-state mismatch is marked `SUBMISSION_UNCERTAIN` and quarantines the channel. No automatic duplicate result POST is attempted.
- [x] Added Robolectric tests for exact attempt correlation, durable inbox staging, duplicate broadcast suppression and uncertain-submission non-replay.
- [x] Gateway workflow #156 (`43835984615`) passed on commit `4b5c67af74cfac2ee394d465633121f8e49c143e`; Android unit tests/build and source ZIP packaging both succeeded.
- [ ] Wire inbox processing and guarded result submission into the foreground worker only after durable gate restoration and an approved production response/drain timing configuration.
- [ ] Add controller-side idempotency/reconciliation for `SUBMISSION_IN_PROGRESS` rows before any automatic recovery from an interrupted result POST.
- [ ] Run controlled physical-device tests using the selected Globacom SIM and a redacted real 3030 response.

**Current operational boundary:** the correlation, durable staging and guarded submission APIs are implemented and CI-verified, but they are not yet called by the foreground service. The service remains heartbeat-only; live SMS dispatch and automatic queue execution remain disabled. A successful CI build is not evidence of a real SIM/3030 transaction.


## 29. Gateway live phone-readiness heartbeat — 16 Nov 2026

- [x] Added `GatewayPhoneReadiness` to inspect the actual Android `SEND_SMS`, `RECEIVE_SMS` and `READ_PHONE_STATE` permissions.
- [x] Reuses the exact SIM-picker preference namespace/key (`devmonitor_gateway_sim` / `selected_subscription_id`) rather than selecting a different SIM in the service.
- [x] Validates that the chosen subscription is still active and its subscription-specific SIM state is `SIM_STATE_READY`.
- [x] Gateway heartbeat now reports the actual SMS-permission and selected-SIM readiness flags instead of hardcoded false values.
- [x] Foreground notification reports the permission/SIM readiness summary. It continues to state that queue dispatch and SMS sending remain disabled.
- [x] Added Robolectric tests for missing permissions, permissions granted without a selected SIM, and a selected but inactive SIM.
- [ ] CI validation for this change is pending.
- [ ] Physical-device validation across single-SIM and multi-SIM phones, including one and multiple Globacom SIMs, remains pending.

**Safety boundary:** this is readiness reporting only. It does not auto-select a SIM, claim controller work, transmit SMS, or wire the 3030 result-submission API into the foreground worker. Restart recovery remains reconciliation-only and must be connected to a safely configured transaction gate before any queue execution is enabled. The post-reply quiet period must not be guessed from the separate four-minute cancellation cooldown.


## 30. Immediate next-query policy after accepted 3030 reply — 16 Nov 2026

- [x] User explicitly selected **zero seconds** for the post-reply quiet period. This is a distinct policy from the four-minute cancellation cooldown.
- [x] Updated the worker-core transaction gate to accept a zero-duration post-reply interval while still rejecting negative durations.
- [x] Added a deterministic test proving that, after a valid reply is accepted and staged by the gate, the next transaction may begin at the same timestamp.
- [ ] Wire the zero-duration configuration into the Android foreground worker together with durable transaction restoration, restart recovery, inbox processing and guarded controller result submission.
- [ ] Run the combined Android/worker-core CI suite before publishing an updated APK.
- [ ] Validate on a physical Globacom SIM with duplicate, delayed and out-of-order 3030 messages.

**Important protocol risk:** 3030 replies do not echo the requested device/partner identifier. With a zero-second drain, a delayed duplicate that arrives only after the next transaction starts may be indistinguishable from that next transaction's reply. Exact fingerprint duplicates, response deadlines, durable attempt matching and quarantine remain mandatory, but they cannot eliminate this protocol-level ambiguity. Live dispatch remains disabled until the integrated flow and physical-device tests pass.
