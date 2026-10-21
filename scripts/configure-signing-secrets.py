#!/usr/bin/env python3
"""Explicit operator step: send local signing credentials to GitHub Actions Secrets."""
import argparse
import base64
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--repository", required=True)
args = parser.parse_args()
values = dict(line.split("=", 1) for line in (ROOT / ".signing/signing.properties").read_text().splitlines() if "=" in line)
values["ANDROID_KEYSTORE_BASE64"] = base64.b64encode(Path(values.pop("ANDROID_KEYSTORE_PATH")).read_bytes()).decode()
for name, value in values.items():
    subprocess.run(["gh", "secret", "set", name, "--repo", args.repository], input=value, text=True, check=True)
print("Signing secrets configured. No passwords were printed.")
