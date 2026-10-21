#!/usr/bin/env python3
"""Shared, deterministic version and release metadata rules. No third-party packages."""
import argparse
import hashlib
import json
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATTERN = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-rc\.([1-9][0-9]*))?")
DISALLOWED_UI_COPY = re.compile(r"题目|解答|答案|原题|题号|解题")


def version_code(version):
    match = PATTERN.fullmatch(version)
    if not match:
        raise ValueError("Use major.minor.patch or major.minor.patch-rc.N")
    major, minor, patch = map(int, match.group(1, 2, 3))
    rc = int(match.group(4)) if match.group(4) else 99
    if not (0 <= major <= 2099 and 0 <= minor <= 99 and 0 <= patch <= 99 and 1 <= rc <= 99):
        raise ValueError("Version component exceeds Android versionCode allocation")
    if match.group(4) and rc == 99:
        raise ValueError("RC numbers must be between 1 and 98")
    return major * 1_000_000 + minor * 10_000 + patch * 100 + rc


def current_version():
    lines = (ROOT / "version.properties").read_text().splitlines()
    return next(line.split("=", 1)[1].strip() for line in lines if line.startswith("versionName="))


def next_version(version, level):
    match = PATTERN.fullmatch(version)
    if not match:
        raise ValueError("Current version is invalid")
    major, minor, patch = map(int, match.group(1, 2, 3))
    rc = int(match.group(4)) if match.group(4) else None
    if level == "major":
        candidate = f"{major + 1}.0.0"
    elif level == "minor":
        candidate = f"{major}.{minor + 1}.0"
    elif level == "patch":
        candidate = (f"{major}.{minor}.{patch}" if rc is not None
                     else f"{major}.{minor}.{patch + 1}")
    elif level == "rc":
        candidate = (f"{major}.{minor}.{patch + 1}-rc.1" if rc is None
                     else f"{major}.{minor}.{patch}-rc.{rc + 1}")
    else:
        raise ValueError("Unknown version increment")
    version_code(candidate)
    if version_code(candidate) <= version_code(version):
        raise ValueError("New version must increase")
    return candidate


def prepare_version(version, level, notes):
    candidate = next_version(version, level)
    return candidate, validate_notes(notes)


def validate_prepared_version(current, candidate, notes, allow_current=False):
    code = version_code(candidate)
    if code < version_code(current) or (code == version_code(current) and not allow_current):
        raise ValueError("New version must increase")
    return candidate, validate_notes(notes)


def validate_notes(notes):
    value = notes.strip()
    if not value:
        raise ValueError("Release notes must not be empty")
    if len(value) > 8000:
        raise ValueError("Release notes exceed 8000 characters")
    if DISALLOWED_UI_COPY.search(value):
        raise ValueError("Release notes use disallowed app-facing problem/solution wording")
    return value


def validate(tag, existing_tags=(), latest_stable=None):
    version = current_version()
    code = version_code(version)
    if tag != "v" + version:
        raise ValueError("Tag and version.properties disagree")
    if latest_stable and latest_stable.startswith("v") and PATTERN.fullmatch(latest_stable[1:]):
        if "-rc." not in version and version_code(latest_stable[1:]) >= code:
            raise ValueError(f"Stable version must advance beyond latest release {latest_stable}")
    for old in existing_tags:
        old = old.strip()
        if old == tag:
            raise ValueError("This version is already published")
        if old.startswith("v") and PATTERN.fullmatch(old[1:]) and version_code(old[1:]) >= code:
            raise ValueError(f"Version must advance beyond published {old}")
    return version, code


def package(tag, repository, apk, metadata, notes, output, certificate_sha256):
    version, code = validate(tag)
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid GitHub repository")
    built = json.loads(metadata.read_text())
    element = built["elements"][0]
    if len(built["elements"]) != 1 or built["applicationId"] != "com.nic.photoanswer" or element["versionCode"] != code or element["versionName"] != version:
        raise ValueError("APK metadata does not match release identity/version")
    if not 0 < apk.stat().st_size <= 150 * 1024 * 1024:
        raise ValueError("APK exceeds updater limit")
    certificate = certificate_sha256.lower().replace(":", "")
    if not re.fullmatch(r"[0-9a-f]{64}", certificate):
        raise ValueError("Invalid signing certificate SHA-256")
    output.mkdir(parents=True, exist_ok=True)
    target = output / f"camera-{tag}.apk"
    shutil.copyfile(apk, target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    release_notes = validate_notes(notes.read_text())
    payload = {
        "schemaVersion": 1, "applicationId": "com.nic.photoanswer",
        "versionName": version, "versionCode": code, "minSdk": 26,
        "signingCertificateSha256": certificate,
        "apk": {"url": f"https://github.com/{repository}/releases/download/{tag}/{target.name}",
                "sha256": digest, "size": target.stat().st_size}, "notes": release_notes,
    }
    (output / "update.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
    (output / "release-notes.md").write_text(release_notes + "\n")
    files = [target, output / "update.json"]
    (output / "SHA256SUMS").write_text("".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n" for p in files))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("validate")
    check.add_argument("--tag", required=True)
    check.add_argument("--existing-tags", type=Path)
    check.add_argument("--latest-stable")
    bump = commands.add_parser("set-version")
    bump.add_argument("version")
    next_command = commands.add_parser("next")
    next_command.add_argument("--bump", choices=["major", "minor", "patch", "rc"], required=True)
    next_command.add_argument("--notes", type=Path, required=True)
    commands.add_parser("current")
    prepare = commands.add_parser("prepare")
    version_source = prepare.add_mutually_exclusive_group(required=True)
    version_source.add_argument("--bump", choices=["major", "minor", "patch", "rc"])
    version_source.add_argument("--version")
    prepare.add_argument("--notes", type=Path, required=True)
    build = commands.add_parser("package")
    build.add_argument("--tag", required=True)
    build.add_argument("--repository", required=True)
    build.add_argument("--apk", type=Path, required=True)
    build.add_argument("--metadata", type=Path, default=ROOT / "app/build/outputs/apk/release/output-metadata.json")
    build.add_argument("--notes", type=Path, default=ROOT / "RELEASE_NOTES.md")
    build.add_argument("--certificate-sha256", required=True)
    build.add_argument("--output", type=Path, default=ROOT / "dist")
    args = parser.parse_args()
    if args.command == "validate":
        tags = args.existing_tags.read_text().splitlines() if args.existing_tags else []
        version, code = validate(args.tag, tags, args.latest_stable)
        print(f"versionName={version}\nversionCode={code}\nprerelease={'-rc.' in version}")
    elif args.command == "set-version":
        if version_code(args.version) <= version_code(current_version()):
            raise ValueError("New version must increase")
        (ROOT / "version.properties").write_text(f"versionName={args.version}\n")
        print(f"Updated version.properties to {args.version}; review release notes before tagging.")
    elif args.command == "next":
        version, _ = prepare_version(current_version(), args.bump, args.notes.read_text())
        print(version)
    elif args.command == "current":
        print(current_version())
    elif args.command == "prepare":
        if args.bump:
            version, notes = prepare_version(current_version(), args.bump, args.notes.read_text())
        else:
            version, notes = validate_prepared_version(
                current_version(), args.version, args.notes.read_text(), allow_current=True)
        (ROOT / "version.properties").write_text(f"versionName={version}\n")
        (ROOT / "RELEASE_NOTES.md").write_text(notes + "\n")
        print(version)
    else:
        package(args.tag, args.repository, args.apk, args.metadata, args.notes, args.output,
                args.certificate_sha256)


if __name__ == "__main__":
    main()
