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
