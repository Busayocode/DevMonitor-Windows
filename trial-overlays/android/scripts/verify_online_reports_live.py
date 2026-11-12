from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
import json

props = Path("gradle.properties").read_text(encoding="utf-8").splitlines()
values = {}
for line in props:
    if "=" in line and not line.lstrip().startswith("#"):
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
api_key = values.get("DRIVE_REPORT_API_KEY", "")
folder_id = values.get("DRIVE_REPORT_FOLDER_ID", "")
if not api_key or not folder_id:
    raise SystemExit("Online Reports preflight: API key or company folder ID is missing from top-level gradle.properties.")

query = f"'{folder_id}' in parents and trashed = false and mimeType != 'application/vnd.google-apps.folder'"
params = urlencode({
    "q": query,
    "spaces": "drive",
    "pageSize": "5",
    "supportsAllDrives": "true",
    "includeItemsFromAllDrives": "true",
    "fields": "files(id,name,mimeType,modifiedTime)",
    "key": api_key,
})
request = Request(
    "https://www.googleapis.com/drive/v3/files?" + params,
    headers={
        "Accept": "application/json",
        "X-Android-Package": "com.aziitech.simregmonitor",
        "X-Android-Cert": "8AC31B96527DF89683D584A6D599100D885DF536",
    },
    method="GET",
)
try:
    with urlopen(request, timeout=25) as response:
        payload = json.loads(response.read().decode("utf-8"))
except HTTPError as exc:
    detail = exc.read().decode("utf-8", errors="replace")[:1200]
    raise SystemExit(f"Online Reports preflight failed: Google Drive HTTP {exc.code}: {detail}")
except URLError as exc:
    raise SystemExit(f"Online Reports preflight could not reach Google Drive: {exc}")
files = payload.get("files") or []
if not files:
    raise SystemExit(
        "Online Reports preflight: Google Drive accepted the app identity and API key but returned zero files. "
        "Check that the configured company folder ID is correct, contains supported reports, and is shared "
        "with link-view access for this API-key-only listing."
    )
print(f"Online Reports live preflight passed: Google Drive returned {len(files)} file(s) for the configured company folder.")
for item in files[:5]:
    print("Visible report:", item.get("name", "(unnamed)"))
