#!/usr/bin/env python3
"""Send a contract-conformant H.264/RTP fixture to the native OBS source."""

from __future__ import annotations

import argparse
import json
import select
import socket
import struct
import subprocess
import sys
import time
import uuid
from pathlib import Path
from typing import Any


CONTROL_HEADER_BYTES = 4
CONTROL_TIMEOUT_SECONDS = 2.0
CONTROL_POLL_SECONDS = 0.1
STATUS_SAMPLE_INTERVAL_SECONDS = 1.0
RECONNECT_PAUSE_SECONDS = 1.0
PROCESS_STOP_TIMEOUT_SECONDS = 3.0
MINIMUM_DURATION_SECONDS = 1.0
DEFAULT_STARTUP_DELAY_SECONDS = 0.0
DEFAULT_ROTATION_DEGREES = 0
PORTRAIT_ROTATIONS = frozenset({90, 270})
TEST_CARD_FONT_SIZE = 32
TEST_CARD_MARGIN = 32
TEST_CARD_FLASH_WIDTH = 96
TEST_CARD_FLASH_HEIGHT = 96
TEST_CARD_PATCH_SIZE = 96
TEST_CARD_PATCH_GAP = 16
TEST_CARD_PATCH_Y = TEST_CARD_MARGIN + TEST_CARD_FONT_SIZE + TEST_CARD_PATCH_GAP
DEFAULT_CONTRACT = Path(__file__).resolve().parents[2] / "protocol" / "direct-stream-contract.json"


def load_contract(path: Path, requested_profile_id: str | None) -> tuple[dict[str, Any], dict[str, Any], str]:
    contract = json.loads(path.read_text(encoding="utf-8"))
    profiles = {profile["id"]: profile for profile in contract["profiles"]}
    profile_id = requested_profile_id or contract["defaults"]["profileId"]
    try:
        profile = profiles[profile_id]
    except KeyError as error:
        raise ValueError(f"unknown profile: {profile_id}") from error
    return contract, profile, profile_id


def send_frame(connection: socket.socket, message: dict[str, Any]) -> None:
    payload = json.dumps(message, separators=(",", ":")).encode("utf-8")
    connection.sendall(struct.pack(">I", len(payload)) + payload)


def display_geometry(profile: dict[str, Any], rotation_degrees: int) -> tuple[int, int]:
    if rotation_degrees in PORTRAIT_ROTATIONS:
        return profile["height"], profile["width"]
    return profile["width"], profile["height"]


def receive_frame(connection: socket.socket, deadline: float) -> dict[str, Any]:
    data = bytearray()
    while len(data) < CONTROL_HEADER_BYTES:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("timed out waiting for control header")
        connection.settimeout(remaining)
        chunk = connection.recv(CONTROL_HEADER_BYTES - len(data))
        if not chunk:
            raise ConnectionError("control connection closed before acceptance")
        data.extend(chunk)
    message_size = struct.unpack(">I", data)[0]
    if message_size <= 0:
        raise ValueError("receiver returned an empty control frame")
    payload = bytearray()
    while len(payload) < message_size:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("timed out waiting for control payload")
        connection.settimeout(remaining)
        chunk = connection.recv(message_size - len(payload))
        if not chunk:
            raise ConnectionError("control connection closed before acceptance")
        payload.extend(chunk)
    return json.loads(payload.decode("utf-8"))


def connect_control(
    host: str,
    port: int,
    contract: dict[str, Any],
    profile: dict[str, Any],
    generation: int,
    rotation_degrees: int,
) -> tuple[socket.socket, str, dict[str, Any]]:
    session_id = f"fixture-{uuid.uuid4()}"
    display_width, display_height = display_geometry(profile, rotation_degrees)
    connection = socket.create_connection((host, port), timeout=CONTROL_TIMEOUT_SECONDS)
    send_frame(
        connection,
        {
            "protocolVersion": contract["protocolVersion"],
            "type": "hello",
            "sessionId": session_id,
            "generation": generation,
            "codec": "h264",
            "codedWidth": profile["width"],
            "codedHeight": profile["height"],
            "displayWidth": display_width,
            "displayHeight": display_height,
            "rotationDegrees": rotation_degrees,
            "fps": profile["fps"],
            "bitrateBps": profile["bitrateBps"],
        },
    )
    accepted = receive_frame(connection, time.monotonic() + CONTROL_TIMEOUT_SECONDS)
    if accepted.get("type") != "accepted":
        raise RuntimeError(f"receiver rejected fixture: {accepted}")
    expected_media_port = port + contract["defaults"]["mediaPortOffset"]
    if accepted.get("mediaPort") != expected_media_port:
        raise RuntimeError(f"receiver returned unexpected media port: {accepted}")
    connection.setblocking(False)
    return connection, session_id, accepted


def start_ffmpeg(
    ffmpeg: str,
    media_host: str,
    media_port: int,
    profile: dict[str, Any],
    contract: dict[str, Any],
    stderr_path: Path,
) -> tuple[subprocess.Popen[bytes], Any]:
    media = contract["media"]
    test_card_filter = (
        f"drawtext=fontcolor=white:fontsize={TEST_CARD_FONT_SIZE}:box=1:boxcolor=black@0.70:"
        f"boxborderw=8:text='direct webcam {profile['id']} {profile['width']}x{profile['height']} "
        f"frame %{{n}} pts %{{pts\\:hms}}':x={TEST_CARD_MARGIN}:y={TEST_CARD_MARGIN},"
        f"drawbox=x=iw-{TEST_CARD_FLASH_WIDTH}-{TEST_CARD_MARGIN}:y={TEST_CARD_MARGIN}:"
        f"w={TEST_CARD_FLASH_WIDTH}:h={TEST_CARD_FLASH_HEIGHT}:color=white@1.0:t=fill:"
        f"enable='lt(mod(n\\,{profile['fps']})\\,2)',"
        f"drawbox=x={TEST_CARD_MARGIN}:y={TEST_CARD_PATCH_Y}:w={TEST_CARD_PATCH_SIZE}:"
        f"h={TEST_CARD_PATCH_SIZE}:color=red@1.0:t=fill,"
        f"drawbox=x={TEST_CARD_MARGIN + TEST_CARD_PATCH_SIZE + TEST_CARD_PATCH_GAP}:"
        f"y={TEST_CARD_PATCH_Y}:w={TEST_CARD_PATCH_SIZE}:h={TEST_CARD_PATCH_SIZE}:"
        f"color=green@1.0:t=fill,"
        f"drawbox=x={TEST_CARD_MARGIN + 2 * (TEST_CARD_PATCH_SIZE + TEST_CARD_PATCH_GAP)}:"
        f"y={TEST_CARD_PATCH_Y}:w={TEST_CARD_PATCH_SIZE}:h={TEST_CARD_PATCH_SIZE}:"
        f"color=blue@1.0:t=fill,"
        f"drawtext=fontcolor=black:fontsize={TEST_CARD_FONT_SIZE}:text='RED':"
        f"x={TEST_CARD_MARGIN + 12}:y={TEST_CARD_PATCH_Y + 30},"
        f"drawtext=fontcolor=black:fontsize={TEST_CARD_FONT_SIZE}:text='GREEN':"
        f"x={TEST_CARD_MARGIN + TEST_CARD_PATCH_SIZE + TEST_CARD_PATCH_GAP + 2}:"
        f"y={TEST_CARD_PATCH_Y + 30},"
        f"drawtext=fontcolor=white:fontsize={TEST_CARD_FONT_SIZE}:text='BLUE':"
        f"x={TEST_CARD_MARGIN + 2 * (TEST_CARD_PATCH_SIZE + TEST_CARD_PATCH_GAP) + 2}:"
        f"y={TEST_CARD_PATCH_Y + 30}"
    )
    command = [
        ffmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-nostdin",
        "-re",
        "-f",
        "lavfi",
        "-i",
        f"testsrc2=size={profile['width']}x{profile['height']}:rate={profile['fps']}",
        "-vf",
        test_card_filter,
        "-an",
        "-c:v",
        "libx264",
        "-pix_fmt",
        "yuv420p",
        "-preset",
        "ultrafast",
        "-tune",
        "zerolatency",
        "-b:v",
        str(profile["bitrateBps"]),
        "-maxrate",
        str(profile["bitrateBps"]),
        "-bufsize",
        str(profile["bitrateBps"] * 2),
        "-g",
        str(profile["fps"]),
        "-keyint_min",
        str(profile["fps"]),
        "-sc_threshold",
        "0",
        "-bf",
        "0",
        "-x264-params",
        f"repeat-headers=1:slice-max-size={media['mtuBytes'] - media['rtpHeaderBytes']}",
        "-payload_type",
        str(media["payloadType"]),
        "-f",
        "rtp",
        "-rtpflags",
        "h264_mode0",
        f"rtp://{media_host}:{media_port}?pkt_size={media['mtuBytes']}",
    ]
    stderr = stderr_path.open("wb")
    process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=stderr)
    return process, stderr


def stop_process(process: subprocess.Popen[bytes] | None, stderr: Any | None) -> None:
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=PROCESS_STOP_TIMEOUT_SECONDS)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
    if stderr is not None:
        stderr.close()


def consume_control(
    connection: socket.socket,
    buffer: bytearray,
    summary: dict[str, Any],
) -> None:
    try:
        chunk = connection.recv(64 * 1024)
    except BlockingIOError:
        return
    if not chunk:
        raise ConnectionError("receiver closed the control connection")
    buffer.extend(chunk)
    while len(buffer) >= CONTROL_HEADER_BYTES:
        message_size = struct.unpack(">I", buffer[:CONTROL_HEADER_BYTES])[0]
        if len(buffer) < CONTROL_HEADER_BYTES + message_size:
            return
        payload_start = CONTROL_HEADER_BYTES
        payload_end = payload_start + message_size
        message = json.loads(bytes(buffer[payload_start:payload_end]).decode("utf-8"))
        del buffer[:payload_end]
        message_type = message.get("type")
        if message_type == "status":
            metrics = message.get("metrics", {})
            summary["statusCount"] += 1
            summary["lastStatus"] = metrics
            for name, value in metrics.items():
                if isinstance(value, int):
                    summary["maximumMetrics"][name] = max(summary["maximumMetrics"].get(name, value), value)
        elif message_type == "request_idr":
            summary["idrRequests"] += 1
        elif message_type == "error":
            summary["receiverErrors"].append(message.get("error", "unknown receiver error"))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--profile", default=None)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--control-port", type=int, default=None)
    parser.add_argument("--media-port", type=int, default=None)
    parser.add_argument("--duration", type=float, default=30.0)
    parser.add_argument("--reconnect-after", type=float, default=None)
    parser.add_argument(
        "--rotation-degrees",
        type=int,
        choices=(0, 90, 180, 270),
        default=DEFAULT_ROTATION_DEGREES,
    )
    parser.add_argument("--startup-delay", type=float, default=DEFAULT_STARTUP_DELAY_SECONDS)
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.duration < MINIMUM_DURATION_SECONDS:
        raise ValueError("duration must be at least one second")
    if args.startup_delay < DEFAULT_STARTUP_DELAY_SECONDS:
        raise ValueError("startup delay cannot be negative")
    if args.reconnect_after is not None and not MINIMUM_DURATION_SECONDS <= args.reconnect_after < args.duration:
        raise ValueError("reconnect-after must be inside the fixture duration")
    contract, profile, profile_id = load_contract(args.contract, args.profile)
    control_port = args.control_port or contract["defaults"]["controlPort"]
    media_port = args.media_port or control_port + contract["defaults"]["mediaPortOffset"]
    summary: dict[str, Any] = {
        "profile": profile,
        "durationSeconds": args.duration,
        "reconnectAfterSeconds": args.reconnect_after,
        "controlConnections": 0,
        "statusCount": 0,
        "lastStatus": {},
        "maximumMetrics": {},
        "idrRequests": 0,
        "receiverErrors": [],
    }
    connection: socket.socket | None = None
    ffmpeg_process: subprocess.Popen[bytes] | None = None
    ffmpeg_stderr: Any | None = None
    control_buffer = bytearray()
    generation = 1
    session_id: str | None = None
    started = time.monotonic()
    reconnect_done = False
    stderr_path = (args.output.parent if args.output else Path.cwd()) / f"direct-webcam-fixture-{profile_id}.ffmpeg.log"
    try:
        connection, session_id, _ = connect_control(
            args.host,
            control_port,
            contract,
            profile,
            generation,
            args.rotation_degrees,
        )
        summary["controlConnections"] += 1
        if args.startup_delay > DEFAULT_STARTUP_DELAY_SECONDS:
            time.sleep(args.startup_delay)
        ffmpeg_process, ffmpeg_stderr = start_ffmpeg(
            args.ffmpeg, args.host, media_port, profile, contract, stderr_path
        )
        while time.monotonic() - started < args.duration:
            elapsed = time.monotonic() - started
            if ffmpeg_process.poll() is not None:
                raise RuntimeError(f"ffmpeg exited with status {ffmpeg_process.returncode}")
            if (
                args.reconnect_after is not None
                and not reconnect_done
                and elapsed >= args.reconnect_after
            ):
                stop_process(ffmpeg_process, ffmpeg_stderr)
                ffmpeg_process = None
                ffmpeg_stderr = None
                connection.close()
                connection = None
                time.sleep(RECONNECT_PAUSE_SECONDS)
                generation += 1
                control_buffer.clear()
                connection, session_id, _ = connect_control(
                    args.host,
                    control_port,
                    contract,
                    profile,
                    generation,
                    args.rotation_degrees,
                )
                summary["controlConnections"] += 1
                ffmpeg_process, ffmpeg_stderr = start_ffmpeg(
                    args.ffmpeg, args.host, media_port, profile, contract, stderr_path
                )
                reconnect_done = True
                continue
            if connection is not None:
                readable, _, _ = select.select([connection], [], [], CONTROL_POLL_SECONDS)
                if readable:
                    consume_control(connection, control_buffer, summary)
        if connection is not None:
            try:
                send_frame(
                    connection,
                    {
                        "protocolVersion": contract["protocolVersion"],
                        "type": "stop",
                        "sessionId": session_id,
                        "generation": generation,
                    },
                )
            except OSError:
                pass
    finally:
        stop_process(ffmpeg_process, ffmpeg_stderr)
        if connection is not None:
            connection.close()
        summary["elapsedSeconds"] = time.monotonic() - started
        summary["rotationDegrees"] = args.rotation_degrees
        summary["displayWidth"], summary["displayHeight"] = display_geometry(profile, args.rotation_degrees)
        summary["ffmpegLog"] = str(stderr_path)
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(summary, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, TimeoutError, ValueError, json.JSONDecodeError) as error:
        print(f"fixture error: {error}", file=sys.stderr)
        raise SystemExit(1)
