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
- [x] Add isolated Kotlin transaction-safety core with tests (Android persistence adapter and CI validation pending).
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
- [ ] Persist job, attempt, lease and send state across process interruption.
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
3. Complete worker-core CI and implement durable Android transaction-state persistence before selecting the compliant foreground-service type.
4. Connect one worker to a test controller and run one controlled 3030 transaction with ambiguity protection.
5. Add a second worker and validate queue assignment, isolation and recovery.
6. Integrate the Gateway transport into the separate Gateway edition and run permission/regression checks.
7. Expand to three or more workers, then review release readiness and infrastructure cost.

## 8. Evidence and status discipline

Use these labels consistently:

- **Design approved:** requirements documented; no implementation implied.
- **Implemented in source:** code/schema exists; not necessarily tested.
- **CI validated:** automated checks passed in GitHub; not equivalent to live service/device tests.
- **Device tested:** explicitly exercised on a physical device.
- **Deployed:** running in a selected environment with operational evidence.
- **Release accepted:** all agreed acceptance tests passed and the user approved promotion.

Never describe the current Gateway APK as the completed Gateway 5.0 product. It is an authentication/client prototype only.
