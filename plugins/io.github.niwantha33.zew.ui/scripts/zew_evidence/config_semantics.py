# SPDX-License-Identifier: Apache-2.0
"""Optional read-only Kconfiglib semantics from an exact configured Ninja build.

Kconfig semantics are evaluated by the *selected Zephyr checkout's* kconfiglib.
Ninja's generated guiconfig command is INSPECTED, never executed; only the
literal environment and Kconfig root are reused. No guessed BOARD or SoC.
"""
from __future__ import annotations

import importlib.util
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

from .collector import InputError, _digest, _images, _regular

_ENV_NAME = re.compile(r"^[A-Za-z_][A-Za-z_0-9]*$")
_GUICONFIG = re.compile(
    r'''(?:^|\s)(?:"[^"\r\n]*guiconfig\.py"|'[^'\r\n]*guiconfig\.py'|[^\s"']*guiconfig\.py)(?=\s|$)''')
_ROOT = re.compile(r'''\s*(?:"([^"\r\n]+)"|'([^'\r\n]+)'|([^\s"']+))''')
MAX_NINJA_TEXT = 256 * 1024
_ALLOWED_TRISTATES = ("n", "m", "y")


def _environment_tokens(text: str) -> list[str]:
    """Split literal env assignments without treating Windows backslashes as escapes."""
    output: list[str] = []
    buffer: list[str] = []
    quoted = None
    for char in text:
        if quoted:
            if char == quoted:
                quoted = None
            else:
                buffer.append(char)
        elif char in ("'", '"'):
            quoted = char
        elif char.isspace():
            if buffer:
                output.append("".join(buffer))
                buffer.clear()
        else:
            buffer.append(char)
    if quoted:
        raise ValueError("Unterminated generated environment quote")
    if buffer:
        output.append("".join(buffer))
    return output


def _generated_environment(commands: str) -> tuple[dict[str, str], Path]:
    """Recover only literal -E env pairs and Kconfig root, never a shell command."""
    for command in commands.splitlines():
        if "guiconfig.py" not in command or "-E env" not in command:
            continue
        script = _GUICONFIG.search(command)
        if script is None:
            continue
        prefix, tail = command[:script.start()], command[script.end():]
        try:
            tokens = _environment_tokens(prefix.rsplit("-E env", 1)[1])
        except ValueError:
            continue
        values = {}
        for token in tokens:
            if "=" not in token:
                continue
            name, value = token.split("=", 1)
            if _ENV_NAME.fullmatch(name):
                values[name] = value
        root = _ROOT.match(tail)
        if root is None or not all(values.get(k) for k in
                                   ("srctree", "KCONFIG_BINARY_DIR", "EDT_PICKLE")):
            continue
        kroot = Path(next(part for part in root.groups() if part is not None))
        if not kroot.is_file():
            continue
        return values, kroot
    raise InputError("The exact build has no usable generated guiconfig environment")


def _load_selected_kconfiglib(zephyr: Path):
    source = zephyr / "scripts" / "kconfig" / "kconfiglib.py"
    if not source.is_file():
        raise InputError("Selected Zephyr checkout lacks Kconfiglib")
    spec = importlib.util.spec_from_file_location("zew_selected_kconfiglib", source)
    if spec is None or spec.loader is None:
        raise InputError("Selected Kconfiglib cannot be loaded")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _semantics_for_image(build: Path, ninja: Path | None, names: list[str],
                         expected_hash: str | None) -> dict:
    config = _regular(build, "zephyr/.config")
    build_ninja = _regular(build, "build.ninja")
    if config is None or build_ninja is None or not expected_hash:
        return {"state": "unavailable", "reason": "configured-ninja-build-required", "symbols": {}}
    if ninja is None:
        return {"state": "unavailable", "reason": "ninja-executable-not-found", "symbols": {}}
    before, _ = _digest(config)
    if before != expected_hash:
        return {"state": "unavailable", "reason": "generated-config-changed", "symbols": {}}
    try:
        result = subprocess.run([str(ninja), "-t", "commands", "guiconfig"],
                                cwd=build, capture_output=True, text=True, encoding="utf-8",
                                errors="replace", timeout=10, check=True, stdin=subprocess.DEVNULL)
    except (OSError, subprocess.SubprocessError):
        return {"state": "unavailable", "reason": "ninja-command-inspection-failed", "symbols": {}}
    if len(result.stdout) > MAX_NINJA_TEXT:
        return {"state": "unavailable", "reason": "generated-command-too-large", "symbols": {}}
    selected_import_path = None
    try:
        values, root = _generated_environment(result.stdout)
        zephyr = Path(values["srctree"]).resolve(strict=True)
        if not zephyr.is_dir():
            raise InputError("Selected Zephyr tree is not a directory")
        # Zephyr's Kconfiglib dynamically imports kconfigfunctions while
        # parsing the Kconfig root. Its directory must be importable for
        # the entire evaluation, as in the native kconfig_inspect.py probe.
        # Use the *selected* checkout only; never the host's kconfigfunctions.
        selected_import_path = str(zephyr / "scripts" / "kconfig")
        sys.path.insert(0, selected_import_path)
        library = _load_selected_kconfiglib(zephyr)
    except Exception:
        if selected_import_path is not None and selected_import_path in sys.path:
            sys.path.remove(selected_import_path)
        # A selected checkout's Kconfig module can fail to import. Never echo
        # arbitrary source paths or exception text into the public report.
        return {"state": "unavailable", "reason": "generated-kconfig-environment-unavailable", "symbols": {}}
    original_env = os.environ.copy()
    observations = {}
    try:
        os.environ.update(values)
        os.environ["ZEPHYR_BASE"] = str(zephyr)
        tree = library.Kconfig(str(root), warn_to_stderr=False)
        tree.load_config(str(config))
        for name in sorted(set(names)):
            symbol = tree.syms.get(name.removeprefix("CONFIG_"))
            if symbol is None or not symbol.nodes:
                observations[name] = {"state": "undefined-in-selected-tree",
                                      "confidence": "derived"}
                continue
            type_name = library.TYPE_TO_STR.get(symbol.type, "unknown")
            effective = symbol.str_value if symbol.str_value in _ALLOWED_TRISTATES else "<redacted>"
            observations[name] = {
                "state": "defined-in-selected-tree",
                "confidence": "derived",
                "type": type_name if type_name in ("bool", "tristate", "string", "int", "hex")
                    else "unknown",
                "effective": effective,
                "assignable": [library.TRI_TO_STR[v] for v in symbol.assignable
                               if v in library.TRI_TO_STR],
                "dependency_value": library.TRI_TO_STR.get(
                    library.expr_value(symbol.direct_dep), "unknown"),
                "visibility": library.TRI_TO_STR.get(symbol.visibility, "unknown"),
                "definition_count": len(symbol.nodes),
            }
    except Exception:
        # Kconfiglib may reject stale/malformed generated inputs. Preserve
        # unknown evidence state instead of leaking local paths or traceback.
        return {"state": "unavailable", "reason": "kconfiglib-evaluation-failed", "symbols": {}}
    finally:
        os.environ.clear()
        os.environ.update(original_env)
        if selected_import_path in sys.path:
            sys.path.remove(selected_import_path)
    after, _ = _digest(config)
    if after != before:
        return {"state": "unavailable", "reason": "generated-config-changed", "symbols": {}}
    return {"state": "evaluated", "reason": "selected-build-kconfiglib",
            "generated_config_sha256": before, "symbols": observations}


def enrich(report: dict, build: Path, images: list[str], ninja: Path | None = None) -> dict:
    """Add conservative Kconfiglib evidence without changing snapshot findings."""
    root = Path(build).resolve(strict=True)
    if ninja is None:
        found = shutil.which("ninja")
        executable = Path(found) if found else None
    else:
        executable = Path(ninja).absolute()
        if not executable.is_file():
            raise InputError("--ninja must point to an existing executable")
    directories = dict(_images(root, images))
    unresolved = 0
    for image in report["images"]:
        expected = image["generated_config"].get("sha256")
        names = [request["symbol"] for request in image["requests"]]
        checked = _semantics_for_image(directories[image["image"]], executable, names, expected)
        image["semantic"] = {"state": checked["state"], "reason": checked["reason"]}
        if checked["state"] != "evaluated":
            for request in image["requests"]:
                request["kconfiglib"] = {"state": "unknown", "reason": checked["reason"]}
                unresolved += 1
            continue
        image["semantic"]["generated_config_sha256"] = checked["generated_config_sha256"]
        for request in image["requests"]:
            detail = checked["symbols"][request["symbol"]]
            if detail["state"] == "undefined-in-selected-tree":
                request["kconfiglib"] = detail
                unresolved += 1
                continue
            wanted = request["requested"]
            if detail["type"] not in ("bool", "tristate"):
                state = "non-boolean-type"
            elif wanted not in _ALLOWED_TRISTATES:
                state = "request-not-comparable"
            elif wanted not in detail["assignable"]:
                state = "effective-but-not-user-assignable" if wanted == detail["effective"] \
                    else "request-not-assignable-in-current-state"
            else:
                state = "assignable-in-current-state"
            request["kconfiglib"] = {**detail, "request_state": state}
            if state not in ("assignable-in-current-state", "effective-but-not-user-assignable"):
                unresolved += 1
    report["semantic_validation"] = {
        "state": "evaluated-without-blockers" if not unresolved and report["images"]
            else "incomplete-or-blocked",
        "unresolved": unresolved,
        "scope": "Kconfiglib evaluates selected build state; no hypothetical reconfigure or dependency fix is inferred",
    }
    report["limitations"].append(
        "Semantic checks are against the last configured Kconfig environment; source tree changes since build are not ruled out.")
    return report
