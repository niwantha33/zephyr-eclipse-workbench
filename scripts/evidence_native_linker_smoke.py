#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Native Linux smoke using *actual* GNU and LLVM link outputs.

This is a host-toolchain format-compatibility test, not Zephyr firmware,
embedded architecture correctness or a physical-device test.
"""
from __future__ import annotations

import contextlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

from zew_evidence.collector import collect
from zew_evidence.compilation import inspect as inspect_compile
from zew_evidence.link_audit import audit as audit_ranges
from zew_evidence.section_audit import audit as audit_sections
from zew_evidence.map_context import audit as audit_context
from zew import main as zew_main


def _run(args, *, cwd):
    subprocess.run([str(arg) for arg in args], cwd=cwd, stdin=subprocess.DEVNULL,
                   check=True, capture_output=True, timeout=90)


def _build(root: Path, compiler: str, linker_flavour: str) -> dict:
    app = root / "source"
    app.mkdir(parents=True)
    source = app / "src"
    source.mkdir()
    main = source / "main.c"
    main.write_text("extern int zew_double(int); int main(void) { return zew_double(3)!=6; }\n")
    helper = source / "helper.c"
    helper.write_text("int zew_double(int x) { return 2*x; }\n")
    build = root / "build"
    (build / "zephyr").mkdir(parents=True)
    main_obj = build / "main.o"
    helper_obj = build / "helper.o"
    archive = build / "libzew.a"
    mapfile = build / "zephyr/zephyr.map"
    elf = build / "zephyr/zephyr.elf"
    _run([compiler, "-g", "-c", "-o", main_obj, main], cwd=build)
    _run([compiler, "-g", "-c", "-o", helper_obj, helper], cwd=build)
    _run(["ar", "rcs", archive, helper_obj], cwd=build)
    options = [compiler, main_obj, archive, "-o", elf, "-Wl,-Map," + str(mapfile)]
    if linker_flavour == "llvm-lld":
        options.append("-fuse-ld=lld")
    _run(options, cwd=build)
    (build / "compile_commands.json").write_text(
        json.dumps([{"directory": str(build), "file": str(main),
                     "output": "main.o", "command": "<intentional-placeholder-not-executed>"}]))
    # Real compilation/link occurred above. The compile database is a
    # controlled identity fixture, not falsely attributed to generated CMake.
    graph = collect(build)
    compiled = inspect_compile(build, "src/main.c", app, graph=graph)["images"][0]
    ranges = audit_ranges(build, graph=graph)["images"][0]
    sections = audit_sections(build, graph=graph)["images"][0]
    context = audit_context(build, graph=graph)["images"][0]
    if compiled["status"] != "compile-command-observed":
        raise AssertionError("Exact native source identity not observed")
    if ranges["status"] in ("missing-elf", "unsupported-or-invalid-elf"):
        raise AssertionError("Real compiled ELF unexpectedly unreadable")
    if sections["status"] in ("missing-elf", "unsupported-or-invalid-elf-sections"):
        raise AssertionError("Real ELF section table unexpectedly unreadable")
    if linker_flavour == "gnu-ld" and ranges["status"] == "unsupported-map-format":
        raise AssertionError("Real GNU ld map not recognised")
    if linker_flavour == "gnu-ld" and sections["status"] == "unsupported-map-format":
        raise AssertionError("Real GNU ld output map not recognised")
    if context["status"] == "unsupported-or-invalid-elf-sections":
        raise AssertionError("Real ELF section metadata unexpectedly invalid")
    if linker_flavour == "gnu-ld" and context["status"] in (
            "unsupported-map-format", "no-supported-gnu-output-or-input-records"):
        raise AssertionError("Genuine GNU ld map did not expose supported input/output context")
    # Execute the zipapp-equivalent CLI contract for native linker evidence.
    stream = io.StringIO()
    with contextlib.redirect_stdout(stream):
        code = zew_main(["section-audit", "--build", str(build)])
    if code != 0 or json.loads(stream.getvalue())["images"][0]["status"] != sections["status"]:
        raise AssertionError("Native CLI disagrees with headless section analyzer")
    report = {"toolchain": linker_flavour,
              "map_context_status": context["status"],
              "map_context": context["context"],
              "compilation": compiled["status"],
              "range_status": ranges["status"],
              "map_sections": ranges["map_sections"],
              "section_status": sections["status"],
              "map_output_sections": sections["map_output_sections"],
              "exact_output_layouts": (sections["comparison"] or {}).get("exact-layout", 0),
              "archive_refs": (ranges["archive"] or {}).get("referenced_unique_members", 0),
              "scope": "Host GNU/LLVM executable, not embedded Zephyr firmware or flashed hardware"}
    if str(root) in json.dumps(report):
        raise AssertionError("Host path in test report")
    return report


def main():
    for program in ("gcc", "clang", "ld.lld", "ar"):
        if not shutil.which(program):
            raise SystemExit("Required native compiler/linker missing: " + program)
    with tempfile.TemporaryDirectory(prefix="zew-real-toolchain-Ω ") as temp:
        root = Path(temp)
        results = [_build(root / "gnu", "gcc", "gnu-ld"),
                   _build(root / "llvm", "clang", "llvm-lld")]
        print(json.dumps({"status": "host-native-toolchain-observations",
                          "results": results}, sort_keys=True))


if __name__ == "__main__":
    main()
