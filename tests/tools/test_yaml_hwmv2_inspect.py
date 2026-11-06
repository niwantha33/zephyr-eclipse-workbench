# SPDX-License-Identifier: Apache-2.0
"""Step 8: verify selected-checkout HWMv2 adapter and bounded source identity.

Fixtures implement only the Zephyr v4.4 list_boards/list_hardware API contract.
They are *not* substitutes for validation on a real pinned Zephyr checkout.
"""

import base64
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[2] / "plugins/io.github.niwantha33.zew.ui/scripts"
WRAPPER = SCRIPTS / "yaml_hwmv2_inspect.py"

HARDWARE_STUB = '''\
from pathlib import Path
from types import SimpleNamespace
import yaml

def find_v2_systems(args):
    items = []
    for root in args.soc_roots:
        for path in (Path(root) / "soc").rglob("soc.yml"):
            raw = yaml.safe_load(path.read_text(encoding="utf-8"))
            for soc in raw.get("socs", []):
                items.append(SimpleNamespace(name=soc["name"], folder=[str(path.parent)],
                    family="", series="",
                    cpuclusters=[c["name"] for c in soc.get("cpuclusters", [])]))
            for family in raw.get("family", []):
                for series in family.get("series", []):
                    for soc in series.get("socs", []):
                        items.append(SimpleNamespace(name=soc["name"], folder=[str(path.parent)],
                            family=family["name"], series=series["name"],
                            cpuclusters=[c["name"] for c in soc.get("cpuclusters", [])]))
    return SimpleNamespace(get_socs=lambda: items)
'''

BOARDS_STUB = '''\
from pathlib import Path
from types import SimpleNamespace
import yaml
import list_hardware

def find_v2_boards(args):
    socs = {s.name: s for s in list_hardware.find_v2_systems(args).get_socs()}
    result = {}
    for root in args.board_roots:
        for path in (Path(root) / "boards").rglob("board.yml"):
            raw = yaml.safe_load(path.read_text(encoding="utf-8"))
            for b in raw.get("boards", [raw.get("board")]):
                entries = [socs[s["name"]] for s in b.get("socs", [])]
                result[b["name"]] = SimpleNamespace(
                    hwm="v2", name=b["name"], directories=[str(path.parent)],
                    full_name=b.get("full_name"), vendor=b.get("vendor"),
                    revision_format=b.get("revision", {}).get("format"),
                    revision_default=b.get("revision", {}).get("default"),
                    revision_exact=b.get("revision", {}).get("exact", False),
                    revisions=[SimpleNamespace(name=r["name"])
                               for r in b.get("revision", {}).get("revisions", [])],
                    socs=entries)
    return result

def board_v2_qualifiers(board):
    result = []
    for soc in board.socs:
        if soc.cpuclusters:
            result.extend(soc.name + "/" + cluster for cluster in soc.cpuclusters)
        else:
            result.append(soc.name)
    return result
'''


def unpack(text):
    lines = text.strip().splitlines()
    assert lines and lines[0] == "ZEW-HWMV2-1", text[:300]
    return [tuple([row.split("\t")[0]] + [
        base64.b64decode(field).decode("utf-8") for field in row.split("\t")[1:]])
        for row in lines[1:]]


class Hwmv2InspectionTests(unittest.TestCase):
    def setUp(self):
        holder = tempfile.TemporaryDirectory()
        self.addCleanup(holder.cleanup)
        self.root = Path(holder.name)
        self.zephyr = self.root / "zephyr"
        self.application = self.root / "application"
        self.application.mkdir()
        (self.zephyr / "scripts").mkdir(parents=True)
        (self.zephyr / "scripts/list_hardware.py").write_text(HARDWARE_STUB, encoding="utf-8")
        (self.zephyr / "scripts/list_boards.py").write_text(BOARDS_STUB, encoding="utf-8")
        self.soc = self.zephyr / "soc" / "ti" / "test" / "soc.yml"
        self.soc.parent.mkdir(parents=True)
        self.soc.write_text(
            "family:\n"
            "  - name: testfamily\n"
            "    series:\n"
            "      - name: testseries\n"
            "        socs:\n"
            "          - name: mspm0test\n"
            "            cpuclusters:\n"
            "              - name: cpu0\n"
            "              - name: cpu1\n", encoding="utf-8")
        self.board = self.zephyr / "boards" / "ti" / "test_board" / "board.yml"
        self.board.parent.mkdir(parents=True)
        self.board.write_text(
            "board:\n"
            "  name: test_board\n"
            "  vendor: ti\n"
            "  full_name: Test LaunchPad\n"
            "  revision:\n"
            "    format: number\n"
            "    default: '1'\n"
            "    exact: true\n"
            "    revisions:\n"
            "      - name: '1'\n"
            "      - name: '2'\n"
            "  socs:\n"
            "    - name: mspm0test\n", encoding="utf-8")

    def run_inspector(self, relative="boards/ti/test_board/board.yml",
                      kind="zephyr", root=None, zephyr=None, application=None):
        return subprocess.run([sys.executable, str(WRAPPER),
                               "--root", str(root or self.zephyr),
                               "--relative", relative,
                               "--source-root", kind,
                               "--zephyr", str(zephyr or self.zephyr),
                               "--application", str(application or self.application)],
                              capture_output=True, text=True, check=False, timeout=18)

    def test_board_resolution_uses_selected_upstream_api(self):
        result = self.run_inspector()
        self.assertEqual(result.returncode, 0, result.stderr)
        rows = unpack(result.stdout)
        self.assertEqual(rows[0][1:3], ("resolved", "selected-upstream-hwmv2"))
        self.assertEqual(rows[0][4], "board")
        self.assertEqual(len(rows[0][3]), 64)
        self.assertEqual(len(rows[0][5]), 64)
        self.assertIn(("B", "test_board", "Test LaunchPad", "ti",
                       "number", "1", "yes"), rows)
        self.assertIn(("R", "test_board", "1"), rows)
        self.assertIn(("R", "test_board", "2"), rows)
        self.assertIn(("Q", "test_board", "mspm0test/cpu0"), rows)
        self.assertIn(("Q", "test_board", "mspm0test/cpu1"), rows)
        self.assertIn(("S", "mspm0test", "testfamily", "testseries", "cpu0,cpu1"), rows)
        self.assertEqual(self.board.read_text(encoding="utf-8").splitlines()[1],
                         "  name: test_board")

    def test_soc_family_series_cpuclusters_and_source_origin(self):
        rows = unpack(self.run_inspector("soc/ti/test/soc.yml").stdout)
        self.assertEqual(rows[0][4], "soc")
        self.assertIn(("S", "mspm0test", "testfamily", "testseries", "cpu0,cpu1"), rows)
        self.assertFalse(any(row[0] == "B" for row in rows))

    def test_multiple_boards_in_one_board_yml(self):
        self.board.write_text(
            "boards:\n"
            "  - name: test_board\n"
            "    vendor: ti\n"
            "    socs: [{name: mspm0test}]\n"
            "  - name: secondary\n"
            "    vendor: ti\n"
            "    socs: [{name: mspm0test}]\n", encoding="utf-8")
        rows = unpack(self.run_inspector().stdout)
        self.assertEqual([r[1] for r in rows if r[0] == "B"],
                         ["secondary", "test_board"])

    def test_application_owned_board_explicitly_supported(self):
        app_board = self.application / "boards" / "custom" / "mine" / "board.yml"
        app_board.parent.mkdir(parents=True)
        app_board.write_text(
            "board:\n  name: mine\n  vendor: custom\n"
            "  socs: [{name: mspm0test}]\n", encoding="utf-8")
        rows = unpack(self.run_inspector("boards/custom/mine/board.yml",
                                         kind="application", root=self.application).stdout)
        self.assertEqual(rows[0][1], "resolved")
        self.assertIn(("B", "mine", "", "custom", "", "", "no"), rows)

    def test_uncanonical_metadata_is_not_misclassified(self):
        generic = self.zephyr / "boards/ti/test_board/board.yaml"
        generic.write_text("board: {name: fake}\n", encoding="utf-8")
        rows = unpack(self.run_inspector("boards/ti/test_board/board.yaml").stdout)
        self.assertEqual(rows[0][1:3], ("not-hwmv2", "not-canonical-hwmv2-metadata"))
        self.assertEqual(len(rows), 1)

    def test_missing_selected_upstream_api_is_unavailable(self):
        (self.zephyr / "scripts/list_boards.py").unlink()
        rows = unpack(self.run_inspector().stdout)
        self.assertEqual(rows[0][1:3], ("unavailable", "selected-hwm-api-unavailable"))
        self.assertEqual(len(rows), 1)

    def test_unresolved_soc_does_not_fake_valid_board(self):
        self.soc.unlink()
        rows = unpack(self.run_inspector().stdout)
        self.assertEqual(rows[0][1], "unavailable")
        self.assertEqual(len(rows), 1)

    def test_source_root_mismatch_or_path_traversal(self):
        for options in ({"root": self.application},
                        {"relative": "../private/board.yml"},
                        {"relative": "/etc/passwd"}):
            with self.subTest(options=options):
                rows = unpack(self.run_inspector(**options).stdout)
                self.assertEqual(rows[0][1], "unavailable")
                self.assertEqual(len(rows), 1)

    def test_duplicate_yaml_keys_rejected_before_upstream(self):
        self.board.write_text("board: {name: first}\nboard: {name: second}\n",
                              encoding="utf-8")
        rows = unpack(self.run_inspector().stdout)
        self.assertEqual(rows[0][1:3], ("unavailable", "unsafe-or-invalid-yaml-source"))
        self.assertEqual(len(rows), 1)

    def test_symlinked_selected_source_rejected_if_supported(self):
        original = self.board.parent / "original.yml"
        self.board.rename(original)
        try:
            self.board.symlink_to(original)
        except (OSError, NotImplementedError):
            self.skipTest("Symlink creation unavailable")
        rows = unpack(self.run_inspector().stdout)
        self.assertEqual(rows[0][1], "unavailable")

    def test_upstream_errors_never_leak_paths_or_private_values(self):
        (self.zephyr / "scripts/list_boards.py").write_text(
            "raise RuntimeError('SECRET from upstream')\n", encoding="utf-8")
        result = self.run_inspector()
        rows = unpack(result.stdout)
        self.assertEqual(rows[0][1], "unavailable")
        self.assertNotIn("SECRET", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
