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

    def test_main_release_429_descriptor_failure_is_retryable(self):
        version = "5.0.4"
        warning = ("[WARNING] GET request to https://repo.maven.apache.org/maven2/"
                   "org/eclipse/tycho/tycho-maven-plugin/5.0.4/"
                   "tycho-maven-plugin-5.0.4.pom failed (attempt 1 of 4) "
                   "due to status 429. Retrying in 5000 ms")
        descriptor = ("[ERROR] Failed to read artifact descriptor for "
                      "org.eclipse.tycho:tycho-maven-plugin:jar:5.0.4")

        def classify(lines, exit_code=1):
            with mock.patch.object(retry.subprocess, "Popen") as popen:
                process = popen.return_value.__enter__.return_value
                process.stdout = iter(line + "\n" for line in lines)
                process.wait.return_value = exit_code
                return retry.run_once(["mvn"], version)

        self.assertEqual(classify([warning, descriptor]), (1, True))
        self.assertEqual(classify([warning]), (1, False))
        self.assertEqual(classify([descriptor]), (1, False))
        self.assertEqual(classify([warning.replace("5.0.4", "5.0.3"), descriptor]),
                         (1, False))
        self.assertEqual(classify([warning.replace("repo.maven.apache.org", "other.example"),
                                   descriptor]), (1, False))
        self.assertEqual(classify([warning.replace("status 429", "status 4290"),
                                   descriptor]), (1, False))
        self.assertEqual(classify(["[WARNING] GET request to https://repo.maven.apache.org/"
                                   "maven2/.meta/prefixes.txt failed due to status 429.",
                                   descriptor]), (1, False))
        self.assertEqual(classify([warning, descriptor.replace("5.0.4", "5.0.40")]),
                         (1, False))
        self.assertEqual(classify([warning, "[ERROR] Compilation failure"]), (1, False))

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
        # Historical #189 retry fixtures deliberately use VERSION=5.0.3,
        # but the current pinned version can advance through Dependabot.
        self.assertRegex(retry.tycho_version(), r"^[0-9]+\.[0-9]+\.[0-9]+$")
        with tempfile.TemporaryDirectory() as path:
            pom = Path(path, "pom.xml")
            for version in ("5.0.3", "5.0.4"):
                with self.subTest(version=version):
                    pom.write_text(
                        '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                        "<properties><tycho.version>" + version +
                        "</tycho.version></properties></project>", encoding="utf-8")
                    self.assertEqual(retry.tycho_version(pom), version)
            pom.write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                "<properties><tycho.version>latest</tycho.version>"
                "</properties></project>", encoding="utf-8")
            with self.assertRaises(ValueError):
                retry.tycho_version(pom)

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
