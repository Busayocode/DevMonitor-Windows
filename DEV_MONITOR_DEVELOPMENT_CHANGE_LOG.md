## Android v338 — SMS Checker daily history trial (2026-11-07)

- Implemented two independent Android SQLite history databases: `sms_checker_devices_history.db` and `sms_checker_partners_history.db`.
- Completed checks are appended with local activity date, timestamp, code, status, parsed result and failure detail. Rechecks do not erase earlier attempts.
- Whole-day view selects a date and displays the latest result per code; per-code view displays all completed checks during the current and previous calendar months.
- Existing legacy success/not-found entries are imported once from each working database where available. Previously unsaved activity cannot be reconstructed.
- Clearing/replacing the active code list does not clear daily history. Payment/MOM calculations and SMS query rules were not changed.
- GitHub Actions Android release build passed; APK certificate identity verified. Physical-device and month-rollover tests remain pending.
- Windows/PC parity and cloud backup of these two new databases are not included in this Android-only trial.
- Isolated branch: `History`; build run: https://github.com/Busayocode/DevMonitor-Windows/actions/runs/42372114195.

