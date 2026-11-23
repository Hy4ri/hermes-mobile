#!/usr/bin/env python3
"""Validate the release version and emit GitHub Actions outputs."""

import re
import sys


def release_version(tag):
    match = re.fullmatch(
        r"v(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)"
        r"(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?",
        tag,
    )
    if not match:
        raise ValueError("Invalid release tag or versionCode components")
    major, minor, patch = map(int, match.group(1, 2, 3))
    code = major * 10_000 + minor * 100 + patch
    if not 1 <= code <= 2_100_000_000:
        raise ValueError("Invalid Android versionCode")
    return {
        "tag": tag,
        "version": tag[1:],
        "code": code,
        "prerelease": "true" if match.group(4) else "false",
    }


if __name__ == "__main__":
    try:
        outputs = release_version(sys.argv[1])
    except ValueError as error:
        sys.exit(str(error))
    for key, value in outputs.items():
        print(f"{key}={value}")
