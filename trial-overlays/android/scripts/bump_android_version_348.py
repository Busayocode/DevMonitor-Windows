from pathlib import Path
import re

build_file = Path("app/build.gradle.kts")
if not build_file.is_file():
    raise SystemExit("Android app/build.gradle.kts not found.")
source = build_file.read_text(encoding="utf-8")
pattern = r'(?m)^(\s*versionCode\s*=\s*)347(\s*)$'
if len(list(re.finditer(pattern, source))) != 1:
    raise SystemExit("Expected exactly one versionCode 347 before advancing to 348.")
source = re.sub(pattern, r'\g<1>348\g<2>', source, count=1)
match = re.search(r'(?m)^(\s*versionName\s*=\s*)"([^"]+)"', source)
if not match:
    raise SystemExit("versionName declaration not found.")
name = match.group(2)
if "V347" in name:
    updated = name.replace("V347", "V348")
elif name.endswith("-347"):
    updated = name[:-4] + "-348"
else:
    updated = name + "-V348"
source = source[:match.start(2)] + updated + source[match.end(2):]
build_file.write_text(source, encoding="utf-8")
print(f"Android version advanced to {updated} (versionCode 348)")
