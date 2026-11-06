# SPDX-License-Identifier: Apache-2.0
"""Fail-closed Tycho release retry regression tests, including the #189 log."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("retry_maven_tycho",
                                               ROOT / "scripts/retry_maven_tycho.py")
assert SPEC is not None and SPEC.loader is not None
retry = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(retry)

VERSION = "5.0.3"
OBSERVED = (
    "[ERROR] Could not find artifact "
    "org.eclipse.tycho:tycho-maven-plugin:jar:5.0.3 in central "
    "(https://repo.maven.apache.org/maven2)"
)


class TychoReleaseRetryTests(unittest.TestCase):
    def test_exact_user_failure_is_retryable(self):
        self.assertTrue(retry.retryable_tycho_failure(OBSERVED, VERSION))
        self.assertTrue(retry.retryable_tycho_failure(
            "Could not transfer artifact org.eclipse.tycho:tycho-maven-plugin:pom:5.0.3",
            VERSION))

    def test_other_versions_and_build_errors_not_retryable(self):
        for log in (
            "Could not find artifact org.eclipse.tycho:tycho-maven-plugin:jar:5.0.4",
            "Could not find artifact org.somewhere:other-maven-plugin:jar:5.0.3",
            "[ERROR] Compilation failure",
            "[ERROR] Tests run: 100, Failures: 1",
            "[ERROR] Required p2 repository metadata missing",
            "[ERROR] Unresolveable build extension: Plugin org.eclipse.tycho:tycho-maven-plugin:5.0.3",
        ):
            self.assertFalse(retry.retryable_tycho_failure(log, VERSION), log)

    def test_retries_missing_plugin_then_succeeds(self):
        calls = []
        responses = iter([(1, True), (0, False)])
        def execute(command, version):
            calls.append((command, version))
            return next(responses)
        delays = []
        result = retry.run_with_retry(["mvn", "-U", "clean", "verify"], VERSION,
                                      execute=execute, pause=delays.append)
        self.assertEqual(result, 0)
        self.assertEqual(len(calls), 2)
        self.assertEqual(delays, [10])

    def test_persistent_plugin_failure_exhausts_limit(self):
        calls = []
        def execute(command, version):
            calls.append(version)
            return (1, True)
        waits = []
        result = retry.run_with_retry(["mvn", "-U"], VERSION, execute, waits.append)
        self.assertEqual(result, 1)
        self.assertEqual(calls, [VERSION] * 3)
        self.assertEqual(waits, [10, 20])

    def test_compilation_failure_fails_immediately(self):
        calls = []
        def execute(command, version):
            calls.append(version)
            return (42, False)
        result = retry.run_with_retry(["mvn", "-U"], VERSION, execute, lambda _: None)
        self.assertEqual(result, 42)
        self.assertEqual(calls, [VERSION])

    def test_exact_pinned_tycho_version_is_read_from_pom(self):
        self.assertEqual(retry.tycho_version(), VERSION)
        with tempfile.TemporaryDirectory() as path:
            invalid = Path(path, "pom.xml")
            invalid.write_text("<project><properties><tycho.version>latest</tycho.version>"
                               "</properties></project>")
            with self.assertRaises(ValueError):
                retry.tycho_version(invalid)

    def test_release_qualifier_cannot_be_injected(self):
        with self.assertRaises(SystemExit):
            retry.main(["--qualifier", "20261106; rm -rf ."])
        with mock.patch.object(retry, "run_with_retry", return_value=0) as runner:
            self.assertEqual(retry.main(["--qualifier", "20261106122200"]), 0)
            command = runner.call_args.args[0]
            self.assertEqual(command, ["mvn", "--batch-mode",
                                       "--no-transfer-progress", "-U",
                                       "-DforceContextQualifier=20261106122200",
                                       "clean", "verify"])


if __name__ == "__main__":
    unittest.main()
