#!/usr/bin/env python3
"""Shared Linux bundle metadata, ELF validation, and OBS inspection helpers."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping, Sequence


SCRIPT_PATH = Path(__file__).resolve()
REPOSITORY_ROOT = SCRIPT_PATH.parents[2]
DEFAULT_BUILDSPEC_PATH = REPOSITORY_ROOT / "receiver/obs/cambridge-obs-source/buildspec.json"
DEFAULT_VARIANT_METADATA_NAME = "cambridge-linux-variants.json"
BUNDLE_METADATA_SCHEMA_VERSION = 1
ELF_NEEDED_PATTERN = re.compile(r"Shared library: \[([^]]+)\]")
LDD_RESOLVED_PATTERN = re.compile(r"^\s*(\S+)\s+=>\s+(\S+)")
LDD_DIRECT_PATTERN = re.compile(r"^\s*(\S+)\s+\((?:0x)?[0-9a-fA-F]+\)")
UNRESOLVED_PATTERN = re.compile(r"(?:not found|undefined symbol)", re.IGNORECASE)
REQUIRED_FFMPEG_MODULES = ("libavcodec", "libavutil", "libswscale")
REQUIRED_MODULES = ("libobs", *REQUIRED_FFMPEG_MODULES)
OBS_LIBRARY_RELATIVE_PATHS = (
    Path("../lib"),
    Path("../lib64"),
    Path("../usr/lib"),
    Path("../usr/lib64"),
    Path("lib"),
    Path("lib64"),
)
PRIVATE_LIBRARY_PREFIXES = tuple(f"{module}.so" for module in REQUIRED_MODULES)
SUCCESS_EXIT_CODE = 0
FAILURE_EXIT_CODE = 1


class BundleError(RuntimeError):
    """Raised when a bundle or selected OBS installation is invalid."""


@dataclass(frozen=True)
class LinuxVariant:
    identifier: str
    display_name: str
    libobs_soname: str
    ffmpeg_sonames: Mapping[str, str]

    @property
    def sonames_by_module(self) -> Mapping[str, str]:
        return {"libobs": self.libobs_soname, **self.ffmpeg_sonames}

    @property
    def required_sonames(self) -> frozenset[str]:
        return frozenset(self.sonames_by_module.values())

    def as_dict(self) -> dict[str, object]:
        return {
            "id": self.identifier,
            "displayName": self.display_name,
            "libobsSoname": self.libobs_soname,
            "ffmpegSonames": dict(self.ffmpeg_sonames),
        }


@dataclass(frozen=True)
class ElfValidation:
    needed: frozenset[str]
    resolved: Mapping[str, Path]


@dataclass(frozen=True)
class ObsInstallation:
    binary: Path
    needed: frozenset[str]
    resolved: Mapping[str, Path]

    @property
    def resolved_sonames(self) -> frozenset[str]:
        return frozenset((*self.needed, *self.resolved.keys()))

    @property
    def library_directories(self) -> tuple[Path, ...]:
        directories = set(obs_library_directories(self.binary))
        directories.update(path.parent for path in self.resolved.values())
        return tuple(sorted(directories, key=str))


def _run(command: Sequence[str], environment: Mapping[str, str] | None = None) -> str:
    try:
        completed = subprocess.run(
            list(command),
            check=False,
            capture_output=True,
            text=True,
            env=dict(environment) if environment is not None else None,
        )
    except OSError as error:
        raise BundleError(f"could not run {' '.join(command)}: {error}") from error
    output = f"{completed.stdout}{completed.stderr}"
    if completed.returncode != 0:
        raise BundleError(f"command failed ({completed.returncode}): {' '.join(command)}\n{output.strip()}")
    return output


def _load_json(path: Path) -> dict[str, object]:
    try:
        with path.open(encoding="utf-8") as source:
            value = json.load(source)
    except (OSError, json.JSONDecodeError) as error:
        raise BundleError(f"could not read JSON metadata {path}: {error}") from error
    if not isinstance(value, dict):
        raise BundleError(f"JSON metadata must contain an object: {path}")
    return value


def _variant_from_dict(value: object, source: Path) -> LinuxVariant:
    if not isinstance(value, dict):
        raise BundleError(f"Linux variant metadata is not an object: {source}")
    required_fields = ("id", "displayName", "libobsSoname", "ffmpegSonames")
    if any(field not in value for field in required_fields):
        missing = next(field for field in required_fields if field not in value)
        raise BundleError(f"Linux variant metadata is missing {missing}: {source}")
    identifier = value["id"]
    display_name = value["displayName"]
    libobs_soname = value["libobsSoname"]
    raw_ffmpeg = value["ffmpegSonames"]
    if not all(isinstance(field, str) and field for field in (identifier, display_name, libobs_soname)):
        raise BundleError(f"Linux variant metadata contains an empty field: {source}")
    if not isinstance(raw_ffmpeg, dict):
        raise BundleError(f"Linux variant FFmpeg metadata is not an object: {source}")
    if set(raw_ffmpeg) != set(REQUIRED_FFMPEG_MODULES):
        raise BundleError(
            f"Linux variant {identifier} must declare {REQUIRED_FFMPEG_MODULES}: {source}"
        )
    if not all(isinstance(name, str) and isinstance(soname, str) and soname for name, soname in raw_ffmpeg.items()):
        raise BundleError(f"Linux variant {identifier} contains an invalid FFmpeg SONAME: {source}")
    ffmpeg_sonames = {name: raw_ffmpeg[name] for name in REQUIRED_FFMPEG_MODULES}
    return LinuxVariant(identifier, display_name, libobs_soname, ffmpeg_sonames)


def _validate_variant_identifiers(variants: Sequence[LinuxVariant], source: Path) -> tuple[LinuxVariant, ...]:
    identifiers = [variant.identifier for variant in variants]
    if len(set(identifiers)) != len(identifiers):
        raise BundleError(f"Linux variant identifiers must be unique: {source}")
    return tuple(variants)


def load_variants(path: Path = DEFAULT_BUILDSPEC_PATH) -> tuple[LinuxVariant, ...]:
    metadata = _load_json(path)
    linux_compatibility = metadata.get("linuxCompatibility")
    if not isinstance(linux_compatibility, dict):
        raise BundleError(f"Linux compatibility declarations are missing: {path}")
    raw_variants = linux_compatibility.get("variants")
    if not isinstance(raw_variants, list) or not raw_variants:
        raise BundleError(f"Linux variant declarations must be a non-empty list: {path}")
    return _validate_variant_identifiers(
        tuple(_variant_from_dict(value, path) for value in raw_variants), path
    )


def load_variant_metadata(path: Path) -> tuple[LinuxVariant, ...]:
    metadata = _load_json(path)
    if metadata.get("schemaVersion") != BUNDLE_METADATA_SCHEMA_VERSION:
        raise BundleError(f"unsupported Linux bundle metadata schema: {path}")
    raw_variants = metadata.get("variants")
    if not isinstance(raw_variants, list) or not raw_variants:
        raise BundleError(f"Bundle does not declare Linux variants: {path}")
    return _validate_variant_identifiers(
        tuple(_variant_from_dict(value, path) for value in raw_variants), path
    )


def variant_metadata(variants: Iterable[LinuxVariant]) -> dict[str, object]:
    return {
        "schemaVersion": BUNDLE_METADATA_SCHEMA_VERSION,
        "variants": [variant.as_dict() for variant in variants],
    }


def read_needed(path: Path) -> frozenset[str]:
    dynamic_section = _run(("readelf", "-d", str(path)))
    return frozenset(ELF_NEEDED_PATTERN.findall(dynamic_section))


def read_dynamic_tags(path: Path) -> frozenset[str]:
    dynamic_section = _run(("readelf", "-d", str(path)))
    tags = set()
    for tag in ("RPATH", "RUNPATH"):
        if re.search(rf"\({tag}\)", dynamic_section):
            tags.add(tag)
    return frozenset(tags)


def _runtime_environment(library_directories: Iterable[Path]) -> dict[str, str]:
    environment = os.environ.copy()
    paths = [str(path) for path in library_directories]
    existing = environment.get("LD_LIBRARY_PATH")
    if existing:
        paths.append(existing)
    if paths:
        environment["LD_LIBRARY_PATH"] = os.pathsep.join(paths)
    return environment


def read_ldd(path: Path, library_directories: Iterable[Path] = ()) -> Mapping[str, Path]:
    output = _run(("ldd", "-r", str(path)), _runtime_environment(library_directories))
    if UNRESOLVED_PATTERN.search(output):
        raise BundleError(f"unresolved runtime dependency in {path}\n{output.strip()}")
    resolved: dict[str, Path] = {}
    for line in output.splitlines():
        match = LDD_RESOLVED_PATTERN.match(line) or LDD_DIRECT_PATTERN.match(line)
        if match is None:
            continue
        name = match.group(1)
        candidate = match.group(2) if "=>" in line else name
        if candidate.startswith("/"):
            resolved[name] = Path(candidate)
    return resolved


def _module_sonames(names: Iterable[str], module: str) -> frozenset[str]:
    prefix = f"{module}.so."
    return frozenset(name for name in names if name.startswith(prefix))


def _validate_exact_modules(names: Iterable[str], variant: LinuxVariant, subject: str) -> None:
    actual_names = frozenset(names)
    errors: list[str] = []
    for module, expected in variant.sonames_by_module.items():
        actual = _module_sonames(actual_names, module)
        if actual != frozenset((expected,)):
            actual_description = ", ".join(sorted(actual)) or "missing"
            errors.append(f"{module}: expected {expected}, found {actual_description}")
    if errors:
        raise BundleError(f"{subject} does not match variant {variant.identifier}: {'; '.join(errors)}")


def validate_plugin(
    path: Path,
    variant: LinuxVariant,
    library_directories: Iterable[Path] = (),
    runtime_validation: bool = True,
) -> ElfValidation:
    if not path.is_file():
        raise BundleError(f"plugin variant is missing: {path}")
    tags = read_dynamic_tags(path)
    if tags:
        raise BundleError(f"plugin contains {'/'.join(sorted(tags))}: {path}")
    needed = read_needed(path)
    _validate_exact_modules(needed, variant, f"plugin {path} direct NEEDED entries")
    resolved: Mapping[str, Path] = {}
    if runtime_validation:
        resolved = read_ldd(path, library_directories)
    return ElfValidation(needed, resolved)


def variant_for_needed(needed: Iterable[str], variants: Iterable[LinuxVariant]) -> LinuxVariant:
    matches = []
    for variant in variants:
        try:
            _validate_exact_modules(needed, variant, "direct ELF dependencies")
        except BundleError:
            continue
        matches.append(variant)
    if len(matches) != 1:
        identifiers = ", ".join(variant.identifier for variant in matches) or "none"
        raise BundleError(f"direct ELF dependencies match {len(matches)} Linux variants ({identifiers})")
    return matches[0]


def variant_matches_runtime(variant: LinuxVariant, sonames: Iterable[str]) -> bool:
    try:
        _validate_exact_modules(sonames, variant, "OBS runtime dependencies")
    except BundleError:
        return False
    return True


def find_bundled_runtime_libraries(root: Path) -> tuple[Path, ...]:
    if not root.is_dir():
        return ()
    return tuple(
        path
        for path in root.rglob("*")
        if path.is_file() and path.name.startswith(PRIVATE_LIBRARY_PREFIXES)
    )


def obs_library_directories(binary: Path) -> tuple[Path, ...]:
    binary_parent = binary.parent
    candidates = [binary_parent / relative_path for relative_path in OBS_LIBRARY_RELATIVE_PATHS]
    unique: dict[str, Path] = {}
    for candidate in candidates:
        resolved = candidate.resolve(strict=False)
        if resolved.is_dir():
            unique[str(resolved)] = resolved
    return tuple(unique.values())


def is_flatpak_path(path: Path) -> bool:
    normalized = str(path.resolve(strict=False))
    return normalized.startswith("/app/") or "/flatpak/" in normalized or "/.var/app/" in normalized


def normalize_obs_binary(path: Path) -> Path:
    path = path.expanduser().resolve(strict=False)
    if path.is_file():
        return path
    candidates = (path / "bin/obs", path / "usr/bin/obs", path / "obs")
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise BundleError(f"OBS executable was not found at {path}")


def inspect_obs_installation(path: Path) -> ObsInstallation:
    binary = normalize_obs_binary(path)
    if is_flatpak_path(binary):
        raise BundleError("Flatpak OBS layouts are not supported by this installer")
    if not os.access(binary, os.X_OK):
        raise BundleError(f"OBS executable is not executable: {binary}")
    needed = read_needed(binary)
    resolved = read_ldd(binary, obs_library_directories(binary))
    return ObsInstallation(binary, needed, resolved)


def discovered_obs_binaries() -> tuple[Path, ...]:
    candidates: list[Path] = []
    configured_paths = os.environ.get("CAMBRIDGE_OBS_PATHS", "")
    candidates.extend(Path(path) for path in configured_paths.split(os.pathsep) if path)
    for directory in os.environ.get("PATH", "").split(os.pathsep):
        if directory:
            candidates.append(Path(directory) / "obs")
    resolved_command = shutil.which("obs")
    if resolved_command:
        candidates.append(Path(resolved_command))
    candidates.extend(
        Path(path)
        for path in (
            "/usr/bin/obs",
            "/usr/local/bin/obs",
            "/opt/obs/bin/obs",
            "/opt/obs/usr/bin/obs",
        )
    )
    candidates = [path for path in candidates if path.is_file()]
    unique: dict[str, Path] = {}
    for path in candidates:
        unique[str(path.resolve())] = path.resolve()
    return tuple(unique.values())


def variant_plugin_path(bundle_root: Path, identifier: str) -> Path:
    return bundle_root / "variants" / identifier / "cambridge-obs-plugin.so"


def write_variant_metadata(path: Path, variants: Iterable[LinuxVariant]) -> None:
    path.write_text(json.dumps(variant_metadata(variants), indent=2) + "\n", encoding="utf-8")


def _select_variants(variants: Sequence[LinuxVariant], identifiers: Sequence[str]) -> tuple[LinuxVariant, ...]:
    if not identifiers:
        return tuple(variants)
    by_identifier = {variant.identifier: variant for variant in variants}
    selected: list[LinuxVariant] = []
    for identifier in identifiers:
        if identifier not in by_identifier:
            raise BundleError(f"unknown Linux variant: {identifier}")
        if identifier not in {variant.identifier for variant in selected}:
            selected.append(by_identifier[identifier])
    return tuple(selected)


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--buildspec", type=Path, default=DEFAULT_BUILDSPEC_PATH)
    parser.add_argument("--list-ids", action="store_true")
    parser.add_argument("--write-metadata", type=Path)
    parser.add_argument("--include-id", action="append", default=[])
    parser.add_argument("--validate-plugin", type=Path)
    parser.add_argument("--print-variant-id", type=Path)
    parser.add_argument("--variant-id")
    parser.add_argument("--skip-runtime-validation", action="store_true")
    return parser.parse_args()


def main() -> int:
    arguments = _parse_args()
    try:
        variants = load_variants(arguments.buildspec)
        if arguments.list_ids:
            print("\n".join(variant.identifier for variant in variants))
            return SUCCESS_EXIT_CODE
        if arguments.write_metadata:
            write_variant_metadata(
                arguments.write_metadata,
                _select_variants(variants, arguments.include_id),
            )
            return SUCCESS_EXIT_CODE
        if arguments.print_variant_id:
            variant = variant_for_needed(read_needed(arguments.print_variant_id), variants)
            print(variant.identifier)
            return SUCCESS_EXIT_CODE
        if arguments.validate_plugin:
            variant = (
                next((candidate for candidate in variants if candidate.identifier == arguments.variant_id), None)
                if arguments.variant_id
                else variant_for_needed(read_needed(arguments.validate_plugin), variants)
            )
            if variant is None:
                raise BundleError(f"unknown Linux variant: {arguments.variant_id}")
            validate_plugin(
                arguments.validate_plugin,
                variant,
                runtime_validation=not arguments.skip_runtime_validation,
            )
            print(f"validated plugin variant: {variant.identifier}")
            return SUCCESS_EXIT_CODE
        raise BundleError(
            "one of --list-ids, --write-metadata, --print-variant-id, or --validate-plugin is required"
        )
    except BundleError as error:
        print(f"error: {error}", file=sys.stderr)
        return FAILURE_EXIT_CODE


if __name__ == "__main__":
    raise SystemExit(main())
