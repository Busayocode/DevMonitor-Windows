## Android v338 — SMS Checker daily history trial (2026-11-07)

- Implemented two independent Android SQLite history databases: `sms_checker_devices_history.db` and `sms_checker_partners_history.db`.
- Completed checks are appended with local activity date, timestamp, code, status, parsed result and failure detail. Rechecks do not erase earlier attempts.
- Whole-day view selects a date and displays the latest result per code; per-code view displays all completed checks during the current and previous calendar months.
- Existing legacy success/not-found entries are imported once from each working database where available. Previously unsaved activity cannot be reconstructed.
- Clearing/replacing the active code list does not clear daily history. Payment/MOM calculations and SMS query rules were not changed.
- GitHub Actions Android release build passed; APK certificate identity verified. Physical-device and month-rollover tests remain pending.
- Windows/PC parity and cloud backup of these two new databases are not included in this Android-only trial.
- Isolated branch: `History`; build run: https://github.com/Busayocode/DevMonitor-Windows/actions/runs/42372114195.


## Merge caution — independent main-branch SMS Checker work (user-confirmed, 2026-11-07)

- The user has clarified that the main branch is independently receiving additional SMS Checker features and that its current Android version number is now **338**.
- The History branch daily-history trial was separately built as **v338 from the older v337 baseline**. The matching version number is coincidental; these are not the same source state and must not be treated as compatible release packages.
- **Do not merge the History branch wholesale into main, do not replace main's source ZIP with the History v338 ZIP, and do not assume a clean cherry-pick.**
- Before integration, fetch the actual latest main source and inspect its SMS Checker changes against the History trial. Reconcile overlapping files and preserve all newer main-branch functionality.
- Integrate only the daily-history feature and its required schema/UI/migration pieces after a deliberate code-level comparison. Resolve conflicts manually and retest all existing SMS Checker flows.
- After integration, set a new Android versionCode/versionName greater than the actual latest main version. Do not reuse v338 for the merged build.
- Keep History isolated until the user approves integration. No merge or main-branch write is authorized by this note.

