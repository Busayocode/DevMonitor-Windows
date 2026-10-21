# DevMonitor Development Change Log

## 2026-10-21 — Trend scope corrected to month-on-month only

- User clarified that the trend section actually used is month-on-month only.
- 7-day, 30-day, and 7/30-day trend views/selectors are not part of the intended app experience and must not be added or shown.
- Apply the same requirement to Android and Windows/PC. Keep existing monthly comparisons and data rules intact; do not alter finalized payment logic or MOM snapshot behavior.
- This documentation update records the requirement only. The actual app screen still needs to be updated/verified against the latest source ZIPs before claiming implementation or build success.

## 2026-10-21 — Permanent cross-platform parity tracking and platform-prefixed source ZIP names

- Added `ANDROID_WINDOWS_PARITY_MATRIX.md` as a persistent register for tracking features originating on Android or Windows, porting direction, intentional platform-specific behavior, blockers, and build/runtime verification evidence.
- Added `DEVELOPMENT_PARITY_AND_VERSION_NAMING.md` with mandatory source ZIP naming and pre-package parity review procedures.
- New source ZIP naming format:
  - Android: `DevMonitor-An-v<android-build>-<version>-<change>.zip`
  - Windows/PC: `DevMonitor-PC-v<windows-build>-<version>-<change>.zip`
- The current GitHub workflow `.github/workflows/build-devmonitor.yml` identifies projects by inspecting ZIP contents. The platform marker alone does not break Android/Windows detection.
- Caution: when multiple ZIPs for the same platform are candidates in one push, workflow selection uses natural filename sorting. Prefer one intended ZIP per platform in each build-triggering commit and verify the selected source paths in the Actions logs.
- No application source, payment logic, project version fields, workflow code, signing configuration, or Drive configuration was changed in this documentation-only commit.
- This documentation commit does not itself trigger the ZIP-based build workflow.
- Commits:
  - `643ea1001afbfe72cdb31883b4ec908f39898fc8` — naming and procedure document
  - `e79a9f2f23a977a613445e08012f462470d1ac09` — parity register

## Release verification status

This entry documents process changes only. It does not claim that Android or Windows has been built or tested by this commit.
