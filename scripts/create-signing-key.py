#!/usr/bin/env python3
"""Create a local signing identity once. Secret values are never printed."""
import os
import secrets
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
directory = ROOT / ".signing"
directory.mkdir(mode=0o700, exist_ok=True)
os.chmod(directory, 0o700)
keystore = directory / "camera-release.p12"
properties = directory / "signing.properties"
if keystore.exists() or properties.exists():
    raise SystemExit("Signing files already exist; refusing to replace the update identity.")
password = secrets.token_urlsafe(36)
env = dict(os.environ, CAMERA_SIGNING_PASSWORD=password)
old_mask = os.umask(0o077)
try:
    subprocess.run(["keytool", "-genkeypair", "-storetype", "PKCS12", "-keystore", str(keystore),
                    "-alias", "camera", "-keyalg", "RSA", "-keysize", "3072", "-sigalg", "SHA256withRSA",
                    "-validity", "10000", "-dname", "CN=Camera", "-storepass:env", "CAMERA_SIGNING_PASSWORD",
                    "-keypass:env", "CAMERA_SIGNING_PASSWORD"], env=env, check=True, capture_output=True)
    properties.write_text(f"ANDROID_KEYSTORE_PATH={keystore}\nANDROID_KEYSTORE_PASSWORD={password}\n"
                          f"ANDROID_KEY_ALIAS=camera\nANDROID_KEY_PASSWORD={password}\n")
    os.chmod(keystore, 0o600)
    os.chmod(properties, 0o600)
finally:
    os.umask(old_mask)
print("Created ignored .signing/camera-release.p12 and .signing/signing.properties. Back up both securely.")
