from pathlib import Path
import re
p = Path("app/build.gradle.kts")
s = p.read_text(encoding="utf-8")
if len(re.findall(r"(?m)^\s*versionCode\s*=\s*351\s*$", s)) != 1:
    raise SystemExit("Expected exactly one Android versionCode 351 before v352.")
s = re.sub(r"(?m)^(\s*versionCode\s*=\s*)351(\s*)$", r"\g<1>352\g<2>", s, count=1)
m = re.search(r'(?m)^(\s*versionName\s*=\s*)"([^"]+)"', s)
if not m:
    raise SystemExit("versionName not found.")
name = m.group(2)
if "V351" in name:
    name = name.replace("V351", "V352")
else:
    name += "-GLO-SIM-AUTO-V352"
s = s[:m.start(2)] + name + s[m.end(2):]
p.write_text(s, encoding="utf-8")
print(f"Android Full edition advanced to {name}, versionCode 352.")
