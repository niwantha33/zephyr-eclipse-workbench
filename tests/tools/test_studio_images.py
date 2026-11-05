# SPDX-License-Identifier: Apache-2.0
import json
from pathlib import Path
import tempfile
import unittest
from zew_evidence.collector import InputError
from zew_evidence.studio_images import inspect, declarations, collect_images


class ImageDiscoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="sysbuild Ω ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for image, value in (("app", "y"), ("boot", "n")):
            directory = self.root / image / "zephyr"
            directory.mkdir(parents=True)
            (directory / ".config").write_text("CONFIG_GPIO=" + value + "\n")
        self.data = {"default": "app", "build_dir": str(self.root),
                     "domains": [{"name": name, "build_dir": str(self.root / name)} for name in ("boot", "app")],
                     "flash_order": ["boot", "app"]}
        self.write()

    def write(self):
        # JSON is an official YAML subset consumed by the safe YAML loader.
        (self.root / "domains.yaml").write_text(json.dumps(self.data))

    def test_upstream_topology_and_image_symbols_remain_separate(self):
        before = (self.root / "domains.yaml").read_bytes()
        report = inspect(self.root)
        self.assertEqual(declarations(report), ["app=app", "boot=boot"])
        self.assertEqual(report["default_image"], "app")
        graph = collect_images(self.root)["graph"]
        observations = {n["id"]: n["data"]["observed_value"] for n in graph["nodes"] if n["type"] == "kconfig_observation"}
        self.assertEqual(observations["kconfig:app:CONFIG_GPIO"], "y")
        self.assertEqual(observations["kconfig:boot:CONFIG_GPIO"], "n")
        self.assertEqual(before, (self.root / "domains.yaml").read_bytes())
        self.assertEqual(report, inspect(self.root))

    def test_no_metadata_never_infers_images_from_folders(self):
        (self.root / "domains.yaml").unlink()
        self.assertEqual(inspect(self.root)["images"], [])
        self.assertIsNone(collect_images(self.root)["graph"])

    def test_partial_image_reported_without_claiming_built(self):
        report = inspect(self.root)
        self.assertEqual(report["images"][0]["state"], "partial-or-unconfigured")
        self.assertFalse(report["images"][0]["observed_artifacts"]["elf"])

    def test_duplicate_names_locations_and_unknown_default_rejected(self):
        for change in (lambda d: d["domains"][1].update(name="boot"),
                       lambda d: d["domains"][1].update(build_dir=str(self.root / "boot")),
                       lambda d: d.update(default="absent")):
            original = json.loads(json.dumps(self.data))
            change(self.data)
            self.write()
            with self.assertRaises(InputError):
                inspect(self.root)
            self.data = original

    def test_external_parent_and_symlink_images_rejected(self):
        self.data["domains"][0]["build_dir"] = "../escape"
        self.write()
        with self.assertRaises(InputError):
            inspect(self.root)
        self.data["domains"][0]["build_dir"] = str(self.root.parent)
        self.write()
        with self.assertRaises(InputError):
            inspect(self.root)
        link = self.root / "linked"
        try:
            link.symlink_to(self.root / "boot", target_is_directory=True)
        except (OSError, NotImplementedError):
            return
        self.data["domains"][0]["build_dir"] = str(link)
        self.write()
        with self.assertRaises(InputError):
            inspect(self.root)

    def test_yaml_aliases_duplicate_keys_and_unsafe_tags_rejected(self):
        for text in ("default: app\ndefault: boot\ndomains: []\n",
                     "a: &x [*x]\ndefault: app\ndomains: []\n",
                     "!!python/object/apply:os.system ['echo no']"):
            (self.root / "domains.yaml").write_text(text)
            with self.assertRaises(InputError):
                inspect(self.root)
