#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-only Kconfiglib symbol inspection using the *selected build's* Ninja environment.

Do not substitute a guessed BOARD, SoC, module list or Kconfig root. If the
original guiconfig target cannot be recovered, report unavailable instead.
No Kconfig or prj.conf files are written, even when a symbol is unavailable.
"""
import argparse
import base64
import json
import hashlib
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys

_ENV_NAME = re.compile(r"^[A-Za-z_][A-Za-z_0-9]*$")
_SYMBOL = re.compile(r"^[A-Z0-9_]{1,160}$")
_GUICONFIG_ARG = re.compile(
    r'''(?:^|\s)(?:"[^"\r\n]*guiconfig\.py"|'[^'\r\n]*guiconfig\.py'|[^\s"']*guiconfig\.py)(?=\s|$)''')
_ROOT_ARG = re.compile(r'''\s*(?:"([^"\r\n]+)"|'([^'\r\n]+)'|([^\s"']+))''')


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
        # The Windows Ninja target may be surrounded by cmd.exe /C
        # double quotes. Tokenising the entire shell wrapper with shlex
        # merges quoted script and environment boundaries. Locate the exact
        # generated cmake and guiconfig delimiters first; no shell commands
        # or arbitrary code from build.ninja are executed.
        script = _GUICONFIG_ARG.search(line)
        if script is None:
            failures.append("invalid generated guiconfig script argument")
            continue
        prefix, root_tail = line[:script.start()], line[script.end():]
        env_text = prefix.rsplit("-E env", 1)[1]
        try:
            env_tokens = shlex.split(env_text, posix=False)
        except ValueError:
            failures.append("invalid generated Ninja command quoting")
            continue
        values = {}
        for token in env_tokens:
            token = unwrap(token)
            if "=" not in token:
                continue
            name, value = token.split("=", 1)
            if _ENV_NAME.fullmatch(name):
                values[name] = value
        root = _ROOT_ARG.match(root_tail)
        if root is None:
            failures.append("guiconfig command has no Kconfig root argument")
            continue
        kroot = next(value for value in root.groups() if value is not None)
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


def causality(conf, library, name, max_nodes=512):
    """Traverse upstream expressions; preserve AND/OR/NOT semantics verbatim.

    Relationships describe evaluated configuration, not a proposed repair or
    proof of assignment origin: loading .config loses fragment precedence.
    Cycles are represented by edges, never recursively expanded.
    """
    if max_nodes < 1 or max_nodes > 4096:
        raise ValueError("Kconfig graph limit must be between 1 and 4096")
    def expression_text(expr):
        def display(item):
            if isinstance(item, library.Symbol) and item.is_constant and item.name not in ("y", "m", "n"):
                return '"<redacted>"'
            return library.standard_sc_expr_str(item)
        return library.expr_str(expr, display)
    pending = [name]
    nodes, edges = {}, set()
    truncated = False
    while pending:
        current = pending.pop(0)
        if current in nodes:
            continue
        if len(nodes) >= max_nodes:
            truncated = True
            break
        symbol = conf.syms[current]
        relations = []
        for attribute in ("direct_dep", "rev_dep", "weak_rev_dep"):
            expression = getattr(symbol, attribute)
            relations.append((attribute, expression))
        for index, (value, condition) in enumerate(symbol.defaults):
            relations.extend([(f"default[{index}].value", value),
                              (f"default[{index}].condition", condition)])
        for index, node in enumerate(symbol.nodes):
            if node.prompt:
                relations.append((f"prompt[{index}]", node.prompt[1]))
        choice = symbol.choice
        if choice is not None:
            relations.append(("choice.depends", choice.direct_dep))
        nodes[current] = {
            "symbol": current, "value": symbol.str_value if symbol.type in (library.BOOL, library.TRISTATE) else "<redacted>",
            "user_value": symbol.user_value if symbol.type in (library.BOOL, library.TRISTATE) else "<redacted>",
            "visibility": library.TRI_TO_STR[symbol.visibility],
            "definitions": sorted(f"{n.filename}:{n.linenr}" for n in symbol.nodes),
            "choice": None if choice is None else {
                "name": choice.name, "value": choice.str_value,
                "selection": choice.selection.name if choice.selection else None},
            "expressions": [{"kind": kind, "expression": expression_text(expr),
                             "value": library.TRI_TO_STR[library.expr_value(expr)]}
                            for kind, expr in relations],
        }
        for kind, expression in relations:
            for item in sorted(library.expr_items(expression),
                               key=lambda x: x.name or ""):
                if isinstance(item, library.Symbol) and not item.is_constant and item.nodes:
                    edges.add((current, kind, item.name))
                    if item.name not in nodes and item.name not in pending:
                        pending.append(item.name)
        pending.sort()
    return {"schema": "zew.kconfig-causality/1", "root": name,
            "coverage": "truncated" if truncated else "reachable-expressions",
            "assignment_origin": "unknown: .config does not preserve fragment precedence",
            "nodes": [nodes[key] for key in sorted(nodes)],
            "edges": [{"from": a, "kind": b, "to": c} for a, b, c in sorted(edges)]}


def inspect(build, ninja, name, graph_json=False):
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
    old_path = sys.path[:]
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
        if graph_json:
            graph = causality(conf, kconfiglib, name)
            inputs = {dotconfig.resolve(), kroot.resolve()}
            for filename in conf.kconfig_filenames:
                path = Path(filename)
                inputs.add((path if path.is_absolute() else zephyr / path).resolve())
            graph["provenance"] = {
                "method": "selected-build upstream kconfiglib evaluated expressions",
                "freshness": "unverified: recorded inputs require build receipt verification",
                "inputs": [{"path": str(path),
                            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                           for path in sorted(inputs)],
            }
            print(json.dumps(graph, sort_keys=True))
            return
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
        graph = causality(conf, kconfiglib, name) if hasattr(kconfiglib, "Symbol") else None
        if graph is not None:
            lines = ["Transitive Kconfig expressions (" + graph["coverage"] + "):"]
            for item in graph["nodes"]:
                lines.append(item["symbol"] + "=" + str(item["value"]))
                for expression in item["expressions"]:
                    lines.append("  " + expression["kind"] + ": " + expression["expression"]
                                 + " => " + expression["value"])
            help_text = ("\n".join(lines)[:12000] + "\nSource-fragment precedence remains unknown.\n\n"
                         + help_text[:5000])
        values_out = (
            name, kconfiglib.TYPE_TO_STR[symbol.type], symbol.str_value,
            visibility, assignable, dep, dep_state,
            " / ".join(prompts), "\n".join(definitions),
            "\n".join(selects), "\n".join(implies), help_text[:18000],
        )
        print("ZEW-KCONFIG-1")
        print("\t".join(field(value) for value in values_out))
    finally:
        sys.path[:] = old_path
        os.environ.clear()
        os.environ.update(old_env)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--ninja", type=Path, required=True)
    parser.add_argument("--symbol", required=True)
    parser.add_argument("--causality-json", action="store_true")
    args = parser.parse_args()
    try:
        inspect(args.build.resolve(), args.ninja.resolve(), args.symbol, args.causality_json)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, ImportError) as exc:
        print("ZEW-KCONFIG-UNAVAILABLE")
        print(str(exc).replace("\n", " ")[:500])
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
