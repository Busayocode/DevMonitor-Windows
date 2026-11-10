from pathlib import Path
import re

build_file = Path("app/build.gradle.kts")
if not build_file.is_file():
    raise SystemExit("Android app/build.gradle.kts not found.")
text = build_file.read_text(encoding="utf-8")
pattern = r'(?m)^(\s*versionCode\s*=\s*)342(\s*)$'
if len(list(re.finditer(pattern, text))) != 1:
    raise SystemExit("Expected exactly one versionCode 342 before advancing to 343.")
text = re.sub(pattern, r'\g<1>343\g<2>', text, count=1)
name_match = re.search(r'(?m)^(\s*versionName\s*=\s*)"([^"]+)"', text)
if not name_match:
    raise SystemExit("versionName declaration not found.")
name = name_match.group(2)
if "V342" in name:
    new_name = name.replace("V342", "V343")
elif name.endswith("-342"):
    new_name = name[:-4] + "-343"
else:
    new_name = name + "-V343"
text = text[:name_match.start(2)] + new_name + text[name_match.end(2):]
build_file.write_text(text, encoding="utf-8")
print(f"Android version advanced: {new_name} (versionCode 343)")
