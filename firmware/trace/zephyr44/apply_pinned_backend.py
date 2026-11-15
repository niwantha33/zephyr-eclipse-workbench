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
	depends on !TRACING_HANDLE_HOST_CMD
	depends on ENTROPY_HAS_DRIVER || TEST_RANDOM_GENERATOR
	help
	  Explicit experimental pinned-Zephyr CTF binary backend. This
	  requires separately verified probe and boot/image evidence.
	  Not an approved live stream and not a generic SoC feature.

config TRACING_BACKEND_ZEW_RAM
	bool "ZEW prototype: bounded framed CTF RAM capture (vendor-neutral)"
	depends on TRACING_CTF && TRACING_ASYNC && !SMP
	depends on !TRACING_HANDLE_HOST_CMD
	depends on ENTROPY_HAS_DRIVER || TEST_RANDOM_GENERATOR
	help
	  A finite RAM recording of integrity-framed CTF events. No RTT,
	  UART, OpenOCD running read or vendor-specific device is assumed.
"""

KCONFIG_SETTINGS = """
if TRACING_BACKEND_ZEW_RTT || TRACING_BACKEND_ZEW_RAM

config ZEW_TRACE_IMAGE_CONTRACT_SHA256
	string "Prebuild image-contract SHA256 hex (NOT ELF attestation)"
	default ""
	help
	  Exactly 64 lowercase hex characters, not the finished ELF digest.
	  Separate flashed-image verification remains REQUIRED.

config ZEW_TRACE_METADATA_SHA256
	string "Exact packed CTF metadata SHA256 hex"
	default ""
	help
	  Exactly 64 lowercase hex characters. Matching metadata is
	  necessary but NOT hardware evidence or authentication.

endif # selected ZEW backend

if TRACING_BACKEND_ZEW_RTT

config ZEW_TRACE_RTT_UP_BYTES
	int "Dedicated RTT up-buffer bytes (prototype)"
	range 1024 16384
	default 8192

endif # TRACING_BACKEND_ZEW_RTT

if TRACING_BACKEND_ZEW_RAM

config ZEW_TRACE_RAM_BYTES
	int "Max framed CTF bytes retained in target RAM"
	range 1024 16384
	default 4096
	help
	  Non-wrapping bounded capture. On full, the interval fails closed.

endif # TRACING_BACKEND_ZEW_RAM
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
        "format": tracing / "tracing_format_async.c",
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
        '\tdefault "tracing_backend_zew_rtt" if TRACING_BACKEND_ZEW_RTT\n'
        '\tdefault "tracing_backend_zew_ram" if TRACING_BACKEND_ZEW_RAM\n',
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
        '  tracing_backend_zew_rtt.c\n  zew_trace_frame.c\n  )\n\n'
        'zephyr_sources_ifdef(\n  CONFIG_TRACING_BACKEND_ZEW_RAM\n'
        '  tracing_backend_zew_ram.c\n  zew_trace_frame.c\n  )',
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

    # Stop is requested by an APPLICATION thread. It disables new tracing
    # under the same IRQ lock as the format hooks, so a worker can eventually
    # drain and seal the exact producer prefix. An async CTF event cannot be
    # admitted after the stop flag is set on this !SMP backend.
    core = unique_replace(
        core,
        'static atomic_t tracing_packet_drop_num;\n',
        'static atomic_t tracing_packet_drop_num;\n'
        '#if defined(CONFIG_TRACING_BACKEND_ZEW_RTT) || '
        'defined(CONFIG_TRACING_BACKEND_ZEW_RAM)\n'
        'static atomic_t zew_trace_stop_requested;\n'
        'void zew_trace_backend_close(void);\n'
        '#endif\n',
        "Stop state",
    )
    core = unique_replace(
        core,
        '\t\tif (tracing_buffer_is_empty()) {\n'
        '\t\t\tk_sem_take(&tracing_thread_sem, K_FOREVER);\n',
        '\t\tif (tracing_buffer_is_empty()) {\n'
        '#if defined(CONFIG_TRACING_BACKEND_ZEW_RTT) || '
        'defined(CONFIG_TRACING_BACKEND_ZEW_RAM)\n'
        '\t\t\tif (atomic_cas(&zew_trace_stop_requested, 1, 2)) {\n'
        '\t\t\t\tzew_trace_backend_close();\n'
        '\t\t\t}\n'
        '#endif\n'
        '\t\t\tk_sem_take(&tracing_thread_sem, K_FOREVER);\n',
        "Async drained END",
    )
    core = unique_replace(
        core,
        '\ttracing_buffer_init();\n',
        '\ttracing_buffer_init();\n'
        '#if defined(CONFIG_TRACING_BACKEND_ZEW_RTT) || '
        'defined(CONFIG_TRACING_BACKEND_ZEW_RAM)\n'
        '\tatomic_set(&zew_trace_stop_requested, 0);\n'
        '#endif\n',
        "Stop state reset",
    )
    core = unique_replace(
        core,
        '#include <string.h>\n',
        '#include <string.h>\n#include <errno.h>\n',
        "Stop errno declarations",
    )
    core += (
        '\n#if defined(CONFIG_TRACING_BACKEND_ZEW_RTT) || '
        'defined(CONFIG_TRACING_BACKEND_ZEW_RAM)\n'
        '/* Nonblocking request; MUST NOT be invoked from ISR or trace worker. */\n'
        'int zew_trace_stop_request(void)\n'
        '{\n'
        '\tif (k_is_in_isr() || is_tracing_thread()) {\n'
        '\t\treturn -EWOULDBLOCK;\n'
        '\t}\n'
        '\tunsigned int key = irq_lock();\n'
        '\tif (atomic_get(&zew_trace_stop_requested) != 0 ||\n'
        '\t    atomic_get(&tracing_state) != TRACING_ENABLE) {\n'
        '\t\tirq_unlock(key);\n'
        '\t\treturn -EALREADY;\n'
        '\t}\n'
        '\ttracing_set_state(TRACING_DISABLE);\n'
        '\tatomic_set(&zew_trace_stop_requested, 1);\n'
        '\tirq_unlock(key);\n'
        '\tk_sem_give(&tracing_thread_sem);\n'
        '\treturn 0;\n'
        '}\n'
        '#endif\n'
    )
    header = unique_replace(
        header,
        'uint32_t tracing_packet_drop_count_get(void);\n',
        'uint32_t tracing_packet_drop_count_get(void);\n'
        '#if defined(CONFIG_TRACING_BACKEND_ZEW_RTT) || '
        'defined(CONFIG_TRACING_BACKEND_ZEW_RAM)\n'
        'int zew_trace_stop_request(void);\n'
        '#endif\n',
        "Stop API declaration",
    )
    trace_format = original["format"]
    for func in ("string", "raw_data", "data"):
        func_call = {
            "string": "tracing_format_string_put(str, args)",
            "raw_data": "tracing_format_raw_data_put(data, length)",
            "data": "tracing_format_data_put(tracing_data_array, count)",
        }[func]
        trace_format = unique_replace(
            trace_format,
            '\tTRACING_LOCK();\n'
            '\tbefore_put_is_empty = tracing_buffer_is_empty();\n'
            f'\tput_success = {func_call};\n'
            '\tTRACING_UNLOCK();\n',
            '\tTRACING_LOCK();\n'
            '\tif (is_tracing_enabled()) {\n'
            '\t\tbefore_put_is_empty = tracing_buffer_is_empty();\n'
            f'\t\tput_success = {func_call};\n'
            '\t} else {\n'
            '\t\t/* Stop won the IRQ lock: no late ring append. */\n'
            '\t\tbefore_put_is_empty = false;\n'
            '\t\tput_success = true;\n'
            '\t}\n'
            '\tTRACING_UNLOCK();\n',
            f"Async producer stop recheck: {func}",
        )
    changes = {
        paths["kconfig"]: kconfig.encode(),
        paths["cmake"]: cmake.encode(),
        paths["header"]: header.encode(),
        paths["format"]: trace_format.encode(),
        paths["core"]: core.encode(),
    }
    for name in ("zew_trace_frame.h", "zew_trace_frame.c",
                 "tracing_backend_zew_rtt.c", "tracing_backend_zew_ram.c"):
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
