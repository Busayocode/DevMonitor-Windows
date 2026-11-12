from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
import json

# Mirror OnlineReportStore.listReports() exactly. The app sends only Accept;
# do not add identity headers, alternate corpora, or a separate folder lookup.
values = {}
for line in Path("gradle.properties").read_text(encoding="utf-8").splitlines():
    if "=" in line and not line.lstrip().startswith("#"):
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
api_key = values.get("DRIVE_REPORT_API_KEY", "")
folder_id = values.get("DRIVE_REPORT_FOLDER_ID", "")
if not api_key or not folder_id:
    raise SystemExit("Online Reports diagnostic: required Gradle properties are missing.")

query = f"'{folder_id}' in parents and trashed = false and mimeType != 'application/vnd.google-apps.folder'"
params = urlencode({
    "q": query,
    "spaces": "drive",
    "pageSize": "1000",
    "supportsAllDrives": "true",
    "includeItemsFromAllDrives": "true",
    "orderBy": "modifiedTime desc",
    "fields": "nextPageToken,files(id,name,modifiedTime,size,mimeType)",
    "key": api_key,
})
request = Request(
    "https://www.googleapis.com/drive/v3/files?" + params,
    headers={"Accept": "application/json"},
    method="GET",
)
try:
    with urlopen(request, timeout=25) as response:
        payload = json.loads(response.read().decode("utf-8"))
except HTTPError as exc:
    detail = exc.read().decode("utf-8", errors="replace")[:1200]
    raise SystemExit(f"App-equivalent Drive listing returned HTTP {exc.code}: {detail}")
except URLError as exc:
    raise SystemExit(f"App-equivalent Drive listing network error: {exc}")

files = payload.get("files") or []
supported = [
    item for item in files
    if (item.get("mimeType") == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    or (item.get("name") or "").lower().endswith((".xlsx", ".xlsm", ".xls", ".csv"))
]
print(f"App-equivalent Drive listing: {len(files)} items, {len(supported)} supported reports.")
for item in supported[:10]:
    print("Visible report:", item.get("name", "(unnamed)"))
if not supported:
    raise SystemExit("The exact in-app listing request returned no supported reports. APK compilation will continue.")
print("Online Reports live preflight passed.")
