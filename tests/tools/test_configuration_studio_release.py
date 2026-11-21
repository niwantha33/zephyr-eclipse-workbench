# SPDX-License-Identifier: Apache-2.0
"""CS-26: release contract for the installed visual Configuration Studio.

These checks never replace actual Tycho, Windows, Eclipse Browser or physical
hardware qualification; they guard against accidentally omitting those gates.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ConfigurationStudioReleaseContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
        cls.visual = (ROOT / "plugins/io.github.niwantha33.zew.ui/configuration-studio/index.html").read_text(encoding="utf-8")
        cls.home = (ROOT / "plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/ConfigurationHomeView.java").read_text(encoding="utf-8")
        cls.writer = (ROOT / "plugins/io.github.niwantha33.zew.core/src/io/github/niwantha33/zew/core/ConfigurationStudioFiles.java").read_text(encoding="utf-8")
        cls.site = (ROOT / "scripts/verify_site.py").read_text(encoding="utf-8")

    def test_pinned_tycho_retry_is_bounded_and_fail_closed(self):
        self.assertIn("scripts/retry_maven_tycho.py --qualifier", self.workflow)
        retry = (ROOT / "scripts/retry_maven_tycho.py").read_text(encoding="utf-8")
        self.assertIn("MAX_ATTEMPTS = 3", retry)
        self.assertIn('"clean", "verify"', retry)
        self.assertIn("if not missing:", retry)
        self.assertIn("return code", retry)
        self.assertIn('"-U"', retry)
        self.assertIn("python3 scripts/verify_site.py", self.workflow)

    def test_both_screen_sizes_exercise_installed_eclipse(self):
        self.assertIn("-screen 0 1920x1080x24", self.workflow)
        self.assertIn("-screen 0 1366x768x24", self.workflow)
        self.assertIn("scripts/editor_smoke.py --native-workbench", self.workflow)
        self.assertIn("zephyr-native-ui-evidence", self.workflow)
        self.assertIn("ConfigurationStudioVisualSmoke", (ROOT / "tests/eclipse/WorkbenchSmoke.java").read_text(encoding="utf-8"))

    def test_configuration_mutation_is_not_a_browser_write(self):
        for unsafe in ("innerHTML", "document.write", "fetch(", "XMLHttpRequest"):
            self.assertNotIn(unsafe, self.visual)
        self.assertIn("host('request',payload)", self.visual)
        self.assertIn('case "request" -> value.length() <= 180 && visualRequestAllowed(value)', self.home)
        self.assertIn("ConfigurationStudioFiles.plan(", (ROOT / "plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/ConfigurationStudioView.java").read_text(encoding="utf-8"))
        self.assertIn("ConfigurationStudioFiles.commit(", (ROOT / "plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/ConfigurationStudioView.java").read_text(encoding="utf-8"))
        self.assertIn("ATOMIC_MOVE", self.writer)
        self.assertIn("config-backups", self.writer)

    def test_exact_provenance_and_explicit_unknown_evidence(self):
        self.assertIn("ConfigurationEvidenceReceipt.inspect(", self.home)
        self.assertIn("KconfigInspector.inspect(", self.home)
        self.assertIn("case \"inspect-kconfig\", \"focus-symbol\"", self.home)
        self.assertIn('case "hardware-focus", "source-hardware" -> verifiedVisualController(value)', self.home)
        self.assertIn("ConfigurationStudioVisualDocument.class", self.site)
        self.assertIn("ConfigurationStudioVisualRequest.class", self.site)
        self.assertIn("UNVERIFIED_SNAPSHOT", (ROOT / "plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/ConfigurationStudioVisualDocument.java").read_text(encoding="utf-8"))

    def test_evidence_gate_is_not_permissive(self):
        self.assertIn('PACKAGE_RESULT: ${{ needs.package.result }}', self.workflow)
        self.assertIn('WINDOWS_RESULT: ${{ needs.windows-firmware.result }}', self.workflow)
        self.assertIn("exit 1", self.workflow)
        self.assertIn("no device or production approval is implied", self.workflow)


if __name__ == "__main__":
    unittest.main()
