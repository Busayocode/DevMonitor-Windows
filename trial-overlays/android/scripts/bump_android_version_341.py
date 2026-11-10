from pathlib import Path
import re

build_file = Path("app/build.gradle.kts")
if not build_file.is_file():
    raise SystemExit("Android app/build.gradle.kts not found.")

text = build_file.read_text(encoding="utf-8")
pattern = r'(?m)^(\s*versionCode\s*=\s*)340(\s*)$'
matches = list(re.finditer(pattern, text))
if len(matches) != 1:
    raise SystemExit(f"Expected exactly one versionCode 340, found {len(matches)}.")
text = re.sub(pattern, r'\g<1>341\g<2>', text, count=1)

name_match = re.search(r'(?m)^(\s*versionName\s*=\s*)"([^"]+)"', text)
if not name_match:
    raise SystemExit("versionName declaration not found.")
name = name_match.group(2)
if "V340" in name:
    new_name = name.replace("V340", "V341")
elif name.endswith("-340"):
    new_name = name[:-4] + "-341"
else:
    new_name = name + "-V341"
text = text[:name_match.start(2)] + new_name + text[name_match.end(2):]

build_file.write_text(text, encoding="utf-8")
print(f"Android version advanced: {new_name} (versionCode 341)")
