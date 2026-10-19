#!/usr/bin/env python3
"""Fail closed unless the signed APK matches the release identity."""

import argparse
import re
import subprocess
import sys


def certificate(value):
    normalized = value.replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", normalized):
        raise ValueError("Expected certificate must be a SHA256 fingerprint")
    return normalized


def run_tool(command):
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode:
        raise ValueError(f"{command[0]} failed: {result.stderr.strip()}")
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("apk", "certificate", "version-name", "version-code", "apksigner", "aapt"):
        parser.add_argument(f"--{name}", required=True)
    args = parser.parse_args()
    try:
        expected = certificate(args.certificate)
        output = run_tool([args.apksigner, "verify", "--print-certs", args.apk])
        # build-tools 36 prints "Signer #1 ..."; 37 prints one "V2 Signer: ..."
        # line per signature scheme. Each scheme may list only one signer.
        pattern = (
            r"^(Signer #\d+|V\d+ Signer):? certificate SHA-256 digest:\s*(\S+)\s*$"
        )
        found = re.findall(pattern, output, re.MULTILINE)
        labels = [label for label, _ in found]
        fingerprints = [value for _, value in found]
        if (
            len(set(labels)) != len(labels)
            or sum(label.startswith("Signer #") for label in labels) > 1
            or len({certificate(value) for value in fingerprints}) != 1
        ):
            raise ValueError("APK must have exactly one signing certificate")
        if certificate(fingerprints[0]) != expected:
            raise ValueError("APK signing certificate does not match the trust anchor")
        output = run_tool([args.aapt, "dump", "badging", args.apk])
        packages = re.findall(r"^package: (.+)$", output, re.MULTILINE)
        if len(packages) != 1:
            raise ValueError("APK must contain exactly one package record")
        fields = dict(re.findall(r"(\w+)='([^']*)'", packages[0]))
        for key, expected_value in (
            ("name", "com.m57.hermescontrol"),
            ("versionName", args.version_name),
            ("versionCode", args.version_code),
        ):
            if fields.get(key) != expected_value:
                raise ValueError(f"APK {key} does not match expected value {expected_value!r}")
    except (OSError, ValueError) as error:
        print(f"Release APK verification failed: {error}", file=sys.stderr)
        return 1
    print("Release APK certificate, package, and version verified")
    return 0


if __name__ == "__main__":
    sys.exit(main())
