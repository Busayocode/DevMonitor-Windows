#!/usr/bin/env python3
"""Apply a gzip-compressed unified diff to an extracted DevMonitor source tree."""
import gzip
import subprocess
import sys
from pathlib import Path

if len(sys.argv) != 3:
    raise SystemExit("Usage: apply_patch.py PATCH_GZ TARGET_DIRECTORY")
patch_path = Path(sys.argv[1])
target = sys.argv[2]
with gzip.open(patch_path, "rb") as stream:
    patch = stream.read()
subprocess.run(
    ["git", "apply", f"--directory={target}", "-"],
    input=patch,
    check=True,
)
print(f"Applied trial source overlay to {target}.")
