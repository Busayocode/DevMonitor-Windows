## Agents database upgrade — next approved work (2026-10-22)

Tracked in GitHub issue #1: https://github.com/Busayocode/DevMonitor-Windows/issues/1

Scope: editable contact import including name, all phone numbers, email addresses, postal address, and existing thumbnail/photo; reviewable duplicate detection/merge; all agent fields editable after import; and a real map location picker that persists coordinates to the agent record. The picker must support place/address search and manual map-pin selection with explicit confirmation/save. Current-device location is optional and permission-dependent. Keep postal address distinct from saved coordinates.

Implementation order:
1. Confirm the latest Android and Windows ZIPs and inspect actual Agents screens, data model/schema, and map integration.
2. Plan safe schema migration for multiple phones/emails and photo storage/reference without data loss.
3. Implement contact selection/import preview, duplicate review, and complete editable agent detail form.
4. Implement the saveable map picker and edit/reopen/clear flows.
5. Compare Android/Windows behavior and update the parity register/change log.
6. Build each affected platform and report compile/build evidence separately from device/runtime tests.

Guardrails: do not change finalized payment/MOM logic, cycle/snapshot rules, targets, or reach rules. Preserve profile-specific operational data. Current repository ZIP references must be refreshed before implementation; the documentation baseline does not prove they are still the newest packages.

# DevMonitor Master Development Worksheet

Last updated: 2026-10-21

## Permanent release rules

1. Use `ANDROID_WINDOWS_PARITY_MATRIX.md` before and after every platform change.
2. Follow `DEVELOPMENT_PARITY_AND_VERSION_NAMING.md` for platform-prefixed source ZIP filenames:
   - Android: `DevMonitor-An-v<android-build>-<version>-<change>.zip`
   - PC: `DevMonitor-PC-v<windows-build>-<version>-<change>.zip`
3. Compare each new platform source against the latest available source for the other platform before packaging.
4. Update the parity register and change log with implementation status and evidence.
5. Do not claim compile/build/runtime success based only on source inspection or ZIP integrity.
6. Do not alter finalized payment/MOM formulas or protected business rules without explicit user authorization.
7. Keep Android-only permissions and Android AdMob behavior platform-specific unless an approved equivalent is designed for Windows.
8. Inspect GitHub Actions logs to confirm the selected Android and Windows ZIP paths whenever more than one ZIP could be selected.

## Current context references (verify before release)

These are the latest package references in the development context at the time this worksheet was created, not proof of successful builds:

- Android: `DevMonitor-4.7.0-TARGET-v292-AD-FREQUENCY-CORRECTION.zip` — not confirmed pushed or built.
- Windows: `DevMonitor-Windows-Native-v279-ANDROID-PARITY-SHARE-CARD-LEGIBILITY.zip` — ZIP integrity passed; Windows compile/runtime not confirmed; not confirmed pushed.
- Workflow: `.github/workflows/build-devmonitor.yml` — detects project types by ZIP contents. It does not require a particular platform prefix.

## Trend requirement — corrected 2026-10-21

- The trend section used by the app is **month-on-month only**. Remove/avoid 7-day, 30-day, and 7/30-day trend UI or options on both Android and Windows/PC.
- Preserve the existing monthly comparison/data logic. This is a display/scope correction, not authorization to change payment formulas or MOM snapshot behavior.
- Verify the actual screen in each platform source and record build/runtime evidence before marking parity complete.

## Next packaging actions

- [ ] Inspect the actual latest Android source package and choose its next unused Android build number.
- [ ] Inspect the actual latest Windows source package and choose its next unused PC build number.
- [ ] Use the new filename convention for the next package of each platform.
- [ ] Compare source changes against the other platform and update the parity register.
- [ ] Include the parity register, naming procedure, change log and master worksheet in source packages where these project documents are maintained.
- [ ] Build each intended platform and report the selected ZIP, workflow run, compile status, and runtime test status separately.


## Full DevMonitor v352 — Globacom-first SIM routing (15 Nov 2026)

- Baseline: Full Android v351, versionCode 351, from branch `trial/direct-google-account-lan-sync`; latest pre-change CI run #185 (run ID `43647093001`) passed Android and Windows.
- New Full target: Android v352, versionCode 352. This is a separate trial branch update; production `main` remains untouched.
- Detect a Globacom SIM using the SIM subscription's home operator MCC/MNC `621/50`; carrier display name is only a fallback when the operator code is unavailable.
- Automatically select the first READY Glo SIM by physical slot order when automatic mode is active. If no READY Glo SIM exists, do not silently choose the phone's default SMS SIM; show the existing manual SIM picker.
- Manual SIM selection is an explicit override and is persisted. The selector includes an `Auto-select Glo` action to restore automatic mode.
- Both SMS send paths require an explicit active subscription ID and revalidate that the SIM is READY immediately before each send/retry. The implementation must not use `SubscriptionManager.getDefaultSmsSubscriptionId()` or `SmsManager.getDefault()` as a fallback.
- Android 12+ uses `SmsManager.createForSubscriptionId()`; earlier supported Android versions use the subscription-bound legacy factory.
- The Android Messaging app's default SMS SIM is independent of DevMonitor's selected subscription.
- Existing SMS Checker wait periods, retry sequencing, history, payments, MOM, reports, Drive and other business rules are unchanged.
- CI/runtime status: pending v352 run; do not treat source edits as a successful build or real-device verification.
