#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""CI-only REAL Zephyr 4.4 builds of optional vendor-neutral framed CTF RAM.

MSPM0G3507 is first. STM32 and Espressif are compile-only regressions; no
physical boards are required or touched. No flash, probe, OpenOCD or Live UI.
The checkout is a disposable, verified pinned managed CI workspace.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from windows_firmware_matrix import run, select_target

REPO = Path(__file__).resolve().parents[1]
PINS = "684c9e8f32e4373a21098559f748f06915f950c9"
TARGETS = (
    ("mspm0", "lp_mspm0g3507", "mspm0g3507", None),
    ("stm32", "nucleo_f446re", "stm32f446xx", None),
    ("esp32c3", "esp32c3_devkitm", "esp32c3", None),
)


def build_all(root: Path, report_path: Path) -> None:
    if os.name != "nt":
        raise ValueError("Real managed Zephyr trace matrix requires Windows CI")
    root = root.resolve(strict=True)
    ready = root / ".zew-ready"
    workspace = root / "workspace"
    zephyr = workspace / "zephyr"
    sdk = root / "sdk"
    python = root / "python/python.exe"
    ninja = root / "python/Scripts/ninja.exe"
    git = root / "git/cmd/git.exe"
    for item in (ready, zephyr / "CMakeLists.txt", python, ninja, git):
        if not item.is_file():
            raise ValueError("Missing exact fresh managed Windows prerequisite: " + str(item))
    source_repo = REPO / "examples/trace_framed_probe"
    if not source_repo.is_dir():
        raise ValueError("Bundled portable firmware probe missing")
    metadata_file = zephyr / "subsys/tracing/ctf/tsdl/metadata"
    if not metadata_file.is_file() or not 0 < metadata_file.stat().st_size <= 65536:
        raise ValueError("Exact Zephyr 4.4 packed CTF metadata not found")
    metadata_sha = hashlib.sha256(metadata_file.read_bytes()).hexdigest()
    git_head = subprocess.check_output(
        [str(git), "-C", str(zephyr), "rev-parse", "HEAD"],
        text=True, timeout=15).strip()
    if git_head != PINS:
        raise ValueError("Refusing to modify a non-pinned Zephyr checkout")
    patcher = REPO / "firmware/trace/zephyr44/apply_pinned_backend.py"
    # This is *only* allowed inside the disposable build matrix job; checked
    # patcher rejects a dirty tree, changed revision and unsafe anchors.
    subprocess.run([sys.executable, str(patcher), "--zephyr-base",
                    str(zephyr), "--apply"], check=True, timeout=30)
    env = dict(os.environ, PYTHONUTF8="1", PYTHONIOENCODING="utf-8",
               PYTHONUNBUFFERED="1", GIT_TERMINAL_PROMPT="0")
    for name in ("PYTHONHOME", "PYTHONPATH", "BOARD", "CONF_FILE",
                 "ZEPHYR_BASE", "ZEPHYR_SDK_INSTALL_DIR", "ZEPHYR_TOOLCHAIN_VARIANT"):
        env.pop(name, None)
    env["PATH"] = os.pathsep.join(map(str, [
        root / "python", root / "python/Scripts", root / "git/cmd",
        root / "board-python/esp32s3/Scripts", root / "board-python/esp32s3/bin",
        sdk / "hosttools/bin", sdk / "hosttools/usr/bin",
    ])) + os.pathsep + env.get("PATH", "")
    env.update(ZEPHYR_BASE=zephyr.as_posix(),
               ZEPHYR_TOOLCHAIN_VARIANT="zephyr",
               ZEPHYR_SDK_INSTALL_DIR=sdk.as_posix())
    catalog = run([python, root / "installer/hardware_catalog.py",
                   "--zephyr", zephyr], root, env, capture=True)
    rows = [line.split("\t") for line in catalog.splitlines()
            if len(line.split("\t")) == 7]
    if not rows:
        raise ValueError("Managed Zephyr board catalog did not return evidence")
    output = []
    for label, board_name, soc, core in TARGETS:
        board = select_target(rows, board_name, soc, core)
        app = root / "trace-ci-projects" / label
        if app.exists():
            raise ValueError("Trace CI build must start from a fresh source dir")
        shutil.copytree(source_repo, app)
        contract_sha = hashlib.sha256(
            b"ZEW-TEST-ONLY-PREBUILD-CONTENT\0"
            + PINS.encode() + board.encode()
            + (app / "src/main.c").read_bytes() + metadata_file.read_bytes()
        ).hexdigest()
        with (app / "prj.conf").open("a", encoding="utf-8") as out:
            out.write("\n# CI-only vendor-neutral bounded RAM backend\n"
                      "CONFIG_TRACING_BACKEND_ZEW_RAM=y\n"
                      "CONFIG_ZEW_TRACE_RAM_BYTES=4096\n"
                      f'CONFIG_ZEW_TRACE_IMAGE_CONTRACT_SHA256="{contract_sha}"\n'
                      f'CONFIG_ZEW_TRACE_METADATA_SHA256="{metadata_sha}"\n')
        build = app / "build"
        run([python, root / "installer/board_dependencies.py",
             "--root", root, "--sdk", sdk, "--workspace", workspace,
             "--git", git, "--board", board, "--application", app],
            workspace, env)
        run([python, "-m", "west", "build", "--cmake-only",
             "-b", board, "-d", build, app, "--",
             "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
             "-DCMAKE_MAKE_PROGRAM:FILEPATH=" + ninja.as_posix(),
             "-DPython3_EXECUTABLE:FILEPATH=" + python.as_posix()],
            workspace, env)
        run([python, "-m", "west", "build", "-d", build], workspace, env)
        java = Path(os.environ["JAVA_HOME"]) / "bin/java.exe"
        observed = run([java, "-cp", REPO / "build-core-tests",
                        "TraceFirmwareRealBuildProbe", app, build, board],
                       workspace, env, capture=True)
        if "ZEW_TRACE_FIRMWARE_BUILD_1" not in observed or \
                "CONFIG_TRACE_MATCH=YES" not in observed or \
                "BUILD_RECEIPT=CURRENT" not in observed or \
                "LIVE_TARGET_VERIFIED=NO" not in observed:
            raise ValueError("Real Zephyr CTF evidence gate did not pass for " + label)
        elf = build / "zephyr/zephyr.elf"
        output.append({
            "board": board,
            "family": label,
            "zephyr_commit": git_head,
            "transport": "BOUNDED_FRAMED_RAM",
            "generated_config": "MATCH",
            "real_elf_sha256": hashlib.sha256(elf.read_bytes()).hexdigest(),
            "prebuild_contract_sha256": contract_sha,
            "metadata_sha256": metadata_sha,
            "receipt": "CURRENT",
            "build": "PASS",
            "physical_board_test": "PENDING",
            "rtt_probe_test": "NOT_APPLICABLE",
            "live_ui_enabled": False,
        })
        print("TRACE_REAL_BUILD_PASS:", label, board, flush=True)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps({
        "schema": "zew-trace-real-build-matrix-v1",
        "pinned_zephyr": PINS,
        "target_count": len(output),
        "hardware_verified": False,
        "live_trace_enabled": False,
        "targets": output,
    }, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--output", default="trace-firmware-matrix.json", type=Path)
    args = parser.parse_args()
    build_all(args.root, args.output)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as failure:
        print("TRACE MATRIX FAILED:", type(failure).__name__, str(failure)[:1800],
              file=sys.stderr)
        sys.exit(2)
