# DevMonitor Android–Windows Parity and Release Naming Rules

Status: permanent development/release procedure
Repository: Busayocode/DevMonitor-Windows
Applies to every future Android and Windows source ZIP prepared for this repository.

## 1. Platform-specific source ZIP names

Use a platform marker immediately before the version identifier. Keep the existing `.zip` extension and retain descriptive text after the version.

- Android: `DevMonitor-An-v293-4.7.0-TARGET-SHORT-CHANGE-DESCRIPTION.zip`
- Windows/PC: `DevMonitor-PC-v280-4.7.0-TARGET-SHORT-CHANGE-DESCRIPTION.zip`

The numbers above are examples of the naming format, not claims that those builds have been produced. Increment each platform's own build number from its actual latest source version. Do not reuse a version number or pretend a package was built/tested when it was not.

Use exactly `An` for Android and `PC` for Windows. Do not rename the internal app package, Android versionCode/versionName, Windows assembly version, project folders, APKs, installer files, or executable solely to apply this source-ZIP naming convention. Those internal version values should be updated only as part of the normal release/versioning process.

## 2. GitHub workflow compatibility

The current `.github/workflows/build-devmonitor.yml` detects Android and Windows ZIPs by inspecting their contents, not by requiring a particular filename. Android detection checks for the Gradle wrapper, settings file, and app Gradle build file. Windows detection checks for `DevMonitor.Windows.csproj`.

Therefore the `An` and `PC` source-ZIP filename markers do not, by themselves, break the workflow. Keep these safeguards:

1. Preserve the `.zip` extension and put source project files inside each ZIP.
2. Upload/commit the intended source ZIP(s). When multiple source ZIPs are committed together, verify the Actions log's “Selected Android ZIP” and “Selected Windows ZIP” lines to ensure the intended versions were selected.
3. The workflow selects the naturally highest-sorting filename among the candidate ZIPs for each platform. When a push includes more than one ZIP for the same platform, mixed legacy and new naming conventions can affect sorting. Prefer committing only the intended updated ZIP per platform in a build-triggering commit, or use the workflow's `workflow_dispatch` input `source_zip` when intentionally building one exact source ZIP (that manual input is a single ZIP and is not a substitute for checking whether it contains Android, Windows, or both).
4. Do not alter the workflow's source detection just to accommodate these markers; if workflow selection logic is changed in the future, test it against both new filename patterns.

The source-ZIP prefix does not automatically rename the APK or Windows installer produced by the workflow. Those are separate artifact naming steps.

## 3. Mandatory parity review before packaging

Before preparing a new Android or Windows package:

1. Identify the exact source ZIP and version currently used for each platform. Inspect the actual source, not just the filename.
2. Compare the platform being changed against the latest available source of the other platform.
3. Update `ANDROID_WINDOWS_PARITY_MATRIX.md` with each relevant feature and its status:
   - Shared — complete on both platforms
   - Android → PC — needs Windows port
   - PC → Android — needs Android port
   - Platform-specific — intentionally not shared
   - Needs verification — source exists but working build/runtime is unverified
   - Blocked — dependency, design, or test needed
4. Update `CHANGE_LOG.md` with concrete changes and affected files; update `MASTER_WORK_SHEET.md` with current baselines, open gaps, and next actions.
5. Preserve the existing payment calculations and other protected business rules unless the user explicitly authorizes a change.
6. Separate source inspection, ZIP integrity, compile/build, and runtime/device testing. Never describe one as proof of another.
7. List feature gaps that were intentionally not ported and why. Do not silently omit platform-specific features or introduce fake equivalents.
8. Include the parity matrix, change log, master worksheet, and a release note in the source package when those files are part of the current source baseline; keep this procedure document with future packages too.

## 4. Cross-platform feature register fields

For each meaningful feature, record: feature name; originating platform; first source version; relevant files/components; counterpart status; porting decision; test evidence; blocker/dependency; and next action. Mark a feature shared only after checking both implementations and documenting available verification evidence.

## 5. Current workflow boundary

The workflow builds Android and Windows independently when it finds corresponding project sources. A Windows-only ZIP cannot carry Android source changes that are not in it, and an Android-only ZIP cannot carry Windows changes that are not in it. Keep the two source histories and versions distinct, then use the parity register to coordinate them.