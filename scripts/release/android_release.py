#!/usr/bin/env python3
"""Fail-closed release checks. Never print keystore bytes or credentials."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def certificate_digest(value):
    result = value.strip().lower().replace(":", "")
    if not re.fullmatch(r"[0-9a-f]{64}", result):
        raise ValueError("ANDROID_SIGNING_CERT_SHA256 must contain the full installed certificate SHA-256")
    return result


def version_counter(value, properties, last_published):
    data = dict(line.strip().split("=", 1) for line in properties.splitlines()
                if line.strip() and not line.lstrip().startswith("#"))
    floor = max(int(data["versionCode"]), int(data["minimumUpgradeVersionCode"]))
    if not re.fullmatch(r"[0-9]+", value) or not floor <= int(value) <= 2_100_000_000:
        raise ValueError("release versionCode must be at least the shared counter and upgrade floor")
    if not last_published or not re.fullmatch(r"[0-9]+", last_published) or int(value) <= int(last_published):
        raise ValueError("release counter must exceed protected ANDROID_LAST_PUBLISHED_VERSION_CODE")
    return int(value)


def restore_keystore(encoded, destination):
    # Preserve the PR #3 support for wrapped / URL-safe base64, without logging it.
    normalized = re.sub(r"\s", "", encoded).replace("-", "+").replace("_", "/")
    if not normalized:
        raise ValueError("missing signing material: ANDROID_KEYSTORE_BASE64")
    normalized += "=" * (-len(normalized) % 4)
    try:
        raw = base64.b64decode(normalized, validate=True)
    except (ValueError, base64.binascii.Error):
        raise ValueError("ANDROID_KEYSTORE_BASE64 is invalid") from None
    if not raw:
        raise ValueError("ANDROID_KEYSTORE_BASE64 decoded to an empty file")
    fd = os.open(destination, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(raw)


def command(args):
    return subprocess.run(args, capture_output=True, check=False)


def check_keystore(path, expected, execute=command):
    for key in ("ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD"):
        if not os.environ.get(key):
            raise ValueError("missing signing material: " + key)
    result = execute(["keytool", "-exportcert", "-keystore", str(path), "-alias",
                      os.environ["ANDROID_KEY_ALIAS"], "-storepass:env", "ANDROID_KEYSTORE_PASSWORD"])
    if result.returncode or not result.stdout:
        raise ValueError("maintainer keystore could not be opened or alias was not found")
    if hashlib.sha256(result.stdout).hexdigest() != certificate_digest(expected):
        raise ValueError("keystore certificate differs from the protected installed-certificate fingerprint")


def package_metadata(badging):
    line = next((line for line in badging.splitlines() if line.startswith("package:")), "")
    fields = dict(re.findall(r"([A-Za-z]+)='([^']*)'", line))
    if not {"name", "versionCode", "versionName"}.issubset(fields):
        raise ValueError("APK package metadata could not be read")
    return fields


def check_build_config(text, update_id):
    match = re.search(r'UPDATE_DRIVE_FILE_ID\s*=\s*("(?:\\.|[^"\\])*")\s*;', text)
    if not match or json.loads(match[1]) != update_id.strip():
        raise ValueError("update file id was not passed through to the production BuildConfig")
    if not re.search(r"MAINTAINER_SIGNED\s*=\s*true\s*;", text):
        raise ValueError("production BuildConfig is not marked as maintainer signed")


def check_apk(apk, apksigner, aapt, expected, counter, source_sha, config, update_id, execute=command):
    expected = certificate_digest(expected)
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise ValueError("source commit SHA is invalid")
    verified = execute([str(apksigner), "verify", "--verbose", "--print-certs", str(apk)])
    if verified.returncode:
        raise ValueError("APK cryptographic signature verification failed")
    signers = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]+)",
                         verified.stdout.decode("utf-8", errors="replace"))
    if signers != [expected] and [s.lower() for s in signers] != [expected]:
        raise ValueError("APK signer set differs from the protected installed certificate")
    # aapt may return nonzero for adaptive icons after printing valid badging.
    badging = execute([str(aapt), "dump", "badging", str(apk)])
    metadata = package_metadata(badging.stdout.decode("utf-8", errors="replace"))
    if metadata["name"] != "wherevercode.agent" or metadata["versionCode"] != str(counter):
        raise ValueError("APK package or versionCode does not match the production release request")
    if f".{counter}+{source_sha[:8]}." not in metadata["versionName"]:
        raise ValueError("APK versionName does not identify the requested counter and source commit")
    check_build_config(config, update_id)
    digest = hashlib.sha256()
    with open(apk, "rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return dict(packageName=metadata["name"], versionCode=counter, versionName=metadata["versionName"],
                sourceCommit=source_sha, signerSha256=expected, apkSha256=digest.hexdigest(),
                updateSourceConfigured=bool(update_id.strip()), physicalUpgradeVerified=False)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["prepare", "verify"])
    parser.add_argument("--keystore", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--properties", default="android/version.properties")
    parser.add_argument("--apk")
    parser.add_argument("--apksigner")
    parser.add_argument("--aapt")
    parser.add_argument("--build-config")
    parser.add_argument("--manifest")
    args = parser.parse_args()
    counter = version_counter(args.version, Path(args.properties).read_text(),
                              os.environ.get("ANDROID_LAST_PUBLISHED_VERSION_CODE"))
    expected = certificate_digest(os.environ.get("ANDROID_SIGNING_CERT_SHA256", ""))
    if args.action == "prepare":
        restore_keystore(os.environ.get("ANDROID_KEYSTORE_BASE64", ""), args.keystore)
        try:
            check_keystore(args.keystore, expected)
        except Exception:
            Path(args.keystore).unlink(missing_ok=True)
            raise
    else:
        if not all((args.apk, args.apksigner, args.aapt, args.build_config, args.manifest)):
            raise ValueError("release verification paths are incomplete")
        result = check_apk(args.apk, args.apksigner, args.aapt, expected, counter,
                           os.environ.get("GITHUB_SHA", ""), Path(args.build_config).read_text(),
                           os.environ.get("WHEREVERCODE_UPDATE_DRIVE_FILE_ID", ""))
        Path(args.manifest).write_text(json.dumps(result, indent=2) + "\n")
    print("Release checks passed; physical upgrade acceptance remains with Muse.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError) as error:
        print("Release blocked: " + str(error), file=sys.stderr)
        sys.exit(1)
