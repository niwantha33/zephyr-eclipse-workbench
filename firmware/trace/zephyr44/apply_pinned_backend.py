#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Explicitly patch ONLY a pristine pinned Zephyr 4.4.0 for ZEW RTT prototype.

Default mode checks all source anchors without writing. --apply deliberately
changes the user's Zephyr checkout; never called by the Workbench or installer.
Do not run against arbitrary Zephyr releases, modified trees or symlink files.
"""
from __future__ import annotations

import argparse
import pathlib
import shutil
import subprocess
import sys

PINNED_ZEPHYR = "684c9e8f32e4373a21098559f748f06915f950c9"
HERE = pathlib.Path(__file__).resolve().parent

KCONFIG_CHOICE = """
config TRACING_BACKEND_ZEW_RTT
	bool "ZEW prototype: CTF over dedicated nonblocking SEGGER RTT"
	depends on TRACING_CTF && TRACING_ASYNC && USE_SEGGER_RTT && !SMP
	help
	  Explicit experimental pinned-Zephyr CTF binary backend. This
	  requires separately verified probe and boot/image evidence.
	  Not an approved live stream and not a generic SoC feature.
"""

KCONFIG_SETTINGS = """
if TRACING_BACKEND_ZEW_RTT

config ZEW_TRACE_RTT_UP_BYTES
	int "Dedicated RTT up-buffer bytes (prototype)"
	range 1024 16384
	default 8192

config ZEW_TRACE_IMAGE_CONTRACT_SHA256
	string "Prebuild image-contract SHA256 hex (NOT ELF attestation)"
	default ""
	help
	  Exactly 64 lowercase hex characters. This is a prebuild contract,
	  NOT a hash of the finished ELF, and cannot independently attest
	  what is flashed onto a target.

config ZEW_TRACE_METADATA_SHA256
	string "Exact packed CTF metadata SHA256 hex"
	default ""
	help
	  Exactly 64 lowercase hex characters. Independent host matching
	  is mandatory; the firmware-supplied value is not authentication.

endif # TRACING_BACKEND_ZEW_RTT
"""


def git(zephyr: pathlib.Path, *args: str) -> str:
    return subprocess.check_output(
        ["git", "-C", str(zephyr), *args],
        stderr=subprocess.STDOUT, text=True, timeout=15,
    ).strip()


def unique_replace(source: str, original: str, replacement: str, name: str) -> str:
    if source.count(original) != 1:
        raise ValueError(f"Unsafe pinned Zephyr source anchor for {name}: "
                         f"expected once, found {source.count(original)}")
    return source.replace(original, replacement, 1)


def propose(root: pathlib.Path) -> dict[pathlib.Path, bytes]:
    tracing = root / "subsys" / "tracing"
    paths = {
        "kconfig": tracing / "Kconfig",
        "cmake": tracing / "CMakeLists.txt",
        "core": tracing / "tracing_core.c",
        "header": tracing / "include" / "tracing_core.h",
    }
    original = {}
    for name, path in paths.items():
        if not path.is_file() or path.is_symlink():
            raise ValueError(f"Missing/symlink pinned Zephyr source: {name}")
        original[name] = path.read_text(encoding="utf-8")

    kconfig = unique_replace(
        original["kconfig"],
        'config TRACING_BACKEND_RAM\n',
        KCONFIG_CHOICE + '\nconfig TRACING_BACKEND_RAM\n',
        "Kconfig backend choice",
    )
    kconfig = unique_replace(
        kconfig,
        '\tdefault "tracing_backend_ram" if TRACING_BACKEND_RAM\n',
        '\tdefault "tracing_backend_ram" if TRACING_BACKEND_RAM\n'
        '\tdefault "tracing_backend_zew_rtt" if TRACING_BACKEND_ZEW_RTT\n',
        "Kconfig backend name",
    )
    kconfig = unique_replace(
        kconfig,
        '\nconfig RAM_TRACING_BUFFER_SIZE\n',
        '\n' + KCONFIG_SETTINGS + '\nconfig RAM_TRACING_BUFFER_SIZE\n',
        "Kconfig opt-in requirements",
    )
    cmake = unique_replace(
        original["cmake"],
        'zephyr_sources_ifdef(\n  CONFIG_TRACING_BACKEND_RAM\n'
        '  tracing_backend_ram.c\n  )',
        'zephyr_sources_ifdef(\n  CONFIG_TRACING_BACKEND_RAM\n'
        '  tracing_backend_ram.c\n  )\n\n'
        'zephyr_sources_ifdef(\n  CONFIG_TRACING_BACKEND_ZEW_RTT\n'
        '  tracing_backend_zew_rtt.c\n  zew_trace_frame.c\n  )',
        "Zephyr tracing source selection",
    )
    header = unique_replace(
        original["header"],
        'void tracing_packet_drop_handle(void);\n',
        'void tracing_packet_drop_handle(void);\n'
        '/* ZEW: exact upstream asynchronous drop count (not loss-free proof). */\n'
        'uint32_t tracing_packet_drop_count_get(void);\n',
        "Tracing header drop counter",
    )
    core = unique_replace(
        original["core"],
        'void tracing_packet_drop_handle(void)\n{\n'
        '\tatomic_inc(&tracing_packet_drop_num);\n}\n',
        'void tracing_packet_drop_handle(void)\n{\n'
        '\tatomic_inc(&tracing_packet_drop_num);\n}\n\n'
        'uint32_t tracing_packet_drop_count_get(void)\n{\n'
        '\treturn (uint32_t)atomic_get(&tracing_packet_drop_num);\n}\n',
        "Tracing core drop counter",
    )

    changes = {
        paths["kconfig"]: kconfig.encode(),
        paths["cmake"]: cmake.encode(),
        paths["header"]: header.encode(),
        paths["core"]: core.encode(),
    }
    for name in ("zew_trace_frame.h", "zew_trace_frame.c",
                 "tracing_backend_zew_rtt.c"):
        destination = tracing / name
        if destination.exists() or destination.is_symlink():
            raise ValueError(f"Refusing to replace existing backend source: {destination}")
        changes[destination] = (HERE / name).read_bytes()
    return changes


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--zephyr-base", required=True, type=pathlib.Path)
    parser.add_argument("--apply", action="store_true",
                        help="Explicitly change the clean pinned Zephyr checkout")
    args = parser.parse_args()
    root = args.zephyr_base.resolve(strict=True)
    if git(root, "rev-parse", "HEAD") != PINNED_ZEPHYR:
        raise ValueError("Not the exact pinned Zephyr 4.4.0 commit; refused")
    if git(root, "status", "--porcelain", "--untracked-files=all"):
        raise ValueError("Zephyr working tree is not clean; refused")
    changes = propose(root)
    if not args.apply:
        print("PASS: exact pinned Zephyr source anchors, clean tree, "
              "source files verified; no writes")
        return 0
    backups: dict[pathlib.Path, bytes | None] = {}
    try:
        for target, data in changes.items():
            backups[target] = target.read_bytes() if target.exists() else None
            target.write_bytes(data)
        git(root, "diff", "--check")
    except Exception:
        for target, saved in backups.items():
            if saved is None:
                target.unlink(missing_ok=True)
            else:
                target.write_bytes(saved)
        raise
    print("APPLIED: experimental CTF RTT backend to pinned local Zephyr.")
    print("Review 'git -C <zephyr-base> diff' before building.")
    print("NO running-target, ELF hash, RTT channel or hardware attestation performed.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, subprocess.CalledProcessError) as problem:
        print(f"FAIL CLOSED: {problem}", file=sys.stderr)
        sys.exit(2)
