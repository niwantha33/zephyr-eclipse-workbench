# SPDX-License-Identifier: Apache-2.0
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from zew import main

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("studio_resource_sync", ROOT / "scripts/sync_studio_resources.py")
SYNC = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SYNC)


class StudioCliTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="studio-cli-Ω ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.build = self.root / "build"
        self.build.mkdir()

    def invoke(self, *args):
        out, error = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(error):
            status = main(list(map(str, args)))
        return status, json.loads(out.getvalue() or error.getvalue())

    def test_all_milestone_commands_are_discoverable(self):
        for name in ("kconfig", "dt", "driver", "resources", "images", "preflight", "compare",
                     "diagnose", "answer", "view", "rules"):
            with self.subTest(name=name), contextlib.redirect_stdout(io.StringIO()), self.assertRaises(SystemExit) as result:
                main(["studio-" + name, "--help"])
            self.assertEqual(result.exception.code, 0)

    def test_missing_build_metadata_remains_unknown(self):
        status, report = self.invoke("studio-kconfig", "--build", self.build, "--symbol", "CONFIG_ADC")
        self.assertEqual(status, 1)
        self.assertEqual(report["status"], "partial")
        self.assertEqual(report["findings"][0]["confidence"], "unknown")
        status, report = self.invoke("studio-images", "--build", self.build)
        self.assertEqual(status, 1)
        self.assertEqual(report["status"], "metadata-unavailable")

    def test_sysbuild_auto_selection_and_environment_preflight(self):
        for image in ("app", "mcuboot"):
            child = self.build / image
            (child / "zephyr").mkdir(parents=True)
            (child / "zephyr/.config").write_text("CONFIG_GPIO=y\n")
        (self.build / "domains.yaml").write_text("default: app\ndomains:\n  - name: app\n    build_dir: app\n  - name: mcuboot\n    build_dir: mcuboot\n")
        status, report = self.invoke("studio-preflight", "--build", self.build, "--auto-images")
        self.assertEqual({row["image"] for row in report["images"]}, {"app", "mcuboot"})
        status, report = self.invoke("studio-preflight", "--build", self.build, "--auto-images", "--image", "app=app")
        self.assertEqual(status, 2)

    def test_diagnosis_answer_and_view_never_invent_positive_facts(self):
        source = self.root / "prj.conf"
        source.write_text("CONFIG_ADC=y\n")
        report_file = self.root / "report.json"
        report_file.write_text(json.dumps({"findings": [{"rule_id": "CS-KCONFIG-UNKNOWN",
            "image": "app", "symbol": "CONFIG_ADC", "message": "Missing evaluator", "confidence": "unknown"}]}))
        status, report = self.invoke("studio-diagnose", "--input", report_file)
        self.assertEqual(status, 0)
        self.assertEqual(report["findings"][0]["confidence"], "unknown")
        report_file.write_text(json.dumps(report))
        status, response = self.invoke("studio-answer", "--input", report_file,
                                      "--finding", report["findings"][0]["id"], "--evidence-root", self.root)
        self.assertEqual(status, 1)
        self.assertEqual(response["status"], "refused")
        status, response = self.invoke("studio-view", "--input", report_file)
        self.assertEqual(status, 0)
        self.assertIn("Recorded configuration evidence", response["content"])
        self.assertEqual(response["freshness"], "unverified")

    def test_installed_resource_closure_is_exact_and_isolated_entrypoint_runs(self):
        SYNC.sync(check=True)
        result = subprocess.run([sys.executable, "-I", "-B", str(ROOT / "plugins/io.github.niwantha33.zew.ui/scripts/studio.py"),
                                 "studio-images", "--build", str(self.build)], cwd=self.root,
                                capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(json.loads(result.stdout)["status"], "metadata-unavailable")

    def test_malformed_report_returns_typed_error(self):
        report = self.root / "malformed.json"
        report.write_text('{"findings": [{"rule_id":"VALID-ID","severity": []}]}')
        status, response = self.invoke("studio-diagnose", "--input", report)
        self.assertEqual(status, 2)
        self.assertEqual(response["status"], "error")


if __name__ == "__main__":
    unittest.main()
