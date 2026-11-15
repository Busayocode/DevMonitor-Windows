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
- [ ] Implement a compliant foreground worker service (not an Activity-owned coroutine).
- [ ] Add a persistent notification with truthful states and Pause/Resume/Stop controls.
- [x] Extend durable persistence from the SMS channel snapshot to controller job, attempt, lease and send lifecycle records (initial local schema/API implemented; CI and recovery tests pending).
- [ ] Wire durable job/attempt transitions into the controller polling/claim client and the eventual foreground worker service.
- [ ] Check Android 14+ foreground-service type/permission and boot-start restrictions.
- [ ] Provide user-guided battery-optimization settings for dedicated worker phones only.
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
2. Complete controller integration/security tests on an isolated test database; no paid deployment yet.
3. Worker-core CI passed (run #43682711793). The Android branch now contains SQLite persistence for the SMS channel snapshot plus controller job/lease and attempt lifecycle records. Validate the integrated Android build, then wire the ledger to controller claim and worker state transitions before selecting the compliant foreground-service type.
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
