# SPDX-License-Identifier: Apache-2.0
"""CS-03/04: evaluated, per-image Kconfig causes and request diagnostics.

Only upstream Kconfiglib evaluates expressions. No hypothetical dependency
repair is inferred. The selected checkout is executable trusted local code.
"""
from __future__ import annotations

import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

from .collector import InputError, _images, _read_observed, _regular
from .config_request_check import _read_snippet, _requests
from .config_semantics import _generated_environment, _load_selected_kconfiglib
from .kconfig_causality import causality

MAX_INPUT = 8 * 1024 * 1024
MAX_TOTAL = 128 * 1024 * 1024


def _snapshot(path):
    path = Path(path).absolute()
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise InputError("Symlinked Kconfig evidence rejected")
    digest, size, _ = _read_observed(path, MAX_INPUT)
    return {"path": str(path), "sha256": digest, "bytes": size}


def _inputs(tree, zephyr, root, config, ninja_file, library_path):
    paths = {root, config, ninja_file, library_path}
    for filename in tree.kconfig_filenames:
        path = Path(filename)
        paths.add(path if path.is_absolute() else zephyr / path)
    if len(paths) > 20000:
        raise InputError("Too many Kconfig inputs")
    result, total = [], 0
    for path in sorted(paths):
        # Stop before hashing another large file once the cumulative budget
        # would be exceeded. _snapshot still checks the read against races.
        if path.stat().st_size > MAX_TOTAL - total:
            raise InputError("Kconfig input budget exceeded")
        entry = _snapshot(path)
        total += entry["bytes"]
        if total > MAX_TOTAL:
            raise InputError("Kconfig input budget exceeded")
        result.append(entry)
    return result


def _finding(rule, image, symbol, message, evidence, action, severity="warning", confidence="derived"):
    return {"id": f"{rule}:{image}:{symbol}", "rule_id": rule,
            "image": image, "symbol": symbol, "severity": severity,
            "confidence": confidence, "message": message,
            "evidence": evidence, "next_action": action}


def diagnose(tree, library, requests, image, evidence):
    """Classify requests without mutating upstream symbol state or user sources."""
    output = []
    for request in requests:
        name = request["symbol"]
        short = name.removeprefix("CONFIG_")
        values = request["values"]
        source_evidence = [{**item, **({"line": request["lines"][0]}
                                    if item.get("kind") == "snippet" else {})}
                           for item in evidence]
        symbol = tree.syms.get(short)
        if len(values) != 1:
            rule, message, action = ("CS-KCONFIG-CONFLICTING-REQUESTS",
                "This fragment requests different values for the same symbol.",
                "Review all recorded source lines; retain one deliberate request.")
        elif symbol is None or not symbol.nodes:
            rule, message, action = ("CS-KCONFIG-UNDEFINED",
                "Symbol has no definition in the selected upstream Kconfig tree.",
                "Check the symbol spelling, selected Zephyr version and module roots.")
        elif symbol.type not in (library.BOOL, library.TRISTATE) or next(iter(values)) not in ("y", "m", "n"):
            rule, message, action = ("CS-KCONFIG-UNKNOWN",
                "Value/type is outside the supported boolean/tristate diagnostic scope.",
                "Inspect the upstream type, range and original value locally.")
        else:
            wanted = next(iter(values))
            numeric = {"n": 0, "m": 1, "y": 2}[wanted]
            dependency = library.expr_value(symbol.direct_dep)
            if wanted == symbol.str_value:
                rule, message, action = ("CS-KCONFIG-VALID",
                    "Request matches the evaluated generated configuration.",
                    "Rebuild after source changes; runtime behavior remains unverified.")
            elif numeric > dependency:
                rule, message, action = ("CS-KCONFIG-UNMET-DEPENDENCY",
                    "The evaluated direct dependency limits the requested tristate.",
                    "Inspect the transitive expression graph; OR/NOT terms are not automatic fixes.")
            elif symbol.visibility == 0:
                rule, message, action = ("CS-KCONFIG-INVISIBLE",
                    "The differing request targets a symbol with no currently visible prompt.",
                    "Inspect prompt/choice conditions and reverse select/imply evidence.")
            else:
                rule, message, action = ("CS-KCONFIG-REQUEST-MISMATCH",
                    "Requested and evaluated values differ; causation is not established.",
                    "Check active fragment order, choice selection and upstream traceconfig.")
        output.append(_finding(rule, image, name, message, source_evidence, action,
                               "info" if rule.endswith("VALID") else "warning",
                               "unknown" if rule.endswith("UNKNOWN") else "derived"))
    return output


def _trace(build, names):
    """Consume upstream origin types; never mistake the report for an attestation."""
    path = _regular(build, "zephyr/kconfig-trace.md")
    if path is None:
        return {"state": "missing", "origins": [], "next_action": "Run west build -t traceconfig explicitly."}
    digest, _, data = _read_observed(path, 4 * 1024 * 1024, capture=True)
    origins = []
    for line in data.decode("utf-8", errors="strict").splitlines():
        if not line.startswith("|"):
            continue
        fields = re.split(r"(?<!\\)\|", line)
        if len(fields) < 7:
            continue
        tagged = fields[2].strip().removesuffix(" (h)").strip("`")
        origin = fields[4].strip().lower()
        if tagged.removeprefix("CONFIG_") in names and origin in ("assigned", "default", "selected", "implied"):
            origins.append({"symbol": tagged, "origin": origin})
    return {"state": "observed-unverified", "sha256": digest,
            "origins": sorted(origins, key=lambda x: (x["symbol"], x["origin"]))}


def inspect(build: Path, snippet: Path | None = None, symbol: str | None = None,
            ninja: Path | None = None, images=None):
    root = Path(build).absolute()
    if not root.is_dir() or any(p.is_symlink() for p in (root, *root.parents)):
        raise InputError("Select an existing non-symlink build root")
    root = root.resolve(strict=True)
    if symbol is not None and not re.fullmatch(r"(?:CONFIG_)?[A-Z0-9_]{1,160}", symbol):
        raise InputError("Invalid Kconfig symbol")
    requests, invalid, snippet_evidence = [], [], []
    if snippet:
        content, sha, _ = _read_snippet(snippet)
        requests, invalid = _requests(content)
        snippet_evidence = [{"kind": "snippet", "path": str(Path(snippet).absolute()), "sha256": sha}]
    if not requests and symbol is None:
        raise InputError("Specify a symbol or a fragment containing configuration requests")
    names = sorted({r["symbol"].removeprefix("CONFIG_") for r in requests}
                   | ({symbol.removeprefix("CONFIG_")} if symbol else set()))
    executable = str(ninja) if ninja else shutil.which("ninja")
    rows, findings = [], []
    for image, directory in _images(root, images or []):
        config, generated = _regular(directory, "zephyr/.config"), _regular(directory, "build.ninja")
        row = {"image": image, "state": "unavailable", "freshness": "unverified", "graphs": []}
        if config is None or generated is None or executable is None:
            row["reason"] = "configured-ninja-build-required"
            findings.extend(_finding("CS-KCONFIG-GENERATED-MISSING", image, "CONFIG_" + name,
                "Exact configured Ninja build or evaluator executable is unavailable.", snippet_evidence,
                "Configure the selected board/image; retain failed configure logs.", confidence="unknown") for name in names)
            rows.append(row)
            continue
        env, import_path, old_path = os.environ.copy(), None, sys.path[:]
        saved_functions = sys.modules.pop("kconfigfunctions", None)
        try:
            result = subprocess.run([executable, "-t", "commands", "guiconfig"], cwd=directory,
                capture_output=True, encoding="utf-8", errors="strict", text=True, timeout=10,
                check=True, stdin=subprocess.DEVNULL)
            if len(result.stdout) > 256 * 1024:
                raise InputError("Generated command exceeds inspection limit")
            values, kroot = _generated_environment(result.stdout)
            zephyr = Path(values["srctree"]).resolve(strict=True)
            import_path = zephyr / "scripts/kconfig"
            sys.path.insert(0, str(import_path))
            os.environ.update(values)
            os.environ["ZEPHYR_BASE"] = str(zephyr)
            library = _load_selected_kconfiglib(zephyr)
            # First discover the exact active upstream source set, then hash before
            # and after the actual evaluation to detect concurrent edits.
            initial = library.Kconfig(str(kroot), warn_to_stderr=False)
            before = _inputs(initial, zephyr, kroot, config, generated, import_path / "kconfiglib.py")
            tree = library.Kconfig(str(kroot), warn_to_stderr=False)
            tree.load_config(str(config))
            graphs = [causality(tree, library, name) for name in names if name in tree.syms and tree.syms[name].nodes]
            after = _inputs(tree, zephyr, kroot, config, generated, import_path / "kconfiglib.py")
            if before != after:
                raise InputError("Kconfig inputs changed during evaluation")
            if snippet and _read_snippet(snippet)[1] != snippet_evidence[0]["sha256"]:
                raise InputError("Configuration requests changed during evaluation")
            evidence = snippet_evidence + [{"kind": "generated-config", **next(p for p in after if p["path"] == str(config))}]
            local_findings = diagnose(tree, library, requests, image, evidence)
            row.update(state="evaluated", graphs=graphs, inputs=after,
                       trace=_trace(directory, {n["symbol"] for g in graphs for n in g["nodes"]}),
                       assignment_precedence="unknown-without-pristine-upstream-trace")
            findings.extend(local_findings)
        except Exception:
            row.update(state="unavailable", reason="selected-kconfig-evaluation-failed-or-inputs-changed")
            findings.extend(_finding("CS-KCONFIG-UNKNOWN", image, "CONFIG_" + name,
                "Selected upstream Kconfig evaluation could not establish stable evidence.", snippet_evidence,
                "Check the exact Ninja environment, selected checkout and configuration inputs.", confidence="unknown") for name in names)
        finally:
            os.environ.clear()
            os.environ.update(env)
            sys.path[:] = old_path
            sys.modules.pop("kconfigfunctions", None)
            if saved_functions is not None:
                sys.modules["kconfigfunctions"] = saved_functions
        rows.append(row)
    for line in invalid:
        findings.append(_finding("CS-KCONFIG-INVALID-LINE", "source", f"line-{line}",
            "Fragment line is not a supported Kconfig assignment.",
            [{**p, "line": line} for p in snippet_evidence], "Review the original fragment syntax."))
    return {"schema_version": "zew.studio-kconfig/1", "status": "evaluated" if all(r["state"] == "evaluated" for r in rows) else "partial",
            "images": rows, "findings": sorted(findings, key=lambda f: f["id"]),
            "limitations": ["Selected checkout code is trusted and executed locally; no shell target is executed.",
                "No hypothetical dependency fix, source-fragment precedence or runtime cause is asserted.",
                "Input stability during inspection does not prove freshness against the original build."]}
