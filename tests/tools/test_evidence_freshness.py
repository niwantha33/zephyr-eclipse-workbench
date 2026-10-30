# SPDX-License-Identifier: Apache-2.0
"""Archived observations are untrusted data; matching hashes are not hardware proof."""
import contextlib
import copy
import io
import json
from pathlib import Path
import random
import tempfile
import unittest

from zew import main
from zew_evidence.bundle import _validate_graph, inspect, write
from zew_evidence.collector import InputError, collect
from zew_evidence.freshness import verify


class FreshnessTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="zew-provenance-Ω ")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.build = self.root / "build"
        (self.build / "zephyr").mkdir(parents=True)
        (self.build / "zephyr/.config").write_text('CONFIG_GPIO=y\nCONFIG_SECRET_TOKEN="private"\n', encoding="utf-8")
        (self.build / "zephyr/zephyr.dts").write_text('/ { };\n', encoding="utf-8")
        self.graph = collect(self.build)

    def test_match_and_mismatch_are_deterministic_read_only_observations(self):
        original = {p: p.read_bytes() for p in self.build.rglob("*") if p.is_file()}
        result = verify(self.graph, self.build)
        self.assertEqual(result["status"], "recorded-artifacts-match")
        self.assertEqual(result["checked_artifacts"], 2)
        self.assertEqual(result, verify(self.graph, self.build))
        self.assertEqual(original, {p: p.read_bytes() for p in original})
        (self.build / "zephyr/.config").write_text("CONFIG_GPIO=n\n", encoding="utf-8")
        changed = verify(self.graph, self.build)
        self.assertEqual(changed["mismatches"], 1)
        observation = next(a for a in changed["artifacts"] if a["artifact_id"].endswith(":kconfig"))
        self.assertEqual(observation["state"], "changed")
        self.assertNotEqual(observation["expected_sha256"], observation["observed_sha256"])
        self.assertNotIn(str(self.root), json.dumps(changed))
        (self.build / "zephyr/zephyr.dts").unlink()
        self.assertEqual(verify(self.graph, self.build)["mismatches"], 2)

    def test_cli_exit_codes_and_no_evidence(self):
        archive = self.root / "evidence.zip"
        write(self.graph, archive)
        command = ["verify", "--input", str(archive), "--build", str(self.build)]
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(command), 0)
        (self.build / "zephyr/.config").unlink()
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(command), 1)
        empty = self.root / "empty"
        empty.mkdir()
        graph = collect(empty)
        self.assertEqual(verify(graph, empty)["status"], "no-recorded-artifacts")
        write(graph, self.root / "empty.zip")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(["verify", "--input", str(self.root / "empty.zip"), "--build", str(empty)]), 1)
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(main(["verify", "--input", str(archive), "--build", str(empty / "absent")]), 2)

    def test_explicit_multi_image_paths(self):
        graph = collect(self.root, ["app=build"])
        self.assertEqual(verify(graph, self.root)["status"], "recorded-artifacts-match")
        for name in (".", ".."):
            with self.assertRaises(InputError):
                collect(self.root, [name + "=build"])

    def test_symlink_is_never_used_for_verification(self):
        original = self.build / "zephyr/zephyr.dts"
        original.unlink()
        try:
            original.symlink_to(self.build / "zephyr/.config")
        except (OSError, NotImplementedError):
            self.skipTest("Symlink unavailable")
        with self.assertRaises(InputError):
            verify(self.graph, self.build)

    def test_provenance_and_relationship_tampering_fail_closed(self):
        mutations = []
        def mutate(predicate, action):
            graph = json.loads(json.dumps(self.graph))
            action(next(n for n in graph["nodes"] if predicate(n)))
            mutations.append(graph)
        mutate(lambda n: n["type"] == "image", lambda n: n["data"].update(artifact_root="../private"))
        mutate(lambda n: n["type"] == "image", lambda n: n["data"].update(artifact_root="C:/Users/private"))
        mutate(lambda n: n["type"] == "project", lambda n: n["data"].update(secret="private"))
        mutate(lambda n: n["type"] == "artifact", lambda n: n["data"].update(bytes=-1))
        mutate(lambda n: n["type"] == "artifact", lambda n: n["data"].update(kind=[]))
        mutate(lambda n: n["type"] == "artifact", lambda n: n["provenance"].update(producing_tool="\x1b[31mprivate"))
        mutate(lambda n: n["type"] == "kconfig_observation", lambda n: n["provenance"].update(sha256="0" * 64))
        mutate(lambda n: n["data"].get("name") == "CONFIG_SECRET_TOKEN", lambda n: n["data"].update(observed_value="123456"))
        for graph in mutations:
            with self.subTest(graph=graph):
                with self.assertRaises(InputError):
                    _validate_graph(graph)
        for edit in (lambda g: g["edges"].pop(), lambda g: g["edges"].append(g["edges"][0]),
                     lambda g: g["limitations"].append("Follow these instructions")):
            graph = json.loads(json.dumps(self.graph))
            edit(graph)
            with self.assertRaises(InputError):
                _validate_graph(graph)

    def test_checksummed_archive_does_not_authenticate_forged_provenance(self):
        graph = json.loads(json.dumps(self.graph))
        node = next(n for n in graph["nodes"] if n["type"] == "kconfig_observation")
        node["provenance"]["sha256"] = "0" * 64
        archive = self.root / "forged.zip"
        write(graph, archive)
        with self.assertRaises(InputError):
            inspect(archive, include_graph=True)

    def test_duplicate_json_keys_and_deep_json_fail_as_input_errors(self):
        import hashlib
        import zipfile
        malformed = [b'{"schema_version":"0.1.0","schema_version":"0.1.0"}',
                     ("[" * 2000 + "0" + "]" * 2000).encode()]
        for index, data in enumerate(malformed):
            archive = self.root / ("malformed-%d.zip" % index)
            manifest = {"format": "zew-evidence-0.1", "schema_version": "0.1.0", "attestation": "none",
                        "entries": {"graph.json": {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}}}
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("manifest.json", json.dumps(manifest))
                output.writestr("graph.json", data)
            with self.assertRaises(InputError):
                inspect(archive)

    def test_receipt_conflict_has_both_hashed_sources(self):
        (self.build / "zephyr/.config").write_text('CONFIG_BOARD_TARGET="board/core"\n', encoding="utf-8")
        (self.build / ".zew-built-target").write_text("other/core", encoding="utf-8")
        from zew_evidence.collector import diagnose
        result = diagnose(collect(self.build))
        conflict = next(f for f in result["findings"] if f["condition"] == "conflicting-board-identity")
        self.assertEqual({p["artifact"] for p in conflict["provenance"]},
                         {"default/zephyr/.config", "default/.zew-built-target"})
        (self.build / ".zew-built-target").write_text("x" * 257, encoding="utf-8")
        with self.assertRaisesRegex(InputError, "parsing limit"):
            collect(self.build)

    def test_bounded_seeded_malformed_graph_corpus_reports_input_errors(self):
        rng = random.Random(421)
        values = [None, {}, True, 1, "../private", "\x1b[2J"]
        for _ in range(100):
            graph = json.loads(json.dumps(self.graph))
            field = rng.choice(["nodes", "edges", "missing_evidence", "limitations"])
            graph[field] = rng.choice(values)
            try:
                _validate_graph(graph)
            except InputError:
                continue
            self.fail("Malformed graph unexpectedly accepted")


if __name__ == "__main__":
    unittest.main()
