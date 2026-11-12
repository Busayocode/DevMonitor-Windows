from pathlib import Path
import re

build_file = Path("app/build.gradle.kts")
if not build_file.is_file():
    raise SystemExit("Android app/build.gradle.kts not found.")
text = build_file.read_text(encoding="utf-8")
pattern = r'(?m)^(\s*versionCode\s*=\s*)346(\s*)$'
if len(list(re.finditer(pattern, text))) != 1:
    raise SystemExit("Expected exactly one versionCode 346 before advancing to 347.")
text = re.sub(pattern, r'\g<1>347\g<2>', text, count=1)
name_match = re.search(r'(?m)^(\s*versionName\s*=\s*)"([^"]+)"', text)
if not name_match:
    raise SystemExit("versionName declaration not found.")
name = name_match.group(2)
if "V346" in name:
    new_name = name.replace("V346", "V347")
elif name.endswith("-346"):
    new_name = name[:-4] + "-347"
else:
    new_name = name + "-V347"
text = text[:name_match.start(2)] + new_name + text[name_match.end(2):]
build_file.write_text(text, encoding="utf-8")
print(f"Android version advanced: {new_name} (versionCode 347)")
