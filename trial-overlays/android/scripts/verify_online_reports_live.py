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

query = f"'{folder_id}' in parents and trashed = false"
headers = {
    "Accept": "application/json",
    "X-Android-Package": "com.aziitech.simregmonitor",
    "X-Android-Cert": "8AC31B96527DF89683D584A6D599100D885DF536",
}
def get_json(url):
    request = Request(url, headers=headers, method="GET")
    with urlopen(request, timeout=25) as response:
        return json.loads(response.read().decode("utf-8"))

errors = []
for corpus in ("user", "allDrives"):
    params = urlencode({
        "q": query,
        "spaces": "drive",
        "corpora": corpus,
        "pageSize": "10",
        "supportsAllDrives": "true",
        "includeItemsFromAllDrives": "true",
        "fields": "files(id,name,mimeType,modifiedTime)",
        "key": api_key,
    })
    try:
        payload = get_json("https://www.googleapis.com/drive/v3/files?" + params)
    except HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:1200]
        errors.append(f"{corpus}: HTTP {exc.code}: {detail}")
        print(f"Online Reports search corpus={corpus}: HTTP {exc.code}")
        continue
    except URLError as exc:
        errors.append(f"{corpus}: network error: {exc}")
        print(f"Online Reports search corpus={corpus}: network error")
        continue
    files = payload.get("files") or []
    print(f"Online Reports search corpus={corpus}: {len(files)} file(s)")
    for item in files[:5]:
        print("Visible report:", item.get("name", "(unnamed)"))
    if files:
        print(f"Online Reports live preflight passed using corpus={corpus}.")
        raise SystemExit(0)

# Check whether the folder itself is visible; this distinguishes a bad share/folder
# reference from a visible but empty folder.
folder_url = "https://www.googleapis.com/drive/v3/files/" + folder_id + "?" + urlencode({
    "fields": "id,name,mimeType,driveId,parents",
    "supportsAllDrives": "true",
    "key": api_key,
})
try:
    folder = get_json(folder_url)
    print("Configured folder is visible:", folder.get("name", "(unnamed)"))
    print("Configured folder driveId:", folder.get("driveId", "(not in a shared drive)"))
except HTTPError as exc:
    detail = exc.read().decode("utf-8", errors="replace")[:1200]
    errors.append(f"folder metadata: HTTP {exc.code}: {detail}")
    print(f"Configured folder metadata lookup: HTTP {exc.code}: {detail}")
except URLError as exc:
    errors.append(f"folder metadata: network error: {exc}")

if errors:
    raise SystemExit("Online Reports preflight failed. " + " | ".join(errors))
raise SystemExit(
    "Online Reports preflight: the configured folder is visible but contains no child files "
    "in either the user's Drive corpus or all shared drives. Verify that the reports are direct "
    "children of this folder and that the folder sharing grants the app's API-key-only access."
)
