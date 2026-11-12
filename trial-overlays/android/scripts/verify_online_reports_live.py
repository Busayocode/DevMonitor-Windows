from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
import json

# Reproduce OnlineReportStore.listReports() exactly: same query, fields,
# spaces, pagination options and API-key authentication. Do not add Android
# identity headers or a separate folder-metadata request; neither is sent by
# the app itself.
props = Path("gradle.properties").read_text(encoding="utf-8").splitlines()
values = {}
for line in props:
    if "=" in line and not line.lstrip().startswith("#"):
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
api_key = values.get("DRIVE_REPORT_API_KEY", "")
folder_id = values.get("DRIVE_REPORT_FOLDER_ID", "")
if not api_key or not folder_id:
    raise SystemExit("Online Reports diagnostic: top-level API key or folder ID is missing.")

query = f"'{folder_id}' in parents and trashed = false and mimeType != 'application/vnd.google-apps.folder'"
fields = "nextPageToken,files(id,name,modifiedTime,size,mimeType)"
params = urlencode({
    "q": query,
    "spaces": "drive",
    "pageSize": "1000",
    "supportsAllDrives": "true",
    "includeItemsFromAllDrives": "true",
    "orderBy": "modifiedTime desc",
    "fields": fields,
    "key": api_key,
})
url = "https://www.googleapis.com/drive/v3/files?" + params
request = Request(url, headers={"Accept": "application/json"}, method="GET")
try:
    with urlopen(request, timeout=25) as response:
        payload = json.loads(response.read().decode("utf-8"))
except HTTPError as exc:
    detail = exc.read().decode("utf-8", errors="replace")[:1600]
    raise SystemExit(f"Exact Online Reports request returned HTTP {exc.code}: {detail}")
except URLError as exc:
    raise SystemExit(f"Exact Online Reports request could not reach Google Drive: {exc}")

files = payload.get("files") or []
supported = []
extensions = (".xlsx", ".xlsm", ".xls", ".csv")
xlsx_mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
for item in files:
    name = (item.get("name") or "").strip()
    mime = (item.get("mimeType") or "").strip()
    if mime == xlsx_mime or name.lower().endswith(extensions):
        supported.append(item)

print(f"Exact app-equivalent Drive query returned {len(files)} item(s); {len(supported)} supported report(s).")
for item in supported[:10]:
    print("Visible report:", item.get("name", "(unnamed)"))
if not supported:
    raise SystemExit(
        "Exact app-equivalent Drive query returned no supported report files. "
        "Android compilation will continue; compare this run's request result with the phone build."
    )
print("Online Reports live preflight passed using the exact in-app listing request.")
