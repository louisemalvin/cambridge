#!/usr/bin/env python3
"""Read and verify CamBridge component versions from the root manifest."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Mapping


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
VERSION_MANIFEST_PATH = REPOSITORY_ROOT / "VERSION"
MANIFEST_SCHEMA_VERSION = 1
SEMVER_PATTERN = re.compile(r"^(?P<major>0|[1-9]\d*)\.(?P<minor>0|[1-9]\d*)\.(?P<patch>0|[1-9]\d*)$")
COMPONENT_KEYS = {
    "android": "androidSender",
    "obs": "obsPlugin",
    "ios": "iosSender",
}
TAG_PREFIXES = {
    "android": "android-v",
    "obs": "obs-v",
    "ios": "ios-v",
}
DEFERRED_IOS_MARKETING_VERSION = "0.0.0"
DEFERRED_IOS_BUILD_VERSION = "1"
SUCCESS_EXIT_CODE = 0
FAILURE_EXIT_CODE = 1


class ComponentVersionError(ValueError):
    """Raised when the component version manifest or release reference is invalid."""


def load_manifest(path: Path = VERSION_MANIFEST_PATH) -> Mapping[str, object]:
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ComponentVersionError(f"could not read version manifest {path}: {error}") from error
    if not isinstance(manifest, dict):
        raise ComponentVersionError(f"version manifest must contain an object: {path}")
    if manifest.get("schemaVersion") != MANIFEST_SCHEMA_VERSION:
        raise ComponentVersionError(f"unsupported version manifest schema: {path}")
    components = manifest.get("components")
    if not isinstance(components, dict) or set(components) != set(COMPONENT_KEYS.values()):
        raise ComponentVersionError(
            f"version manifest must define exactly {tuple(COMPONENT_KEYS.values())}: {path}"
        )
    for component, key in COMPONENT_KEYS.items():
        value = components[key]
        if component == "ios" and value is None:
            continue
        if not isinstance(value, str) or SEMVER_PATTERN.fullmatch(value) is None:
            raise ComponentVersionError(f"{key} must contain a semantic version or null: {path}")
    return manifest


def component_version(component: str, path: Path = VERSION_MANIFEST_PATH) -> str | None:
    if component not in COMPONENT_KEYS:
        raise ComponentVersionError(f"unknown component: {component}")
    manifest = load_manifest(path)
    components = manifest["components"]
    assert isinstance(components, dict)
    value = components[COMPONENT_KEYS[component]]
    if value is None:
        return None
    assert isinstance(value, str)
    return value


def parse_release_tag(tag: str) -> tuple[str, str]:
    for component, prefix in TAG_PREFIXES.items():
        if not tag.startswith(prefix):
            continue
        version = tag[len(prefix):]
        if SEMVER_PATTERN.fullmatch(version) is None:
            break
        return component, version
    supported_prefixes = ", ".join(TAG_PREFIXES.values())
    raise ComponentVersionError(f"release tag must use one of {supported_prefixes}<major>.<minor>.<patch>: {tag}")


def verify_release(component: str, version: str, path: Path = VERSION_MANIFEST_PATH) -> None:
    if SEMVER_PATTERN.fullmatch(version) is None:
        raise ComponentVersionError(f"release version is not semantic: {version}")
    expected = component_version(component, path)
    if expected is None:
        raise ComponentVersionError(f"component is deferred and cannot be released: {component}")
    if expected != version:
        raise ComponentVersionError(
            f"{component} release version {version} does not match the manifest value {expected}"
        )


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--check", action="store_true")
    action.add_argument("--component", choices=tuple(COMPONENT_KEYS))
    action.add_argument("--tag")
    parser.add_argument("--version")
    return parser.parse_args()


def main() -> int:
    arguments = _parse_args()
    try:
        if arguments.check:
            load_manifest()
            for component in COMPONENT_KEYS:
                value = component_version(component)
                state = value or "deferred"
                print(f"{component}={state}")
            return SUCCESS_EXIT_CODE
        if arguments.tag:
            component, version = parse_release_tag(arguments.tag)
            verify_release(component, version)
            print(f"component={component}")
            print(f"version={version}")
            return SUCCESS_EXIT_CODE
        if arguments.version is not None:
            verify_release(arguments.component, arguments.version)
            print(f"component={arguments.component}")
            print(f"version={arguments.version}")
        else:
            value = component_version(arguments.component)
            if value is None:
                raise ComponentVersionError(f"component is deferred: {arguments.component}")
            print(value)
        return SUCCESS_EXIT_CODE
    except ComponentVersionError as error:
        print(f"component version check: {error}", file=sys.stderr)
        return FAILURE_EXIT_CODE


if __name__ == "__main__":
    raise SystemExit(main())
