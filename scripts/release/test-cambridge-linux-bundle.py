#!/usr/bin/env python3
"""Unit tests for Linux variant metadata, selection, and installation safety."""

from __future__ import annotations

import io
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT_DIRECTORY = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIRECTORY))

import cambridge_linux_bundle as bundle  # noqa: E402

INSTALLER_MODULE_SPEC = importlib.util.spec_from_file_location(
    "cambridge_linux_installer",
    SCRIPT_DIRECTORY / "install-linux-plugin.py",
)
assert INSTALLER_MODULE_SPEC is not None
assert INSTALLER_MODULE_SPEC.loader is not None
installer = importlib.util.module_from_spec(INSTALLER_MODULE_SPEC)
INSTALLER_MODULE_SPEC.loader.exec_module(installer)


class LinuxBundleTests(unittest.TestCase):
    def setUp(self) -> None:
        self.variants = bundle.load_variants()
        self.ubuntu_variant = self.variants[0]
        self.cachyos_variant = self.variants[1]

    def test_declared_variants_have_exact_contract_sonames(self) -> None:
        self.assertEqual(
            [variant.identifier for variant in self.variants],
            ["ubuntu-26.04", "cachyos-obs-32.2.2"],
        )
        self.assertEqual(
            self.ubuntu_variant.as_dict(),
            {
                "id": "ubuntu-26.04",
                "displayName": "Ubuntu 26.04 release environment",
                "libobsSoname": "libobs.so.30",
                "ffmpegSonames": {
                    "libavcodec": "libavcodec.so.62",
                    "libavutil": "libavutil.so.60",
                    "libswscale": "libswscale.so.9",
                },
            },
        )
        self.assertEqual(
            self.cachyos_variant.as_dict(),
            {
                "id": "cachyos-obs-32.2.2",
                "displayName": "CachyOS with OBS 32.2.2",
                "libobsSoname": "libobs.so.30",
                "ffmpegSonames": {
                    "libavcodec": "libavcodec.so.63",
                    "libavutil": "libavutil.so.61",
                    "libswscale": "libswscale.so.10",
                },
            },
        )

    def test_variant_selection_requires_exact_sonames(self) -> None:
        needed = self.cachyos_variant.required_sonames | frozenset(("libpthread.so.0",))
        self.assertIs(
            bundle.variant_for_needed(needed, self.variants),
            self.cachyos_variant,
        )
        self.assertFalse(
            bundle.variant_matches_runtime(
                self.ubuntu_variant,
                self.cachyos_variant.required_sonames,
            )
        )
        with self.assertRaises(bundle.BundleError):
            bundle.variant_for_needed(
                frozenset(("libobs.so.30", "libavcodec.so.61")),
                self.variants,
            )

    def test_private_runtime_libraries_are_detected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            private_library = root / "variants/ubuntu-26.04/libavcodec.so.62"
            private_library.parent.mkdir(parents=True)
            private_library.write_bytes(b"private")
            self.assertEqual(
                bundle.find_bundled_runtime_libraries(root),
                (private_library,),
            )

    def test_multiple_obs_installations_require_a_choice(self) -> None:
        installations = tuple(
            installer.ObsInstallation(Path(path), frozenset(), {})
            for path in ("/usr/bin/obs", "/opt/obs/bin/obs")
        )
        output = io.StringIO()
        selected = installer.choose_obs_installation(
            installations,
            input_stream=io.StringIO("2\n"),
            output_stream=output,
            interactive=True,
        )
        self.assertEqual(selected.binary, Path("/opt/obs/bin/obs"))
        with self.assertRaises(installer.InstallerError):
            installer.choose_obs_installation(installations, interactive=False)

    def test_failed_validation_leaves_existing_plugin_unchanged(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            source = root / "source.so"
            plugin_directory = root / "plugins"
            target = plugin_directory / source.name
            source.write_bytes(b"new plugin")
            plugin_directory.mkdir()
            target.write_bytes(b"old plugin")

            with patch.object(installer, "validate_plugin", side_effect=bundle.BundleError("invalid")):
                with self.assertRaises(installer.InstallerError):
                    installer.install_plugin(source, plugin_directory, self.ubuntu_variant, ())

            self.assertEqual(target.read_bytes(), b"old plugin")

    def test_successful_install_replaces_plugin_after_validation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            source = root / "source.so"
            plugin_directory = root / "plugins"
            source.write_bytes(b"new plugin")

            with patch.object(installer, "validate_plugin"):
                target = installer.install_plugin(source, plugin_directory, self.ubuntu_variant, ())

            self.assertEqual(target.read_bytes(), b"new plugin")


if __name__ == "__main__":
    unittest.main()
