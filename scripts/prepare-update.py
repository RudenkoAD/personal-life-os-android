#!/usr/bin/env python3
"""Validate a signed release APK and create a deployable update tree."""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path


def tool(name: str) -> str:
    value = shutil.which(name)
    if not value:
        raise SystemExit(f"required Android SDK tool not found: {name}")
    return value


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path, default=Path("outputs/android-update"))
    parser.add_argument("--expected-package", default="com.personallifeos.mobile")
    args = parser.parse_args()

    apk = args.apk.resolve()
    if not apk.is_file() or apk.suffix.lower() != ".apk":
        raise SystemExit("input must be an existing .apk file")
    apksigner = tool("apksigner")
    aapt = shutil.which("aapt2") or shutil.which("aapt")
    if not aapt:
        raise SystemExit("required Android SDK tool not found: aapt2 or aapt")
    verify = subprocess.run([apksigner, "verify", "--verbose", str(apk)], text=True, capture_output=True)
    if verify.returncode:
        raise SystemExit("apksigner verification failed")
    badging = subprocess.run([aapt, "dump", "badging", str(apk)], text=True, capture_output=True, check=True).stdout
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    sdk = re.search(r"(?m)^(?:sdkVersion|minSdkVersion):'(\d+)'(?:\s|$)", badging)
    if not package or not sdk:
        raise SystemExit("aapt output does not contain package/version/minSdk")
    package_name, version_code_text, version_name = package.groups()
    if package_name != args.expected_package:
        raise SystemExit(f"unexpected package: {package_name}")
    try:
        version_code = int(version_code_text)
        min_sdk = int(sdk.group(1))
    except ValueError as exc:
        raise SystemExit("invalid numeric package metadata") from exc
    if version_code < 1 or min_sdk < 1 or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,63}", version_name):
        raise SystemExit("invalid APK metadata")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    size = apk.stat().st_size
    if size > 150 * 1024 * 1024:
        raise SystemExit("APK exceeds 150 MiB limit")
    filename = f"Life-OS-{version_name}.apk"
    parent = args.output.resolve().parent
    parent.mkdir(parents=True, exist_ok=True)
    args.output.mkdir(parents=True, exist_ok=True)
    release_root = args.output / "releases"
    release_root.mkdir(exist_ok=True)
    release_dir = release_root / str(version_code)
    target_apk = release_dir / filename
    if release_dir.exists():
        if not target_apk.is_file() or target_apk.stat().st_size != size or hashlib.sha256(target_apk.read_bytes()).hexdigest() != digest:
            raise SystemExit("versionCode already exists with a different or incomplete APK")
    else:
        with tempfile.TemporaryDirectory(prefix=f".{version_code}.", dir=release_root) as temp:
            stage = Path(temp) / str(version_code)
            stage.mkdir()
            shutil.copy2(apk, stage / filename)
            os.replace(stage, release_dir)
    metadata = {
        "schemaVersion": 1,
        "versionCode": version_code,
        "versionName": version_name,
        "apkPath": f"/android/releases/{version_code}/{filename}",
        "sha256": digest,
        "sizeBytes": size,
        "minSdk": min_sdk,
    }
    with tempfile.NamedTemporaryFile("w", dir=args.output, prefix=".latest.", delete=False) as latest:
        json.dump(metadata, latest, ensure_ascii=False, indent=2)
        latest.write("\n")
        latest_path = Path(latest.name)
    os.replace(latest_path, args.output / "latest.json")
    print(f"prepared {package_name} {version_name} ({version_code}) -> {args.output}")
    print(f"sha256={digest} sizeBytes={size} minSdk={min_sdk}")


if __name__ == "__main__":
    main()
