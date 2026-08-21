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
COMPONENT_VERSION_CHECK_PATH = REPOSITORY_ROOT / "scripts/development/cambridge_component_versions.py"
ANDROID_SMOKE_PATH = REPOSITORY_ROOT / "scripts/sender/android/test-emulator-cambridge.sh"
NATIVE_FIXTURE_PATH = REPOSITORY_ROOT / "scripts/receiver/linux/test-cambridge-fixture.sh"
IOS_STREAM_SETTINGS_VIEW_PATH = (
    REPOSITORY_ROOT / "sender/ios/CamBridge/Features/StreamSetup/StreamSettingsSelectionView.swift"
)
IOS_PRODUCTION_ROOT = REPOSITORY_ROOT / "sender/ios/CamBridge"
IOS_REMOVED_CAPABILITY_PATHS = (
    IOS_PRODUCTION_ROOT / "Features/StreamSetup/VideoModeSelectionView.swift",
    IOS_PRODUCTION_ROOT / "Platform/Camera/CameraCapabilityProbe.swift",
    IOS_PRODUCTION_ROOT / "Platform/Diagnostics/CapabilityReport.swift",
    IOS_PRODUCTION_ROOT / "Platform/Encoding/EncoderCapabilityProbe.swift",
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


def check_ios_direct_start_path() -> None:
    for path in IOS_REMOVED_CAPABILITY_PATHS:
        if path.exists():
            raise AssertionError(f"obsolete iOS capability path still exists: {path}")
    production_source = "\n".join(read(path) for path in IOS_PRODUCTION_ROOT.rglob("*.swift"))
    forbidden_tokens = (
        "CameraCapabilityProbe",
        "EncoderCapabilityProbe",
        "CapabilityReport",
        "VTSessionCopySupportedPropertyDictionary",
        "supportedBitrateRange",
        "bitrateRange",
    )
    for token in forbidden_tokens:
        if token in production_source:
            raise AssertionError(f"iOS production code contains forbidden capability gate: {token}")
    encoder_creation_count = production_source.count("VTCompressionSessionCreate(")
    if encoder_creation_count != 1:
        raise AssertionError(
            f"iOS must contain exactly one real VideoToolbox encoder creation path; found {encoder_creation_count}"
        )


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


def check_component_versions() -> None:
    result = subprocess.run(
        [sys.executable, str(COMPONENT_VERSION_CHECK_PATH), "--check"],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise AssertionError(f"component version manifest is invalid: {detail}")


def main() -> int:
    check_component_versions()
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
    media = contract["media"]
    control = contract["control"]
    computer = deployment.get("computer", {})
    expected_computer_keys = {"id", "displayName", "address", "interface", "sourceCidr"}
    if set(computer) != expected_computer_keys:
        raise AssertionError("deployment must define exactly one computer entry")
    network_values = [computer["address"], computer["interface"], computer["sourceCidr"]]
    if any(network_values) and not all(network_values):
        raise AssertionError("deployment network values must be all present or all blank")
    if protocol_version != 7:
        raise AssertionError("the CamBridge stream contract must be protocol v7")
    if schema["properties"]["protocolVersion"]["const"] != protocol_version:
        raise AssertionError("schema protocol version is out of sync")
    if "profiles" in schema["properties"]:
        raise AssertionError("the v6 schema must not expose receiver-owned profiles")
    if "profiles" in schema["allOf"][1]["then"]["required"]:
        raise AssertionError("the v6 capabilities schema must not require profiles")
    if schema["properties"]["fps"]["minimum"] != video["minimumFps"] or \
        schema["properties"]["fps"]["maximum"] != video["maximumFps"]:
        raise AssertionError("schema FPS bounds are out of sync")
    if schema["properties"]["targetBitrateBps"]["minimum"] != bitrate["minimumBps"] or \
        schema["properties"]["targetBitrateBps"]["maximum"] != bitrate["maximumBps"]:
        raise AssertionError("schema bitrate bounds are out of sync")

    check_scalar(kotlin_contract, r"const val PROTOCOL_VERSION = (\d+)", protocol_version, "Kotlin protocol version")
    check_scalar(kotlin_contract, r"const val CONNECT_TIMEOUT_MILLIS = ([0-9_]+)",
                 control["connectTimeoutMs"], "Kotlin control connect timeout", integer_literal)
    check_scalar(kotlin_contract, r"const val REQUEST_TIMEOUT_MILLIS = ([0-9_]+)",
                 control["requestTimeoutMs"], "Kotlin control request timeout", integer_literal)
    check_scalar(kotlin_contract, r"const val DEFAULT_CONTROL_PORT = ([0-9_]+)", defaults["controlPort"],
                 "Kotlin control port", integer_literal)
    check_scalar(kotlin_contract, r"const val DEFAULT_MEDIA_RTP_PORT = ([0-9_]+)", defaults["mediaRtpPort"],
                 "Kotlin media RTP port", integer_literal)
    check_scalar(kotlin_contract, r"const val DEFAULT_MEDIA_RTCP_PORT = ([0-9_]+)", defaults["mediaRtcpPort"],
                 "Kotlin media RTCP port", integer_literal)
    check_scalar(kotlin_contract, r"const val DEFAULT_SENDER_RTCP_PORT = ([0-9_]+)", defaults["senderRtcpPort"],
                 "Kotlin sender RTCP port", integer_literal)
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
    media_kotlin_values = {
        "RTP_PAYLOAD_TYPE": media["payloadType"],
        "RTX_PAYLOAD_TYPE": media["rtxPayloadType"],
        "RTP_CLOCK_RATE_HZ": media["clockRateHz"],
        "RTP_MTU_BYTES": media["mtuBytes"],
        "TWCC_EXTENSION_ID": media["twccExtensionId"],
        "JITTER_LATENCY_MILLIS": media["jitterLatencyMs"],
        "RTX_HISTORY_MILLIS": media["rtxHistoryMs"],
        "MAXIMUM_ACCESS_UNIT_BYTES": media["maxAccessUnitBytes"],
        "MAXIMUM_ENCODED_QUEUE": media["maxInFlightAccessUnits"],
        "APPSRC_MAXIMUM_BUFFERS": media["appsrcMaxBuffers"],
        "GCC_MINIMUM_BITRATE_FLOOR_BPS": media["gccMinimumBitrateFloorBps"],
        "KEYFRAME_INTERVAL_SECONDS": media["keyframeIntervalSeconds"],
    }
    for constant_name, expected_value in media_kotlin_values.items():
        check_scalar(
            kotlin_contract,
            rf"const val {constant_name} = ([0-9_]+)",
            expected_value,
            f"Kotlin media value {constant_name}",
            integer_literal,
        )
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
    check_scalar(cpp_contract, r"kControlConnectTimeoutMs = ([0-9']+)",
                 control["connectTimeoutMs"], "C++ control connect timeout", integer_literal)
    check_scalar(cpp_contract, r"kControlRequestTimeoutMs = ([0-9']+)",
                 control["requestTimeoutMs"], "C++ control request timeout", integer_literal)
    check_scalar(cpp_contract, r"kDefaultControlPort = ([0-9']+)", defaults["controlPort"],
                 "C++ control port", integer_literal)
    check_scalar(cpp_contract, r"kDefaultMediaRtpPort = ([0-9']+)", defaults["mediaRtpPort"],
                 "C++ media RTP port", integer_literal)
    check_scalar(cpp_contract, r"kDefaultMediaRtcpPort = ([0-9']+)", defaults["mediaRtcpPort"],
                 "C++ media RTCP port", integer_literal)
    check_scalar(cpp_contract, r"kDefaultSenderRtcpPort = ([0-9']+)", defaults["senderRtcpPort"],
                 "C++ sender RTCP port", integer_literal)
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
    cpp_media_values = {
        "kRtpPayloadType": (r"kRtpPayloadType = ([0-9']+)", media["payloadType"], integer_literal),
        "kRtxPayloadType": (r"kRtxPayloadType = ([0-9']+)", media["rtxPayloadType"], integer_literal),
        "kRtpClockRateHz": (r"kRtpClockRateHz = ([0-9']+)", media["clockRateHz"], integer_literal),
        "kRtpMtuBytes": (r"kRtpMtuBytes = ([0-9']+)", media["mtuBytes"], integer_literal),
        "kTwccExtensionId": (r"kTwccExtensionId = ([0-9']+)", media["twccExtensionId"], integer_literal),
        "kRtpSessionIndex": (r"kRtpSessionIndex = ([0-9']+)", media["rtpSessionIndex"], integer_literal),
        "kJitterLatencyMs": (r"kJitterLatencyMs = ([0-9']+)", media["jitterLatencyMs"], integer_literal),
        "kRtxHistoryMs": (r"kRtxHistoryMs = ([0-9']+)", media["rtxHistoryMs"], integer_literal),
        "kMaximumAccessUnitBytes": (
            r"kMaximumAccessUnitBytes = ([0-9']+)", media["maxAccessUnitBytes"], integer_literal,
        ),
        "kMaximumInFlightAccessUnits": (
            r"kMaximumInFlightAccessUnits = ([0-9']+)", media["maxInFlightAccessUnits"], integer_literal,
        ),
        "kAppsrcMaximumBuffers": (
            r"kAppsrcMaximumBuffers = ([0-9']+)", media["appsrcMaxBuffers"], integer_literal,
        ),
        "kGccMinimumBitrateFloorBps": (
            r"kGccMinimumBitrateFloorBps = ([0-9']+)", media["gccMinimumBitrateFloorBps"], integer_literal,
        ),
        "kRtcpFeedbackBandwidthFraction": (
            r"kRtcpFeedbackBandwidthFraction = ([0-9.]+)",
            media["rtcpFeedbackBandwidthFraction"],
            float,
        ),
    }
    for constant_name, (pattern, expected_value, parser) in cpp_media_values.items():
        check_scalar(cpp_contract, pattern, expected_value, f"C++ media value {constant_name}", parser)
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
    check_ios_direct_start_path()
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
