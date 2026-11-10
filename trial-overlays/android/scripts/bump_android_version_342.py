from pathlib import Path
import re
build_file = Path("app/build.gradle.kts")
if not build_file.is_file(): raise SystemExit("Android app/build.gradle.kts not found.")
text = build_file.read_text(encoding="utf-8")
pattern = r'(?m)^(\s*versionCode\s*=\s*)(?:340|341)(\s*)
if len(list(re.finditer(pattern, text))) != 1: raise SystemExit("Expected exactly one versionCode 340 or 341 before advancing to 342.")
text = re.sub(pattern, r'\g<1>342\g<2>', text, count=1)
name_match = re.search(r'(?m)^(\s*versionName\s*=\s*)"([^"]+)"', text)
if not name_match: raise SystemExit("versionName declaration not found.")
name = name_match.group(2)
new_name = name.replace("V340", "V342").replace("V341", "V342") if ("V340" in name or "V341" in name) else (name[:-4] + "-342" if name.endswith(("-340", "-341")) else name + "-V342")
text = text[:name_match.start(2)] + new_name + text[name_match.end(2):]
build_file.write_text(text, encoding="utf-8")
print(f"Android version advanced: {new_name} (versionCode 342)")

if len(list(re.finditer(pattern, text))) != 1: raise SystemExit("Expected exactly one versionCode 341 before advancing to 342.")
text = re.sub(pattern, r'\g<1>342\g<2>', text, count=1)
name_match = re.search(r'(?m)^(\s*versionName\s*=\s*)"([^"]+)"', text)
if not name_match: raise SystemExit("versionName declaration not found.")
name = name_match.group(2)
new_name = name.replace("V341", "V342") if "V341" in name else (name[:-4] + "-342" if name.endswith("-341") else name + "-V342")
text = text[:name_match.start(2)] + new_name + text[name_match.end(2):]
build_file.write_text(text, encoding="utf-8")
print(f"Android version advanced: {new_name} (versionCode 342)")
