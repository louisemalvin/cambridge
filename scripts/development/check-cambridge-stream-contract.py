#!/usr/bin/env python3
"""Check the typed cambridge-stream boundaries against the JSON contract."""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
CONTRACT_PATH = REPOSITORY_ROOT / "protocol" / "cambridge-stream-contract.json"
DEPLOYMENT_PATH = REPOSITORY_ROOT / "protocol" / "cambridge-deployment.json"
SCHEMA_PATH = REPOSITORY_ROOT / "protocol" / "cambridge-stream.schema.json"
KOTLIN_CONTRACT_PATH = (
    REPOSITORY_ROOT
    / "sender/android/app/src/main/java/dev/cambridge/sender/connection/control/cambridge/CamBridgeStreamContract.kt"
)
KOTLIN_CATALOG_PATH = REPOSITORY_ROOT / "sender/android/app/src/main/java/dev/cambridge/sender/session/VideoProfiles.kt"
SENDER_SETTINGS_PATH = REPOSITORY_ROOT / "sender/cambridge-video-settings.json"
CPP_GENERATOR_PATH = REPOSITORY_ROOT / "scripts/development/generate-cambridge-cpp-contract.py"
CPP_CONTRACT_PATH = REPOSITORY_ROOT / "receiver/obs/cambridge-obs-source/src/protocol_contract.generated.hpp"
LEGACY_CPP_CONTRACT_PATH = REPOSITORY_ROOT / "receiver/obs/cambridge-obs-source/src/protocol_contract.hpp"
CPP_PROTOCOL_PATH = REPOSITORY_ROOT / "receiver/obs/cambridge-obs-source/src/control_protocol.cpp"
CPP_SOURCE_PATH = REPOSITORY_ROOT / "receiver/obs/cambridge-obs-source/src/cambridge_source.cpp"
FIXTURE_PATH = REPOSITORY_ROOT / "scripts/receiver/common/cambridge-fixture.py"
ANDROID_SMOKE_PATH = REPOSITORY_ROOT / "scripts/sender/android/test-emulator-cambridge.sh"
NATIVE_FIXTURE_PATH = REPOSITORY_ROOT / "scripts/receiver/linux/test-cambridge-fixture.sh"
IOS_STREAM_SETTINGS_VIEW_PATH = (
    REPOSITORY_ROOT / "sender/ios/CamBridge/Features/StreamSetup/StreamSettingsSelectionView.swift"
)


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
    actual = parser(required_match(text, pattern, description))
    if actual != expected:
        raise AssertionError(f"{description}: expected {expected!r}, found {actual!r}")


def check_phone_catalog(catalog_text: str) -> None:
    required_ids = {"1080p30", "1080p60", "2k30", "2k60"}
    found_ids = set(re.findall(r'id = "([^"]+)"', catalog_text))
    if not required_ids.issubset(found_ids):
        raise AssertionError("the Android phone catalog is missing a required mode")
    for field in (
        "minimumBitrateBps",
        "defaultBitrateBps",
        "maximumBitrateBps",
        "bitrateStepBps",
    ):
        if field not in catalog_text:
            raise AssertionError(f"Android phone catalog must define {field}")
    if "val normal" not in catalog_text or "val all" not in catalog_text:
        raise AssertionError("Android compatibility catalog must expose generated profile collections")


def check_no_receiver_presets() -> None:
    production_paths = [
        CPP_CONTRACT_PATH,
        CPP_PROTOCOL_PATH,
        CPP_SOURCE_PATH,
        REPOSITORY_ROOT / "receiver/obs/cambridge-obs-source/src/control_protocol.hpp",
    ]
    forbidden = ("ProfileContract", "kProfiles", "find_profile", '"profiles"')
    for path in production_paths:
        text = read(path)
        for token in forbidden:
            if token in text:
                raise AssertionError(f"receiver production code contains forbidden preset token {token}: {path}")
    if re.search(r"profile\s*==|profile->|profile_ids", read(CPP_SOURCE_PATH) + read(CPP_PROTOCOL_PATH)):
        raise AssertionError("receiver production code still validates or advertises a profile catalog")


def check_ios_stream_settings_surface() -> None:
    settings_view = read(IOS_STREAM_SETTINGS_VIEW_PATH)
    required_fragments = (
        'Picker("Resolution"',
        "ForEach(SenderVideoCatalog.resolutions",
        'Picker("Frame rate"',
        "ForEach(SenderVideoCatalog.frameRates",
        'TextField(\n                    "Bitrate"',
    )
    for fragment in required_fragments:
        if fragment not in settings_view:
            raise AssertionError(f"iOS production setup is missing independent settings UI: {fragment}")
    forbidden_terms = ("orientation", "stabilization", "camera id", "physical camera")
    lowered = settings_view.lower()
    for term in forbidden_terms:
        if term in lowered:
            raise AssertionError(f"iOS production stream settings expose forbidden platform control: {term}")


def check_generated_cpp_contract() -> None:
    result = subprocess.run(
        [sys.executable, str(CPP_GENERATOR_PATH), "--check"],
        cwd=REPOSITORY_ROOT,
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise AssertionError(f"generated C++ contract is stale: {detail}")
    if LEGACY_CPP_CONTRACT_PATH.exists():
        raise AssertionError(f"legacy C++ contract header still exists: {LEGACY_CPP_CONTRACT_PATH}")


def main() -> int:
    check_generated_cpp_contract()
    contract = json.loads(read(CONTRACT_PATH))
    deployment = json.loads(read(DEPLOYMENT_PATH))
    schema = json.loads(read(SCHEMA_PATH))
    kotlin_contract = read(KOTLIN_CONTRACT_PATH)
    kotlin_catalog = read(KOTLIN_CATALOG_PATH)
    sender_settings = json.loads(read(SENDER_SETTINGS_PATH))
    cpp_contract = read(CPP_CONTRACT_PATH)
    fixture = read(FIXTURE_PATH)
    android_smoke = read(ANDROID_SMOKE_PATH)
    native_fixture = read(NATIVE_FIXTURE_PATH)

    protocol_version = contract["protocolVersion"]
    defaults = contract["defaults"]
    discovery = contract["discovery"]
    geometry = contract["geometry"]
    video = contract["video"]
    bitrate = contract["bitrate"]
    computer = deployment.get("computer", {})
    expected_computer_keys = {"id", "displayName", "address", "interface", "sourceCidr"}
    if set(computer) != expected_computer_keys:
        raise AssertionError("deployment must define exactly one computer entry")
    network_values = [computer["address"], computer["interface"], computer["sourceCidr"]]
    if any(network_values) and not all(network_values):
        raise AssertionError("deployment network values must be all present or all blank")
    if protocol_version != 6:
        raise AssertionError("the CamBridge stream contract must be protocol v6")
    if schema["properties"]["protocolVersion"]["const"] != protocol_version:
        raise AssertionError("schema protocol version is out of sync")
    if "profiles" in schema["properties"]:
        raise AssertionError("the v6 schema must not expose receiver-owned profiles")
    if "profiles" in schema["allOf"][1]["then"]["required"]:
        raise AssertionError("the v6 capabilities schema must not require profiles")
    if schema["properties"]["fps"]["minimum"] != video["minimumFps"] or \
        schema["properties"]["fps"]["maximum"] != video["maximumFps"]:
        raise AssertionError("schema FPS bounds are out of sync")
    if schema["properties"]["bitrateBps"]["minimum"] != bitrate["minimumBps"] or \
        schema["properties"]["bitrateBps"]["maximum"] != bitrate["maximumBps"]:
        raise AssertionError("schema bitrate bounds are out of sync")

    check_scalar(kotlin_contract, r"const val PROTOCOL_VERSION = (\d+)", protocol_version, "Kotlin protocol version")
    check_scalar(kotlin_contract, r"const val DEFAULT_CONTROL_PORT = ([0-9_]+)", defaults["controlPort"],
                 "Kotlin control port", integer_literal)
    check_scalar(kotlin_contract, r"const val DEFAULT_MEDIA_PORT_OFFSET = ([0-9_]+)", defaults["mediaPortOffset"],
                 "Kotlin media port offset", integer_literal)
    check_scalar(kotlin_contract, r"const val MAXIMUM_LONG_EDGE = ([0-9_]+)", geometry["maximumLongEdge"],
                 "Kotlin long-edge limit", integer_literal)
    check_scalar(kotlin_contract, r"const val MAXIMUM_SHORT_EDGE = ([0-9_]+)", geometry["maximumShortEdge"],
                 "Kotlin short-edge limit", integer_literal)
    check_scalar(kotlin_contract, r"const val MINIMUM_FPS = ([0-9_]+)", video["minimumFps"],
                 "Kotlin minimum FPS", integer_literal)
    check_scalar(kotlin_contract, r"const val MAXIMUM_FPS = ([0-9_]+)", video["maximumFps"],
                 "Kotlin maximum FPS", integer_literal)
    check_scalar(kotlin_contract, r"const val MINIMUM_BITRATE_BPS = ([0-9_]+)", bitrate["minimumBps"],
                 "Kotlin minimum bitrate", integer_literal)
    check_scalar(kotlin_contract, r"const val MAXIMUM_BITRATE_BPS = ([0-9_]+)", bitrate["maximumBps"],
                 "Kotlin maximum bitrate", integer_literal)
    check_scalar(kotlin_contract, r'const val DISCOVERY_SERVICE_TYPE = "([^"]+)"',
                 discovery["serviceType"], "Kotlin discovery service type", str)
    check_scalar(kotlin_contract, r"const val DISCOVERY_VERSION = (\d+)", discovery["version"],
                 "Kotlin discovery version")
    check_scalar(kotlin_contract, r'const val DISCOVERY_ADDRESS_KEY_PREFIX = "([^"]+)"',
                 discovery["addressKeyPrefix"], "Kotlin discovery address key prefix", str)
    check_scalar(kotlin_contract,
                 r"val DISCOVERY_ADDRESS_FAMILY = ReceiverDiscoveryAddressFamily\.([A-Z0-9_]+)",
                 discovery["addressFamily"].upper(), "Kotlin discovery address family", str)
    check_scalar(kotlin_contract, r"const val MAXIMUM_DISCOVERY_ADDRESS_COUNT = ([0-9_]+)",
                 discovery["maximumAddressCount"], "Kotlin maximum discovery address count", integer_literal)

    check_scalar(cpp_contract, r"kProtocolVersion = (\d+)", protocol_version, "C++ protocol version")
    check_scalar(cpp_contract, r"kDefaultControlPort = ([0-9']+)", defaults["controlPort"],
                 "C++ control port", integer_literal)
    check_scalar(cpp_contract, r"kDefaultMediaPortOffset = ([0-9']+)", defaults["mediaPortOffset"],
                 "C++ media port offset", integer_literal)
    check_scalar(cpp_contract, r"kMaximumLongEdge = ([0-9']+)", geometry["maximumLongEdge"],
                 "C++ long-edge limit", integer_literal)
    check_scalar(cpp_contract, r"kMaximumShortEdge = ([0-9']+)", geometry["maximumShortEdge"],
                 "C++ short-edge limit", integer_literal)
    check_scalar(cpp_contract, r"kMinimumFps = ([0-9']+)", video["minimumFps"],
                 "C++ minimum FPS", integer_literal)
    check_scalar(cpp_contract, r"kMaximumFps = ([0-9']+)", video["maximumFps"],
                 "C++ maximum FPS", integer_literal)
    check_scalar(cpp_contract, r"kMinimumBitrateBps = ([0-9']+)", bitrate["minimumBps"],
                 "C++ minimum bitrate", integer_literal)
    check_scalar(cpp_contract, r"kMaximumBitrateBps = ([0-9']+)", bitrate["maximumBps"],
                 "C++ maximum bitrate", integer_literal)
    check_scalar(cpp_contract, r'kDefaultReceiverId\[\] = "([^"]+)"', contract["receiver"]["defaultId"],
                 "C++ default receiver ID", str)
    check_scalar(cpp_contract, r'kDefaultReceiverDisplayName\[\] = "([^"]+)"',
                 contract["receiver"]["defaultDisplayName"], "C++ default receiver display name", str)
    check_scalar(cpp_contract, r'kDiscoveryServiceType\[\] = "([^"]+)"',
                 discovery["serviceType"], "C++ discovery service type", str)
    check_scalar(cpp_contract, r"kDiscoveryVersion = (\d+)", discovery["version"],
                 "C++ discovery version")
    check_scalar(cpp_contract, r'kDiscoveryAddressKeyPrefix\[\] = "([^"]+)"',
                 discovery["addressKeyPrefix"], "C++ discovery address key prefix", str)
    check_scalar(cpp_contract, r'kDiscoveryAddressFamily\[\] = "([^"]+)"',
                 discovery["addressFamily"], "C++ discovery address family", str)
    check_scalar(cpp_contract, r"kMaximumDiscoveryAddressCount = ([0-9']+)",
                 discovery["maximumAddressCount"], "C++ maximum discovery address count", integer_literal)

    expected_txt_keys = {"id", "name", "protocolVersion", "codec", "discoveryVersion", "address<N>"}
    if set(discovery["txtKeys"]) != expected_txt_keys:
        raise AssertionError("discovery TXT keys are out of sync")
    cpp_txt_key_patterns = {
        "id": r'kDiscoveryReceiverIdKey\[\] = "([^"]+)"',
        "name": r'kDiscoveryReceiverNameKey\[\] = "([^"]+)"',
        "protocolVersion": r'kDiscoveryProtocolVersionKey\[\] = "([^"]+)"',
        "codec": r'kDiscoveryCodecKey\[\] = "([^"]+)"',
        "discoveryVersion": r'kDiscoveryVersionKey\[\] = "([^"]+)"',
    }
    for expected_key, pattern in cpp_txt_key_patterns.items():
        check_scalar(cpp_contract, pattern, expected_key, f"C++ discovery TXT key {expected_key}", str)

    check_phone_catalog(kotlin_catalog)
    check_no_receiver_presets()
    check_ios_stream_settings_surface()
    if '"profiles"' in read(CONTRACT_PATH) or '"profiles"' in read(REPOSITORY_ROOT / "protocol/examples/cambridge-capabilities.json"):
        raise AssertionError("v6 contract examples must not contain profiles")
    if "--width" not in fixture or "--bitrate-bps" not in fixture:
        raise AssertionError("the native fixture must send explicit phone-authored video values")
    if 'profile_id="${CAMBRIDGE_PROFILE_ID:-fixture-720p30}"' not in android_smoke:
        raise AssertionError("the AVD smoke must opt into the explicit 720p fixture")
    if 'profile_id="${CAMBRIDGE_PROFILE_ID:-fixture-2k30}"' not in native_fixture:
        raise AssertionError("the native fixture must default to an explicit normal fixture")

    for example_name in (
        "cambridge-probe.json",
        "cambridge-capabilities.json",
        "cambridge-hello.json",
        "cambridge-accepted.json",
    ):
        example = json.loads(read(REPOSITORY_ROOT / "protocol/examples" / example_name))
        if example["protocolVersion"] != protocol_version:
            raise AssertionError(f"{example_name} protocol version is out of sync")
        if example_name in {"cambridge-hello.json", "cambridge-accepted.json"} \
                and example["profileId"] != sender_settings["profileId"]:
            raise AssertionError(f"{example_name} must use the opaque sender profile ID")

    print("CamBridge stream contract parity: OK")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, KeyError, StopIteration, json.JSONDecodeError) as error:
        print(f"CamBridge stream contract parity: {error}", file=sys.stderr)
        raise SystemExit(1)
