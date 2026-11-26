# SPDX-License-Identifier: Apache-2.0
"""Offline catalogue test using the same Java parser used by the wizard."""
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
UI = ROOT / "plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui"


class SetupReleaseSelectionTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK not installed")
    def test_official_catalogue_filters_and_sorts(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = pathlib.Path(tmp)
            fixture = tmp / "CatalogFixture.java"
            fixture.write_text(r'''
import io.github.niwantha33.zew.ui.SetupReleaseCatalog;
import java.util.List;
public class CatalogFixture {
    static String item(String tag, boolean draft, boolean rc, String url) {
        return "{\"tag_name\":\"" + tag + "\",\"draft\":" + draft +
            ",\"prerelease\":" + rc + ",\"published_at\":\"2026-01-01\"," +
            "\"html_url\":\"" + url + "\"}";
    }
    static String official(String tag) { return "https://github.com/zephyrproject-rtos/zephyr/releases/tag/" + tag; }
    public static void main(String[] args) {
        String json = "[" + item("v4.4.0", false, false, official("v4.4.0")) + "," +
            item("v4.10.0", false, false, official("v4.10.0")) + "," +
            item("v3.7.0", false, false, official("v3.7.0")) + "," +
            item("v5.0.0", true, false, official("v5.0.0")) + "," +
            item("v5.0.0-rc1", false, true, official("v5.0.0-rc1")) + "," +
            item("v6.0.0", false, false, "https://invalid.example/v6.0.0") + "]";
        if (!SetupReleaseCatalog.parse(json).equals(List.of("v4.10.0", "v4.4.0", "v3.7.0")))
            throw new AssertionError("Wrong catalogue filtering/order");
        if (!SetupReleaseCatalog.label("v3.7.0").contains("source preparation only"))
            throw new AssertionError("Unqualified release presented as build-ready");
        try { SetupReleaseCatalog.parse("{}"); throw new AssertionError("Malformed response accepted"); }
        catch (IllegalArgumentException expected) {}
    }
}
''', encoding="utf-8")
            subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(tmp),
                            str(UI / "MemoryProfilerJson.java"), str(UI / "SetupReleaseCatalog.java"),
                            str(fixture)], check=True, timeout=30)
            subprocess.run(["java", "-cp", str(tmp), "CatalogFixture"], check=True, timeout=15)


if __name__ == "__main__":
    unittest.main()
