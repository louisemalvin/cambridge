#!/usr/bin/env python3
"""Select and atomically install the exact Linux plugin variant for OBS."""

from __future__ import annotations

import argparse
import os
import shutil
import sys
import tempfile
from pathlib import Path
from typing import Iterable, Sequence, TextIO

from cambridge_linux_bundle import (
    BundleError,
    DEFAULT_VARIANT_METADATA_NAME,
    LinuxVariant,
    ObsInstallation,
    discovered_obs_binaries,
    find_bundled_runtime_libraries,
    inspect_obs_installation,
    load_variant_metadata,
    validate_plugin,
    variant_matches_runtime,
    variant_plugin_path,
)


DEFAULT_PLUGIN_RELATIVE_DIRECTORY = Path(
    "obs-studio/plugins/cambridge-obs-plugin/bin/64bit"
)
CHOICE_START = 1
INVALID_CHOICE = 0
PARTIAL_FILE_PREFIX = ".cambridge-obs-plugin.so."
PARTIAL_FILE_SUFFIX = ".partial"
SUCCESS_EXIT_CODE = 0
FAILURE_EXIT_CODE = 1


class InstallerError(RuntimeError):
    """Raised when OBS selection or plugin installation cannot be completed."""


def default_plugin_directory(environment: dict[str, str] | None = None) -> Path:
    environment = environment or os.environ
    config_home = environment.get("XDG_CONFIG_HOME")
    config_directory = Path(config_home).expanduser() if config_home else Path.home() / ".config"
    return config_directory / DEFAULT_PLUGIN_RELATIVE_DIRECTORY


def _inspect_candidates(paths: Iterable[Path]) -> tuple[tuple[ObsInstallation, ...], tuple[str, ...]]:
    installations: list[ObsInstallation] = []
    failures: list[str] = []
    seen: set[Path] = set()
    for path in paths:
        try:
            installation = inspect_obs_installation(path)
        except BundleError as error:
            failures.append(f"{path}: {error}")
            continue
        if installation.binary in seen:
            continue
        seen.add(installation.binary)
        installations.append(installation)
    return tuple(installations), tuple(failures)


def choose_obs_installation(
    installations: Sequence[ObsInstallation],
    input_stream: TextIO | None = None,
    output_stream: TextIO | None = None,
    interactive: bool | None = None,
) -> ObsInstallation:
    if not installations:
        raise InstallerError("no supported OBS installation was found")
    if len(installations) == 1:
        return installations[0]
    input_stream = input_stream or sys.stdin
    output_stream = output_stream or sys.stdout
    if interactive is None:
        interactive = input_stream.isatty() and output_stream.isatty()
    if not interactive:
        paths = ", ".join(str(installation.binary) for installation in installations)
        raise InstallerError(
            "multiple OBS installations were found; rerun with --obs-path and choose one: "
            + paths
        )
    print("Multiple OBS installations were found:", file=output_stream)
    for index, installation in enumerate(installations, start=CHOICE_START):
        print(f"  {index}: {installation.binary}", file=output_stream)
    last_choice = len(installations)
    while True:
        print(f"Choose an OBS installation [1-{last_choice}]: ", end="", file=output_stream, flush=True)
        selected = input_stream.readline().strip()
        try:
            selection = int(selected)
        except ValueError:
            selection = INVALID_CHOICE
        if CHOICE_START <= selection <= last_choice:
            return installations[selection - CHOICE_START]
        print(f"Please enter a number from {CHOICE_START} to {last_choice}.", file=output_stream)


def discover_obs_installation(obs_path: Path | None) -> ObsInstallation:
    if obs_path is not None:
        try:
            return inspect_obs_installation(obs_path)
        except BundleError as error:
            raise InstallerError(str(error)) from error
    installations, failures = _inspect_candidates(discovered_obs_binaries())
    if not installations:
        details = f" Details: {'; '.join(failures)}" if failures else ""
        raise InstallerError(f"no supported OBS installation was found.{details}")
    return choose_obs_installation(installations)


def select_variant(
    bundle_root: Path,
    variants: Sequence[LinuxVariant],
    installation: ObsInstallation,
) -> tuple[LinuxVariant, Path]:
    bundled_libraries = find_bundled_runtime_libraries(bundle_root)
    if bundled_libraries:
        bundled = ", ".join(str(path.relative_to(bundle_root)) for path in bundled_libraries)
        raise InstallerError(f"bundle contains private OBS or FFmpeg libraries: {bundled}")
    failures: list[str] = []
    for variant in variants:
        plugin_path = variant_plugin_path(bundle_root, variant.identifier)
        if not variant_matches_runtime(variant, installation.resolved_sonames):
            failures.append(f"{variant.display_name}: OBS dependencies are not an exact match")
            continue
        try:
            validate_plugin(plugin_path, variant, installation.library_directories)
        except BundleError as error:
            failures.append(f"{variant.display_name}: {error}")
            continue
        return variant, plugin_path
    available = "; ".join(failures) or "no plugin variants are available"
    raise InstallerError(
        f"OBS at {installation.binary} does not match any bundled plugin variant. {available}"
    )


def install_plugin(
    source_path: Path,
    plugin_directory: Path,
    variant: LinuxVariant,
    library_directories: Iterable[Path],
) -> Path:
    try:
        validate_plugin(source_path, variant, library_directories)
    except BundleError as error:
        raise InstallerError(f"plugin validation failed before installation: {error}") from error

    plugin_directory.mkdir(parents=True, exist_ok=True)
    target_path = plugin_directory / source_path.name
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            dir=plugin_directory,
            prefix=PARTIAL_FILE_PREFIX,
            suffix=PARTIAL_FILE_SUFFIX,
            delete=False,
        ) as temporary_file:
            temporary_path = Path(temporary_file.name)
        shutil.copyfile(source_path, temporary_path)
        shutil.copymode(source_path, temporary_path)
        try:
            validate_plugin(temporary_path, variant, library_directories)
        except BundleError as error:
            raise InstallerError(f"plugin validation failed before replacement: {error}") from error
        os.replace(temporary_path, target_path)
        temporary_path = None
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
    return target_path


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--obs-path", type=Path)
    parser.add_argument("--plugin-dir", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def main() -> int:
    arguments = _parse_args()
    bundle_root = Path(__file__).resolve().parent
    try:
        variants = load_variant_metadata(bundle_root / DEFAULT_VARIANT_METADATA_NAME)
        installation = discover_obs_installation(arguments.obs_path)
        variant, source_path = select_variant(bundle_root, variants, installation)
        plugin_directory = (arguments.plugin_dir or default_plugin_directory()).expanduser()
        print(f"OBS installation: {installation.binary}")
        print(f"Selected plugin build: {variant.display_name}")
        if arguments.dry_run:
            print(f"Dry run: would install {source_path.name} to {plugin_directory}")
        else:
            installed_path = install_plugin(
                source_path,
                plugin_directory,
                variant,
                installation.library_directories,
            )
            print(f"Installed: {installed_path}")
        return SUCCESS_EXIT_CODE
    except (BundleError, InstallerError) as error:
        print(f"error: {error}", file=sys.stderr)
        return FAILURE_EXIT_CODE


if __name__ == "__main__":
    raise SystemExit(main())
