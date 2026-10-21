# DevMonitor Android–Windows Parity Register

Last updated: 2026-10-21
Purpose: single cross-platform checklist so changes made on either app are reviewed for the other app before a release is packaged.

## How to maintain this register

- Update this file whenever either platform changes.
- Check the actual source ZIPs and their contents. A version filename or ZIP integrity check is not evidence that a feature compiles or works.
- Use the statuses below. Do not mark Shared — verified without evidence from both platforms.
- Record source version, relevant files, test evidence, and the next action in each change-log entry.
- Keep platform-only features explicit so they are not mistakenly treated as missing parity.
- Do not change finalized payment formulas, targets, reach rules, or cycle calculations unless the user explicitly authorizes it.

## Status definitions

- **Shared — verified:** implemented and verified on both platforms.
- **Android → PC:** Android has the feature; Windows still needs it or needs parity review.
- **PC → Android:** Windows has the feature; Android still needs it or needs parity review.
- **Platform-specific:** intentionally platform-only; record why.
- **Needs verification:** source suggests implementation exists, but build/runtime evidence is missing.
- **Blocked:** awaiting design, dependency, or test.

## Baselines currently known to this development context

These are source-package references, not claims of successful builds:

- Android source package most recently generated in the current development context: `DevMonitor-4.7.0-TARGET-v292-AD-FREQUENCY-CORRECTION.zip`. ZIP/build not confirmed by GitHub in the context of this register.
- Windows source package most recently generated in the current development context: `DevMonitor-Windows-Native-v279-ANDROID-PARITY-SHARE-CARD-LEGIBILITY.zip`. ZIP integrity passed; Windows compilation and runtime were not confirmed; it was not pushed to GitHub.
- The repository's current GitHub workflow is `.github/workflows/build-devmonitor.yml`; it detects Android and Windows source by inspecting ZIP contents, not by requiring platform text in the filename.

Refresh these baseline references against the actual files and latest GitHub workflow run before each release. If later packages exist, replace these references with the actual latest source inputs and evidence.

## Cross-platform feature register

| Feature / area | Android status | Windows/PC status | Decision / next action |
|---|---|---|---|
| Shareable result-card readability | **Needs verification** — v290/v291/v292 lineage includes Android share-card legibility changes; confirm exact source before porting | **Needs verification** — v279 source package includes share-card text fitting, stronger contrast, and supporting text changes; compile and visually inspect output | Compare final renderers, test long and short content on both, then mark shared only after visual verification |
| Quick Check result-card composition to match Add List | **Needs verification** — user-specified Android result-card behavior; inspect latest source | **Needs verification** — inspect Windows workflow and result card | Compare card composition and carry over only the matching presentation behavior; do not alter normal Add List → Start/Refresh All flow |
| SMS Checker workflow and result presentation | **Needs verification** — Android behavior is the reference for phone interaction | **Needs verification** — verify equivalent PC flow and document intentional desktop differences | Compare Add List, Quick Check, Start/Refresh All, result card and shareable card as separate concerns |
| First-launch location permission | **Platform-specific** — Android v291 change requests location permission only when a feature needs it | **Platform-specific** — Windows has no Android runtime location-permission dialog | Keep separate; Windows should request/handle location only using its own platform capability when needed |
| AdMob report-export reward and interstitial thresholds | **Needs verification** — v292 intended first 10 exports free, then rewarded unlock of 5 more; interstitial after 10 successful SMS-monitoring results; build/runtime not confirmed | **Platform-specific** — current Windows source has no AdMob integration | Do not add a fake desktop ad flow. If desktop export gating is ever desired, ask for an explicit product decision and design a Windows-appropriate approach |
| Payment calculator / payment cards / MOM rules | **Protected; verify parity before changing** | **Protected; verify parity before changing** | Never refactor or change formulas, cycle rules, targets, or reach logic without explicit user authorization. Compare outputs against agreed fixtures |
| Operational archive typed editors | **Needs verification** | **Known parity gap in prior review** | Inspect current sources; if Android already has the expected editor, record exact files and port the appropriate desktop equivalent |
| Phone query-result/history integration into monitoring calculations | **Needs verification** | **Known parity gap in prior review** | Trace source-to-storage-to-calculation path on both platforms; do not mark complete based on UI presence alone |
| Company activation database/report history | **Needs verification** | **Known parity gap in prior review** | Compare data model, persistence, report view and access rules; document the intentional platform design |
| Role and screen workflow parity | **Needs verification** | **Known parity gap in prior review** | Compare roles, visible actions, navigation, and permissions; record intended platform-only differences |
| Cross-platform golden/regression tests | **Needs verification** | **Needs verification** | Establish shared test cases for core rules and representative datasets; do not claim parity until both pass |
| Sync conflict, tombstone and resumability controls | **Needs verification** | **Known parity gap / previously left disabled pending design and tests** | Keep disabled until semantics and recovery tests are agreed; do not silently enable partial sync behavior |
| Online report/Drive configuration | **Needs verification** — prior reports of endless loading / configuration mismatch | **Needs verification** — inspect separately if the Windows app uses an online-report counterpart | Treat as a separate issue. Do not change secrets, folder IDs or Drive build injection as part of unrelated parity work |

## Per-build parity checklist

For every new Android or Windows source package, complete the following before calling it ready:

- [ ] Record the exact old and new source ZIP names and internal version values.
- [ ] Inspect changed source files and identify user-visible and data/business-rule changes.
- [ ] Compare against the latest available source of the other platform.
- [ ] Add a row or update the existing row for every feature changed.
- [ ] Mark porting direction, intentional platform-only behavior, blocker, or verification need.
- [ ] Preserve protected business rules unless explicitly authorized to change.
- [ ] Update `CHANGE_LOG.md` and `MASTER_WORK_SHEET.md` in the package/source baseline.
- [ ] Use the platform-prefixed ZIP naming convention in `DEVELOPMENT_PARITY_AND_VERSION_NAMING.md`.
- [ ] Verify ZIP integrity.
- [ ] Build each affected platform using the intended source ZIP and inspect the workflow's selected ZIP paths.
- [ ] Report compile/build results separately from runtime/device tests.
- [ ] Do not mark a feature complete until evidence is recorded for the relevant platform(s).
- [ ] List any known parity gaps and explicitly deferred ports in the final hand-off.

## Per-feature change record template

| Field | Record |
|---|---|
| Feature | |
| First changed on | Android / PC |
| Source version(s) | |
| Relevant files/components | |
| Counterpart status | Android → PC / PC → Android / Shared / Platform-specific / Needs verification / Blocked |
| Porting decision | |
| Build/test evidence | |
| Known limitations | |
| Next action / owner | |

## Release note

This register is a coordination mechanism, not an automated source-code diff service. Each package must be inspected and the register updated. If repository automation is added later, it should produce a report for review rather than silently asserting functional parity from filenames or matching file names alone.
