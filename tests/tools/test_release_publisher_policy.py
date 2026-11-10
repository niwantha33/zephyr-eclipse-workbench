# SPDX-License-Identifier: Apache-2.0
"""Regression guards for publishing a qualified, exact-source p2 update.

These are workflow policy tests, NOT device or installation verification.
"""
from pathlib import Path
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]


class ReleasePublisherPolicyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.publisher = yaml.safe_load(
            (ROOT / ".github/workflows/private-update.yml").read_text(encoding="utf-8")
        )
        cls.pr_build = yaml.safe_load(
            (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
        )

    def test_publish_requires_both_real_linux_and_windows_jobs(self):
        jobs = self.publisher["jobs"]
        publish = jobs["publish"]
        self.assertEqual(
            set(publish["needs"]),
            {"build_and_verify", "windows_firmware_qualification"},
        )
        condition = publish["if"]
        self.assertIn("needs.build_and_verify.result == 'success'", condition)
        self.assertIn("needs.windows_firmware_qualification.result == 'success'", condition)
        self.assertIn("github.ref == 'refs/heads/main'", condition)
        self.assertEqual(jobs["build_and_verify"]["runs-on"], "ubuntu-24.04")

    def test_windows_build_is_exact_sha_and_has_no_qualification_bypass(self):
        job = self.publisher["jobs"]["windows_firmware_qualification"]
        self.assertEqual(job["runs-on"], "windows-2025")
        self.assertIn("github.ref == 'refs/heads/main'", job["if"])
        steps = job["steps"]
        checkout = next(step for step in steps if "actions/checkout@" in step.get("uses", ""))
        self.assertEqual(checkout["with"]["ref"], "${{ github.sha }}")
        self.assertIs(checkout["with"]["persist-credentials"], False)
        self.assertFalse(any(step.get("continue-on-error") for step in steps))
        commands = "\n".join(str(step.get("run", "")) for step in steps)
        self.assertIn("python scripts/test_core.py", commands)
        self.assertIn("python scripts/windows_firmware_matrix.py --root C:/zew-ci --setup-only", commands)
        self.assertIn("python scripts/windows_firmware_matrix.py --root C:/zew-ci --run-only", commands)
        self.assertIn(
            "python scripts/qualify_firmware_matrix.py "
            "--input windows-firmware-results.json --output windows-firmware-qualification.json",
            commands,
        )
        self.assertIn("PyYAML==6.0.3", commands)
        self.assertIn("JAVA_HOME_21_X64", commands)

    def test_linux_publisher_checks_packaged_site_and_installed_eclipse(self):
        job = self.publisher["jobs"]["build_and_verify"]
        steps = job["steps"]
        checkout = next(step for step in steps if "actions/checkout@" in step.get("uses", ""))
        self.assertEqual(checkout["with"]["ref"], "${{ github.sha }}")
        commands = "\n".join(str(step.get("run", "")) for step in steps)
        self.assertIn("scripts/test_core.py", commands)
        self.assertIn("scripts/verify_site.py", commands)
        self.assertIn("scripts/editor_smoke.py --native-workbench", commands)
        self.assertIn("scripts/site_tree_digest.py", commands)

    def test_pr_qualification_includes_windows_and_python_tests(self):
        jobs = self.pr_build["jobs"]
        self.assertEqual(
            set(jobs["evidence-qualification-gate"]["needs"]),
            {"package", "windows-firmware", "evidence-pytest", "evidence-pytest39"},
        )
        self.assertEqual(jobs["windows-firmware"]["runs-on"], "windows-2025")
        commands = "\n".join(str(step.get("run", "")) for step in jobs["package"]["steps"])
        self.assertIn("scripts/test_core.py", commands)


if __name__ == "__main__":
    unittest.main()
