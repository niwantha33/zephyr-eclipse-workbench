# SPDX-License-Identifier: Apache-2.0
"""Offline regression tests for Zephyr version discovery and package isolation."""
import base64
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[2] / "plugins" / "io.github.niwantha33.zew.ui" / "tools"
spec = importlib.util.spec_from_file_location("release_catalog", TOOLS / "release_catalog.py")
catalog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalog)


def release(tag, body="Changes", draft=False, prerelease=False, url=None):
    return {
        "tag_name": tag, "name": "Zephyr " + tag,
        "draft": draft, "prerelease": prerelease,
        "published_at": "2026-10-28T22:23:00Z",
        "body": body,
        "html_url": url or catalog.URL_PREFIX + tag,
    }


class Response(io.BytesIO):
    status = 200


class Opener:
    def __init__(self, data):
        self.data = data
        self.calls = 0

    def open(self, request, timeout):
        self.calls += 1
        assert request.full_url == catalog.API
        assert timeout <= 20
        return Response(self.data)


class ReleaseCatalogTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.parent = Path(tmp.name)
        self.workspace = self.parent / "installed" / "workspace"
        (self.workspace / "zephyr").mkdir(parents=True)
        (self.workspace / "zephyr" / "VERSION").write_text(
            "VERSION_MAJOR = 4\nVERSION_MINOR = 4\nPATCHLEVEL = 0\n")
        self.sdk = self.parent / "zephyr-sdk-1.0.1"
        self.sdk.mkdir()
        (self.sdk / "sdk_version").write_text("1.0.1\n")

    def test_refuse_network_without_explicit_permission(self):
        client = Opener(b"[]")
        with self.assertRaisesRegex(ValueError, "allow-network"):
            catalog.official_releases(False, opener=client)
        self.assertEqual(0, client.calls)

    def test_release_filter_sorts_numeric_versions_and_rejects_untrusted_urls(self):
        payload = [
            release("v4.9.0"), release("v4.10.0"),
            release("v4.5.0-rc1"), release("v4.5.0", body="Long note"),
            release("v4.6.0", draft=True), release("v4.7.0", prerelease=True),
            release("v4.8.0", url="https://example.invalid/remote"),
            release("main"), release("v4.9.0", body="Deduplicated"),
        ]
        client = Opener(json.dumps(payload).encode())
        releases = catalog.official_releases(True, opener=client)
        self.assertEqual(["v4.10.0", "v4.9.0", "v4.5.0"],
                         [item["tag"] for item in releases])
        self.assertEqual(1, client.calls)

    def test_malformed_response_is_not_displayed_as_versions(self):
        for payload in (b"invalid-json", b"{}", b'"oops"'):
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                catalog.official_releases(True, opener=Opener(payload))

    def test_size_limit_rejects_unbounded_api_response(self):
        client = Opener(b"x" * (catalog.MAX_RESPONSE + 1))
        with self.assertRaisesRegex(ValueError, "size limit"):
            catalog.official_releases(True, opener=client)

    def test_local_inventory_reads_current_source_sdk_only(self):
        self.assertEqual("v4.4.0", catalog.installed_version(self.workspace))
        self.assertEqual("1.0.1", catalog.installed_sdk(self.sdk))
        self.assertEqual("", catalog.installed_sdk(self.parent / "absent"))
        self.assertEqual("Current checkout",
                         catalog.mark({"tag": "v4.4.0"}, "v4.4.0", set()))
        self.assertEqual("Newer release available",
                         catalog.mark({"tag": "v4.5.0"}, "v4.4.0", set()))
        self.assertEqual("Older release",
                         catalog.mark({"tag": "v4.3.0"}, "v4.4.0", set()))

    def test_recorded_candidate_is_source_only_never_build_ready(self):
        candidate = self.parent / "zephyr-src-v4.5.0"
        (candidate / ".zew").mkdir(parents=True)
        (candidate / ".zew-source-ready").write_text("SOURCE_VERIFIED_ONLY\n")
        (candidate / ".zew" / "source-lock.json").write_text(json.dumps({
            "kind": "zephyr-source-only", "status": "SOURCE_VERIFIED_ONLY",
            "release": "v4.5.0",
        }))
        found = catalog.prepared_releases(self.parent)
        self.assertEqual({"v4.5.0"}, found)
        self.assertEqual("Source-only candidate recorded",
                         catalog.mark({"tag": "v4.5.0"}, "v4.4.0", found))
        self.assertFalse((candidate / ".zew-ready").exists())

    def test_fake_or_oversized_source_lock_does_not_claim_installation(self):
        candidate = self.parent / "zephyr-src-bad"
        (candidate / ".zew").mkdir(parents=True)
        (candidate / ".zew-source-ready").write_text("SOURCE_VERIFIED_ONLY\n")
        path = candidate / ".zew" / "source-lock.json"
        path.write_text('{"kind":"zephyr-source-only","release":"main"}')
        self.assertEqual(set(), catalog.prepared_releases(self.parent))
        path.write_text("x" * (catalog.MAX_LOCK + 1))
        self.assertEqual(set(), catalog.prepared_releases(self.parent))

    def test_local_command_no_network_no_writes(self):
        command = [sys.executable, str(TOOLS / "release_catalog.py"), "local",
                   "--workspace", str(self.workspace), "--sdk", str(self.sdk),
                   "--sources-parent", str(self.parent)]
        result = subprocess.run(command, check=True, text=True,
                                capture_output=True, timeout=10)
        kinds = [line.split("\t")[0] for line in result.stdout.splitlines()]
        self.assertEqual(["STATE", "RELEASE", "DONE"], kinds)
        state = result.stdout.splitlines()[0].split("\t")
        self.assertEqual("v4.4.0", base64.b64decode(state[1]).decode())
        self.assertEqual("1.0.1", base64.b64decode(state[2]).decode())
        self.assertFalse((self.workspace / ".zew").exists())

    def test_check_command_is_opt_in_even_when_called_via_cli(self):
        command = [sys.executable, str(TOOLS / "release_catalog.py"), "check",
                   "--workspace", str(self.workspace)]
        result = subprocess.run(command, text=True, capture_output=True, timeout=10)
        self.assertEqual(2, result.returncode)
        self.assertIn("ERROR\t", result.stdout)
        self.assertFalse((self.workspace / ".zew").exists())


if __name__ == "__main__":
    unittest.main()
