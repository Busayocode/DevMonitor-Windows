from pathlib import Path

# Canonical company Online Reports folder confirmed by the user from the
# successful phone-build configuration.
FOLDER_ID = "1d2mCmBiFgQXoIYHAvgjXAD_JgSvITbwI"
properties = Path("gradle.properties")
if not properties.is_file():
    raise SystemExit("Top-level gradle.properties not found.")

lines = properties.read_text(encoding="utf-8").splitlines()
lines = [line for line in lines if not line.startswith("DRIVE_REPORT_FOLDER_ID=")]
lines.append(f"DRIVE_REPORT_FOLDER_ID={FOLDER_ID}")
properties.write_text("\n".join(lines) + "\n", encoding="utf-8")
print("Applied the user-confirmed Online Reports company folder ID to top-level gradle.properties.")
