from pathlib import Path

# Public client-side API key: application/API restrictions must remain enabled
# in Google Cloud Console. This is intentionally part of the reproducible build.
API_KEY = "AIzaSyD2pWvYFibsh1-i0q62MhEWdYNaomTTaWk"
properties = Path("gradle.properties")
if not properties.is_file():
    raise SystemExit("Top-level gradle.properties was not found.")

lines = properties.read_text(encoding="utf-8").splitlines()
lines = [line for line in lines if not line.strip().startswith("DRIVE_REPORT_API_KEY=")]
lines.append(f"DRIVE_REPORT_API_KEY={API_KEY}")
properties.write_text("\n".join(lines) + "\n", encoding="utf-8")
print("Embedded configured Online Reports API key in top-level gradle.properties.")
