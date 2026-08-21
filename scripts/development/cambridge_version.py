#!/usr/bin/env python3
"""Validate CamBridge's single root version and release tag."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
VERSION_PATH = REPOSITORY_ROOT / "VERSION"
SEMVER_PATTERN = re.compile(r"^(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)$")
TAG_PATTERN = re.compile(r"^v(?P<version>(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*))$")


def read_version(path: Path = VERSION_PATH) -> str:
    raw_version = path.read_text(encoding="utf-8")
    version = raw_version.strip()
    if raw_version != f"{version}\n":
        raise ValueError("VERSION must contain one trimmed semantic version line")
    if SEMVER_PATTERN.fullmatch(version) is None:
        raise ValueError("VERSION must be MAJOR.MINOR.PATCH")
    return version


def validate_tag(tag: str, version: str) -> None:
    match = TAG_PATTERN.fullmatch(tag)
    if match is None:
        raise ValueError("release tag must be exactly vMAJOR.MINOR.PATCH")
    if match.group("version") != version:
        raise ValueError(f"release tag {tag} does not match VERSION {version}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="validate VERSION")
    parser.add_argument("--tag", help="also validate a vMAJOR.MINOR.PATCH release tag")
    arguments = parser.parse_args()
    try:
        version = read_version()
        if arguments.tag is not None:
            validate_tag(arguments.tag, version)
    except (OSError, ValueError) as error:
        print(f"CamBridge version check: {error}", file=sys.stderr)
        return 1
    print(version)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
