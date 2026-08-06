#!/usr/bin/env python3
"""Check the typed direct-stream boundaries against the JSON contract."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
CONTRACT_PATH = REPOSITORY_ROOT / "protocol" / "direct-stream-contract.json"
DEPLOYMENT_PATH = REPOSITORY_ROOT / "protocol" / "direct-stream-deployment.json"
SCHEMA_PATH = REPOSITORY_ROOT / "protocol" / "direct-stream.schema.json"
KOTLIN_CONTRACT_PATH = (
    REPOSITORY_ROOT
    / "android/app/src/main/java/dev/mobilewebcam/sender/connection/control/direct/DirectStreamContract.kt"
)
KOTLIN_PROFILES_PATH = REPOSITORY_ROOT / "android/app/src/main/java/dev/mobilewebcam/sender/session/VideoProfiles.kt"
CPP_CONTRACT_PATH = REPOSITORY_ROOT / "desktop/hosts/obs/direct-webcam-source/src/protocol_contract.hpp"
FIXTURE_PATH = REPOSITORY_ROOT / "scripts/linux/direct-webcam-fixture.py"
ANDROID_SMOKE_PATH = REPOSITORY_ROOT / "scripts/android/test-emulator-direct-webcam.sh"
NATIVE_FIXTURE_PATH = REPOSITORY_ROOT / "scripts/linux/test-direct-webcam-fixture.sh"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def integer_literal(value: str) -> int:
    return int(value.replace("'", "").replace("_", ""))


def required_match(text: str, pattern: str, description: str) -> str:
    match = re.search(pattern, text, re.MULTILINE)
    if match is None:
        raise AssertionError(f"{description}: pattern not found")
    return match.group(1)


def check_scalar(text: str, pattern: str, expected: Any, description: str, parser: Any = int) -> None:
    raw = required_match(text, pattern, description)
    actual = parser(raw)
    if actual != expected:
        raise AssertionError(f"{description}: expected {expected!r}, found {actual!r}")


def check_profiles(kotlin_text: str, profiles: list[dict[str, Any]]) -> None:
    default_fps = integer_literal(required_match(kotlin_text, r"DEFAULT_FPS = ([0-9_]+)", "Kotlin default FPS"))
    for profile in profiles:
        profile_id = re.escape(profile["id"])
        pattern = (
            rf'id = "{profile_id}",\s+'
            rf'width = ([0-9_]+),\s+'
            rf'height = ([0-9_]+),\s+'
            rf'fps = ([A-Za-z0-9_]+),\s+'
            rf'h264BitrateBps = ([0-9_]+),'
        )
        match = re.search(pattern, kotlin_text, re.MULTILINE)
        if match is None:
            raise AssertionError(f"Kotlin profile {profile['id']} is missing")
        actual_width = integer_literal(match.group(1))
        actual_height = integer_literal(match.group(2))
        actual_fps = default_fps if match.group(3) == "DEFAULT_FPS" else integer_literal(match.group(3))
        actual_bitrate = integer_literal(match.group(4))
        actual = (actual_width, actual_height, actual_fps, actual_bitrate)
        expected = (
            profile["width"],
            profile["height"],
            profile["fps"],
            profile["bitrateBps"],
        )
        if actual != expected:
            raise AssertionError(f"Kotlin profile {profile['id']}: expected {expected}, found {actual}")


def check_cpp_profiles(cpp_text: str, profiles: list[dict[str, Any]]) -> None:
    for profile in profiles:
        profile_id = re.escape(profile["id"])
        pattern = (
            rf'\{{"{profile_id}",\s*([0-9\']+),\s*([0-9\']+),\s*([0-9\']+),\s*([0-9\']+)\}}'
        )
        match = re.search(pattern, cpp_text, re.MULTILINE)
        if match is None:
            raise AssertionError(f"C++ profile {profile['id']} is missing")
        actual = tuple(integer_literal(value) for value in match.groups())
        expected = (
            profile["width"],
            profile["height"],
            profile["fps"],
            profile["bitrateBps"],
        )
        if actual != expected:
            raise AssertionError(f"C++ profile {profile['id']}: expected {expected}, found {actual}")


def main() -> int:
    contract = json.loads(read(CONTRACT_PATH))
    deployment = json.loads(read(DEPLOYMENT_PATH))
    schema = json.loads(read(SCHEMA_PATH))
    kotlin_contract = read(KOTLIN_CONTRACT_PATH)
    kotlin_profiles = read(KOTLIN_PROFILES_PATH)
    cpp_contract = read(CPP_CONTRACT_PATH)
    fixture = read(FIXTURE_PATH)
    android_smoke = read(ANDROID_SMOKE_PATH)
    native_fixture = read(NATIVE_FIXTURE_PATH)

    protocol_version = contract["protocolVersion"]
    defaults = contract["defaults"]
    geometry = contract["geometry"]
    profiles = contract["profiles"]
    computer = deployment.get("computer", {})
    expected_computer_keys = {"id", "displayName", "address", "interface", "sourceCidr"}
    if set(computer) != expected_computer_keys or not all(computer.values()):
        raise AssertionError("deployment must define exactly one complete computer endpoint")
    profile_ids = {profile["id"] for profile in profiles}
    if profile_ids != {"720p30", "1080p30", "2k30"}:
        raise AssertionError("the direct product profiles must include 1080p30, 2k30, and the AVD-only 720p30 profile")
    if next(profile["availability"] for profile in profiles if profile["id"] == defaults["profileId"]) != "normal":
        raise AssertionError("the default profile must be the normal product profile")
    if protocol_version != 4:
        raise AssertionError("the direct stream contract must be protocol v4")
    if schema["properties"]["protocolVersion"]["const"] != protocol_version:
        raise AssertionError("schema protocol version is out of sync")
    if schema["properties"]["fps"]["minimum"] != contract["video"]["minimumFps"] or \
        schema["properties"]["fps"]["maximum"] != contract["video"]["maximumFps"]:
        raise AssertionError("schema FPS bounds are out of sync")

    check_scalar(kotlin_contract, r"const val PROTOCOL_VERSION = (\d+)", protocol_version, "Kotlin protocol version")
    check_scalar(
        kotlin_contract,
        r"const val DEFAULT_CONTROL_PORT = ([0-9_]+)",
        defaults["controlPort"],
        "Kotlin control port",
        integer_literal,
    )
    check_scalar(
        kotlin_contract,
        r"const val DEFAULT_MEDIA_PORT_OFFSET = ([0-9_]+)",
        defaults["mediaPortOffset"],
        "Kotlin media port offset",
        integer_literal,
    )
    check_scalar(
        kotlin_contract,
        r"const val MAXIMUM_LONG_EDGE = ([0-9_]+)",
        geometry["maximumLongEdge"],
        "Kotlin long-edge limit",
        integer_literal,
    )
    check_scalar(
        kotlin_contract,
        r"const val MAXIMUM_SHORT_EDGE = ([0-9_]+)",
        geometry["maximumShortEdge"],
        "Kotlin short-edge limit",
        integer_literal,
    )
    check_scalar(
        kotlin_contract,
        r"const val MINIMUM_FPS = ([0-9_]+)",
        contract["video"]["minimumFps"],
        "Kotlin minimum FPS",
        integer_literal,
    )
    check_scalar(
        kotlin_contract,
        r"const val MAXIMUM_FPS = ([0-9_]+)",
        contract["video"]["maximumFps"],
        "Kotlin maximum FPS",
        integer_literal,
    )
    check_scalar(
        kotlin_contract,
        r"const val DEFAULT_CODED_WIDTH = ([0-9_]+)",
        next(profile["width"] for profile in profiles if profile["id"] == defaults["profileId"]),
        "Kotlin default coded width",
        integer_literal,
    )
    check_scalar(
        kotlin_contract,
        r"const val DEFAULT_CODED_HEIGHT = ([0-9_]+)",
        next(profile["height"] for profile in profiles if profile["id"] == defaults["profileId"]),
        "Kotlin default coded height",
        integer_literal,
    )
    check_scalar(
        kotlin_contract,
        r'const val DEFAULT_PROFILE_ID = "([^"]+)"',
        defaults["profileId"],
        "Kotlin default profile",
        str,
    )
    check_profiles(kotlin_profiles, profiles)

    check_scalar(cpp_contract, r"kProtocolVersion = (\d+)", protocol_version, "C++ protocol version")
    check_scalar(
        cpp_contract,
        r"kDefaultControlPort = ([0-9']+)",
        defaults["controlPort"],
        "C++ control port",
        integer_literal,
    )
    check_scalar(
        cpp_contract,
        r"kDefaultMediaPortOffset = ([0-9']+)",
        defaults["mediaPortOffset"],
        "C++ media port offset",
        integer_literal,
    )
    check_scalar(
        cpp_contract,
        r"kMaximumLongEdge = ([0-9']+)",
        geometry["maximumLongEdge"],
        "C++ long-edge limit",
        integer_literal,
    )
    check_scalar(
        cpp_contract,
        r"kMaximumShortEdge = ([0-9']+)",
        geometry["maximumShortEdge"],
        "C++ short-edge limit",
        integer_literal,
    )
    check_scalar(
        cpp_contract,
        r"kMinimumFps = ([0-9']+)",
        contract["video"]["minimumFps"],
        "C++ minimum FPS",
        integer_literal,
    )
    check_scalar(
        cpp_contract,
        r"kMaximumFps = ([0-9']+)",
        contract["video"]["maximumFps"],
        "C++ maximum FPS",
        integer_literal,
    )
    check_scalar(
        cpp_contract,
        r"kDefaultCodedWidth = ([0-9']+)",
        next(profile["width"] for profile in profiles if profile["id"] == defaults["profileId"]),
        "C++ default coded width",
        integer_literal,
    )
    check_scalar(
        cpp_contract,
        r"kDefaultCodedHeight = ([0-9']+)",
        next(profile["height"] for profile in profiles if profile["id"] == defaults["profileId"]),
        "C++ default coded height",
        integer_literal,
    )
    check_scalar(
        cpp_contract,
        r'kDefaultProfileId\[\] = "([^"]+)"',
        defaults["profileId"],
        "C++ default profile",
        str,
    )
    check_cpp_profiles(cpp_contract, profiles)

    if 'parser.add_argument("--profile", default=None)' not in fixture or \
        'profile_id = requested_profile_id or contract["defaults"]["profileId"]' not in fixture:
        raise AssertionError("the native fixture must derive its default profile from the contract")
    if 'profile_id="${DIRECT_WEBCAM_PROFILE_ID:-720p30}"' not in android_smoke:
        raise AssertionError("the AVD smoke must opt into 720p explicitly")
    if 'profile_id="${DIRECT_WEBCAM_PROFILE_ID:-2k30}"' not in native_fixture:
        raise AssertionError("the native fixture must default to the normal 2K profile")

    for example_name in (
        "direct-probe.json",
        "direct-capabilities.json",
        "direct-hello.json",
        "direct-accepted.json",
    ):
        example = json.loads(read(REPOSITORY_ROOT / "protocol/examples" / example_name))
        if example["protocolVersion"] != protocol_version:
            raise AssertionError(f"{example_name} protocol version is out of sync")

    print("direct stream contract parity: OK")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, KeyError, StopIteration, json.JSONDecodeError) as error:
        print(f"direct stream contract parity: {error}", file=sys.stderr)
        raise SystemExit(1)
