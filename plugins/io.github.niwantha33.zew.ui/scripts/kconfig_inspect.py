#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-only Kconfiglib symbol inspection using the *selected build's* Ninja environment.

Do not substitute a guessed BOARD, SoC, module list or Kconfig root. If the
original guiconfig target cannot be recovered, report unavailable instead.
No Kconfig or prj.conf files are written, even when a symbol is unavailable.
"""
import argparse
import base64
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys

_ENV_NAME = re.compile(r"^[A-Za-z_][A-Za-z_0-9]*$")
_SYMBOL = re.compile(r"^[A-Z0-9_]{1,160}$")


def unwrap(token):
    return token[1:-1] if len(token) >= 2 and token[0] == token[-1] and token[0] in "'\"" else token


def extract_command(output):
    """Extract the env and Kconfig root from Ninja's original guiconfig command.

    Use non-POSIX tokenisation: Windows paths contain literal backslashes.
    Never execute the extracted command; only its environment is read.
    """
    failures = []
    for line in output.splitlines():
        if "guiconfig.py" not in line:
            continue
        if "-E env" not in line:
            failures.append("guiconfig command has no literal CMake -E env")
            continue
        tokens = [unwrap(token) for token in shlex.split(line, posix=False)]
        try:
            start = next(i + 2 for i in range(len(tokens) - 1)
                         if tokens[i] == "-E" and tokens[i + 1] == "env")
            script = next(i for i in range(start, len(tokens))
                          if tokens[i].replace("\\", "/").endswith("/scripts/kconfig/guiconfig.py"))
        except StopIteration:
            failures.append("could not locate CMake env or guiconfig.py token")
            continue
        values = {}
        for token in tokens[start:script]:
            if "=" not in token:
                continue
            name, value = token.split("=", 1)
            if _ENV_NAME.fullmatch(name):
                values[name] = value
        if script + 1 >= len(tokens):
            continue
        kroot = tokens[script + 1]
        missing = [key for key in ("srctree", "KCONFIG_BINARY_DIR", "EDT_PICKLE")
                   if not values.get(key)]
        if missing:
            failures.append("missing Kconfig environment keys: " + ", ".join(missing))
            continue
        if not Path(kroot).is_file():
            failures.append("Kconfig root not found (argument path does not resolve)")
            continue
        return values, Path(kroot)
    summary = failures[0] if failures else (
        "no guiconfig.py command found; generated target may be a wrapper")
    raise RuntimeError("No valid generated guiconfig environment for this exact build: "
                       + summary)


def field(value):
    return base64.b64encode(str(value).encode("utf-8")).decode("ascii")


def inspect(build, ninja, name):
    if not _SYMBOL.fullmatch(name):
        raise ValueError("Invalid Kconfig symbol")
    dotconfig = build / "zephyr" / ".config"
    if not dotconfig.is_file() or not (build / "build.ninja").is_file():
        raise RuntimeError("A configured Ninja build and zephyr/.config are required")
    result = subprocess.run(
        [str(ninja), "-t", "commands", "guiconfig"],
        cwd=build, capture_output=True, text=True, encoding="utf-8",
        errors="replace", timeout=8, check=True)
    values, kroot = extract_command(result.stdout)
    zephyr = Path(values["srctree"]).resolve()
    kconfiglib_path = zephyr / "scripts" / "kconfig"
    if not kconfiglib_path.is_dir():
        raise RuntimeError("Zephyr Kconfiglib not found at the selected build's source root")
    sys.path.insert(0, str(kconfiglib_path))
    import kconfiglib

    old_env = os.environ.copy()
    try:
        os.environ.update(values)
        os.environ["ZEPHYR_BASE"] = str(zephyr)
        # Kconfiglib reads the original build's generated Kconfig modules,
        # board files, toolchain and edt.pickle paths from the captured env.
        conf = kconfiglib.Kconfig(str(kroot), warn_to_stderr=False)
        conf.load_config(str(dotconfig))
        symbol = conf.syms.get(name)
        if symbol is None or not symbol.nodes:
            raise RuntimeError("Symbol was not defined in the selected build's Kconfig tree")
        dep = kconfiglib.expr_str(symbol.direct_dep)
        dep_state = kconfiglib.TRI_TO_STR[kconfiglib.expr_value(symbol.direct_dep)]
        visibility = kconfiglib.TRI_TO_STR[symbol.visibility]
        assignable = ",".join(kconfiglib.TRI_TO_STR[x] for x in symbol.assignable)
        prompts = [node.prompt[0] for node in symbol.nodes if node.prompt]
        help_text = next((node.help.strip() for node in symbol.nodes if node.help), "")
        definitions = [f"{node.filename}:{node.linenr}" for node in symbol.nodes]
        selects = [f"{target.name} if {kconfiglib.expr_str(condition)}"
                   for target, condition in symbol.selects]
        implies = [f"{target.name} if {kconfiglib.expr_str(condition)}"
                   for target, condition in symbol.implies]
        values_out = (
            name, kconfiglib.TYPE_TO_STR[symbol.type], symbol.str_value,
            visibility, assignable, dep, dep_state,
            " / ".join(prompts), "\n".join(definitions),
            "\n".join(selects), "\n".join(implies), help_text[:5000],
        )
        print("ZEW-KCONFIG-1")
        print("\t".join(field(value) for value in values_out))
    finally:
        os.environ.clear()
        os.environ.update(old_env)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--ninja", type=Path, required=True)
    parser.add_argument("--symbol", required=True)
    args = parser.parse_args()
    try:
        inspect(args.build.resolve(), args.ninja.resolve(), args.symbol)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, ImportError) as exc:
        print("ZEW-KCONFIG-UNAVAILABLE")
        print(str(exc).replace("\n", " ")[:500])
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
