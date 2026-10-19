# DevMonitor Repository / Agent Handoff
Date: 2026-10-18
Repository: https://github.com/Busayocode/DevMonitor-Windows
Branch: main

## Context and authority
This chat/account did not produce the last application source ZIP. Treat this repository as the future development repo, but do not assume this agent has the missing conversation's full feature intent. The app's own master sheet and change log remain authoritative. The repository root currently does not expose MASTER_WORK_SHEET.md or CHANGE_LOG.md, and the latest contents of those documents have not yet been verified here. They may be inside a source ZIP or elsewhere.

## Current source inventory verified
- Latest Android source ZIP currently visible at repository root: DevMonitor-4.7.0-TARGET-v277-ANDROID-EXPECTED-TO-DATE-1.zip (about 4.71 MB). A separate v279 onboarding/partner-cleanup source ZIP was prepared in ChatGPT on 2026-10-19 but has not yet been uploaded to the repository.
- Latest Windows source ZIP visible at root: DevMonitor-Windows-Native-v278-TARGET-WPF-COMPILE-FIX.zip.
- DevMonitor-4.7.0-TARGET-v279-DIRECT-UPLOAD-TEST.zip is NOT app source. It is a 239-byte transfer test containing only TRANSFER-TEST.txt. Never build or treat it as a release.
- Recent Android ZIP filenames v255–v275 indicate iteration themes: earnings-card partner filter; photographic earnings flyer; share-resolution estimate, card sharpness and label clarity; dashboard partners card/profile builder; partner-performance flyer; preserving background checks; search cards backup/restore; fork compile fix; FRC weekly-payment separation; share-card next-tier earnings; historical payment eligibility gate; payment-card gate alignment; strict payment-card history gate. Filenames are hints, not proof of implementation/completion.

## Automation work now in the repository
1. .github/workflows/build-devmonitor.yml — workflow name "Build DevMonitor Android and Windows".
   - Push trigger watches ZIP files and this workflow on main/master; manual dispatch accepts optional exact source_zip path.
   - The detect job inspects ZIP contents, not just filenames. Android requires a Gradle wrapper, settings.gradle(.kts), and app/build.gradle(.kts). Windows requires DevMonitor.Windows.csproj.
   - Builds run only for recognized source types; source ZIPs are naturally sorted for fallback selection.
   - The v279 dummy should be ignored as invalid source; v277 is currently the newest Android source ZIP visible in the repository.
2. `.github/workflows/dropbox-zip-intake.yml` — REMOVED on 2026-10-19 by commit `4820458be735bf5bb53766f9ebf163befa983cbf` at the user's request. Dropbox intake is no longer part of the workflow; use the normal GitHub ZIP upload and build workflow.
3. .github/workflows/retain-devmonitor-history.yml — exists. Review its references before relying on it; earlier notes flagged that it may reference a workflow filename that differs from the current build-devmonitor.yml.
4. Direct GitHub push was tested via Git-Data API with a tiny dummy ZIP. This proves the small-file path only. It does NOT prove that uploading a real 4–5 MB Android ZIP through the same connector is reliable. Test with a genuine source ZIP before claiming large direct push is solved.
5. The transfer-test commit was 185f2f398e80a81b9da1deb1c6c6e85231470d11 (dummy only).

## Latest observed workflow failure
Run: https://github.com/Busayocode/DevMonitor-Windows/actions/runs/39130434531
- Detect succeeded; Windows build succeeded.
- Android failed during SDK setup, before Gradle/Kotlin compilation.
- Error: "Warning: Failed to find package 'tools'"; sdkmanager tools exited with code 1 in android-actions/setup-android@v3.
- This run does not establish whether the Android app source compiles. Fix the SDK setup independently. Review the Android SDK setup step in .github/workflows/build-devmonitor.yml; remove/replace the obsolete standalone SDK package request for "tools" with supported cmdline-tools setup. Make a minimal workflow-only change, then rerun.

## App-development task: do not guess
A prior assistant hypothesized a Sync Bridge task involving NextLevelScreen.kt and SyncBridgeClient.kt, with unresolved bridge references. This has not been verified against v277 or the authoritative master sheet/change log. Treat it as a lead only, not permission to patch blindly.
Before feature work, extract/read the master sheet and change log from the authoritative source ZIP or ask the user to provide them. Identify the actual last app build and the exact intended changes missing from it.

## Project safety constraints
- Do not modify/refactor established payment formulas, payment-cycle/progression rules, or estimator logic without explicit user approval. Payment logic was finalized after substantial work.
- Keep changes minimal and scoped; preserve unrelated features.
- Maintain phone-first design and user-initiated heavy operations.
- Never claim a build passed if it failed before compilation.
- Keep ZIP name, versionName and versionCode aligned; never treat the v279 transfer test as source.

## Next actions
1. Recover and inspect the authoritative master sheet and change log.
2. Confirm the actual last app ZIP and missing intended changes; inspect source before editing.
3. Fix the SDK setup issue separately from app source work.
4. Verify large-file direct push using a genuine ZIP or select a tested upload route; keep manual source_zip dispatch.
5. Record app changes in the app's own master sheet/change log and repository automation changes in this handoff/change log.

## Android SDK correction — 2026-10-18
- Updated `.github/workflows/build-devmonitor.yml` to use `android-actions/setup-android@v4` (commit `df545c427fee452b4d9faa4a29bea0a0cc6242de`). The prior v3 action requested the removed `tools` SDK package and exited before Gradle compilation.
- Verification run: https://github.com/Busayocode/DevMonitor-Windows/actions/runs/39144857359. At handoff update, it was still in progress; do not claim the Android build passes until the job finishes and release artifacts are checked.
- Windows build logic was not changed. If Android now reaches Gradle and fails, capture the new error and handle it as a separate source/build issue. Do not modify payment logic or Drive settings as part of this SDK fix.


## Dropbox intake retirement — 2026-10-19
- Deleted `.github/workflows/dropbox-zip-intake.yml` in commit `4820458be735bf5bb53766f9ebf163befa983cbf` because Dropbox intake is no longer used.
- `.github/workflows/build-devmonitor.yml` and `.github/workflows/retain-devmonitor-history.yml` remain.
- The small v279 transfer-test ZIP at repository root remains a dummy and must never be built as app source.

## Android release signing follow-up — 2026-10-18
- Run `39144857359` (https://github.com/Busayocode/DevMonitor-Windows/actions/runs/39144857359) fixed the retired SDK-tools setup issue: Android SDK setup completed and Gradle compiled Kotlin and Java.
- That run then failed only at `:app:validateSigningRelease`: Gradle expected `/build-source/app/release-key.jks`, but the workflow had decoded the configured `RELEASE_KEYSTORE_BASE64` secret to `/build-source/release-key.jks`.
- Workflow commit `cb8f874ebebe8d91cce2d71e65ebab659a122c16` now copies the decoded keystore to both project root and `app/release-key.jks`. This is a workflow path correction; it does not expose or change secret values.
- Verify the workflow run triggered by this commit and check for `DevMonitor-Android-Release` artifact before claiming the Android release build succeeds. If signing advances and another error occurs, diagnose that exact error separately.
- Windows build logic, Android source ZIP, payment logic, Drive configuration values, and application features were not changed by this correction.
