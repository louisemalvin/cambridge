#!/usr/bin/env python3
"""Tests for independent CamBridge component version handling."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path


SCRIPT_DIRECTORY = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIRECTORY))

import cambridge_component_versions as versions  # noqa: E402


class ComponentVersionTests(unittest.TestCase):
    def test_active_components_are_independent_and_ios_is_deferred(self) -> None:
        self.assertEqual(versions.component_version("android"), "0.5.2")
        self.assertEqual(versions.component_version("obs"), "0.5.2")
        self.assertIsNone(versions.component_version("ios"))

    def test_release_tags_select_their_component(self) -> None:
        self.assertEqual(versions.parse_release_tag("android-v0.4.0"), ("android", "0.4.0"))
        self.assertEqual(versions.parse_release_tag("obs-v0.4.0"), ("obs", "0.4.0"))
        versions.verify_release("android", "0.5.2")
        versions.verify_release("obs", "0.5.2")

    def test_historical_shared_tag_is_not_a_current_release_tag(self) -> None:
        with self.assertRaises(versions.ComponentVersionError):
            versions.parse_release_tag("v0.3.3")

    def test_deferred_ios_cannot_be_released(self) -> None:
        with self.assertRaises(versions.ComponentVersionError):
            versions.verify_release("ios", "0.4.0")


if __name__ == "__main__":
    unittest.main()
