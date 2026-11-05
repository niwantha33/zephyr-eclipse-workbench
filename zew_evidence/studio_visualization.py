# SPDX-License-Identifier: Apache-2.0
"""CS-17: bounded, deterministic views over recorded findings and causal graphs.

Rendering performs no evaluation and never upgrades confidence or freshness.
DOT is a portable graph artifact, not an installed viewer or a network service.
"""
from __future__ import annotations

import html
import json
import re
import unicodedata

from .collector import InputError
from .studio_answers import report_digest


def _plain(value, limit=180):
    if not isinstance(value, (str, int)) or isinstance(value, bool):
        return "unknown"
    text = "".join(c if unicodedata.category(c)[0] != "C" else " " for c in str(value))
    return text[:limit] + ("…" if len(text) > limit else "")


def _markdown(value, limit=180):
    text = html.escape(_plain(value, limit), quote=True)
    # Untrusted source values cannot create links, code, tables, or formatting.
    return re.sub(r"([\\`*_{}\[\]()|!#])", r"\\\1", text)


def _dot(value):
    # JSON quoted strings are valid DOT strings; never emit HTML labels.
    return json.dumps(_plain(value), ensure_ascii=True)


def _graph(report, focus, image):
    rows = report.get("images", [])
    if not isinstance(rows, list):
        raise InputError("Invalid image graph collection")
    graphs = []
    for row in rows:
        if not isinstance(row, dict) or image is not None and row.get("image") != image:
            continue
        candidates = row.get("graphs", [])
        if not isinstance(candidates, list):
            raise InputError("Invalid expression graph collection")
        for graph in candidates:
            if isinstance(graph, dict) and graph.get("root") == focus.removeprefix("CONFIG_"):
                graphs.append((row.get("image"), row.get("freshness", "unverified"), graph))
    if len(graphs) != 1:
        raise InputError("Select an unambiguous image and exact recorded graph root")
    return graphs[0]


def render(report: dict, format: str = "markdown", max_findings: int = 100,
           focus: str | None = None, image: str | None = None) -> dict:
    """Render findings, or one exact Kconfig graph (`focus`, optional `image`).

    Return JSON metadata + `content`; the caller chooses whether to save text.
    `max_findings` also bounds focused graph nodes. Truncation is explicit.
    """
    if format not in ("markdown", "dot"):
        raise InputError("Choose markdown or dot")
    if type(max_findings) is not int or not 1 <= max_findings <= 500:
        raise InputError("Visualization limit must be between 1 and 500")
    digest = report_digest(report)
    state = report.get("freshness", "unverified")
    result = {"schema_version": "zew.studio-visualization/1", "format": format,
              "report_sha256": digest, "freshness": _plain(state, 60),
              "interpretation": "Recorded evidence only; rendering does not verify source freshness or runtime behavior."}
    if focus is not None:
        if not isinstance(focus, str) or not re.fullmatch(r"(?:CONFIG_)?[A-Z0-9_]{1,160}", focus):
            raise InputError("Select an exact Kconfig graph root")
        selected_image, freshness, graph = _graph(report, focus, image)
        nodes, edges = graph.get("nodes"), graph.get("edges")
        if not isinstance(nodes, list) or not isinstance(edges, list) or len(nodes) > 20000 or len(edges) > 100000:
            raise InputError("Invalid or oversized recorded expression graph")
        nodes = sorted((n for n in nodes if isinstance(n, dict) and isinstance(n.get("symbol"), str)),
                       key=lambda node: (node["symbol"] != graph["root"], node["symbol"]))
        # Duplicate node identities make a graph ambiguous rather than drawable.
        if len({node["symbol"] for node in nodes}) != len(nodes):
            raise InputError("Duplicate expression graph node")
        if not any(node["symbol"] == graph["root"] for node in nodes):
            raise InputError("Recorded graph has no root node")
        selected = nodes[:max_findings]
        identifiers = {n["symbol"]: f"n{index}" for index, n in enumerate(selected)}
        selected_edges = [e for e in edges if isinstance(e, dict)
                          and e.get("from") in identifiers and e.get("to") in identifiers]
        selected_edges.sort(key=lambda edge: (edge["from"], str(edge.get("kind")), edge["to"]))
        truncated = len(nodes) > len(selected) or graph.get("coverage") == "truncated" or len(selected_edges) > 2000
        selected_edges = selected_edges[:2000]
        if state in ("stale", "changed"):
            freshness = state
        result.update(image=_plain(selected_image, 80), freshness=_plain(freshness, 60),
                      focus=focus, displayed=len(selected), total=len(nodes), truncated=truncated)
        if format == "dot":
            lines = ["digraph configuration {", '  graph [label="Recorded Kconfig expression references; not automatic fixes"];',
                     "  rankdir=TB;"]
            for node in selected:
                label = node["symbol"] + " = " + _plain(node.get("value"), 30)
                lines.append(f"  {identifiers[node['symbol']]} [label={_dot(label)}];")
            for edge in selected_edges:
                lines.append(f"  {identifiers[edge['from']]} -> {identifiers[edge['to']]} [label={_dot(edge.get('kind'))}];")
            lines.append(f"  graph [tooltip={_dot(str(freshness) + "; truncated=" + str(truncated).lower())}];")
            lines.append("}")
        else:
            lines = ["Recorded Kconfig expression evidence", "",
                     f"Image: {_markdown(selected_image)}. Freshness: {_markdown(freshness)}.", "",
                     "| Symbol | Evaluated value | Visibility |", "| --- | --- | --- |"]
            lines.extend(f"| {_markdown(n['symbol'])} | {_markdown(n.get('value'), 30)} | {_markdown(n.get('visibility'), 30)} |"
                         for n in selected)
            lines.extend(["", "Expression references preserve AND/OR/NOT semantics in the source report; an edge alone is not a required change."])
    else:
        findings = report.get("findings", [])
        if not isinstance(findings, list) or len(findings) > 5000:
            raise InputError("Invalid or oversized findings")
        if any(not isinstance(row, dict) or not isinstance(row.get("id"), str) for row in findings):
            raise InputError("Finding identifiers are required")
        if len({row["id"] for row in findings}) != len(findings):
            raise InputError("Duplicate finding identifier")
        selected = sorted(findings, key=lambda row: row["id"])[:max_findings]
        result.update(displayed=len(selected), total=len(findings), truncated=len(selected) < len(findings))
        if format == "dot":
            lines = ["digraph configuration {", '  graph [label="Recorded findings and cited artifact hashes"];', "  rankdir=TB;"]
            hashes = {}
            for index, finding in enumerate(selected):
                label = _plain(finding["id"]) + " (" + _plain(finding.get("confidence"), 20) + ")"
                lines.append(f"  f{index} [label={_dot(label)}];")
                evidence = finding.get("evidence", [])
                if not isinstance(evidence, list) or len(evidence) > 20000:
                    raise InputError("Invalid citation collection")
                for source in evidence:
                    sha = source.get("sha256") if isinstance(source, dict) else None
                    if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{64}", sha):
                        continue
                    if sha not in hashes:
                        if len(hashes) >= 500:
                            result["truncated"] = True
                            break
                        hashes[sha] = "a" + str(len(hashes))
                        lines.append(f"  {hashes[sha]} [shape=box,label={_dot('SHA-256 ' + sha)}];")
                    lines.append(f"  f{index} -> {hashes[sha]} [label=\"cites\"];")
                    if len(hashes) >= 500:
                        result["truncated"] = True
                        break
            lines.append("}")
        else:
            lines = ["Recorded configuration evidence", "",
                     f"Freshness: {_markdown(state)}. Report: `{digest}`.", "",
                     "| Finding | Image | Severity | Confidence | Observation |", "| --- | --- | --- | --- | --- |"]
            lines.extend(f"| {_markdown(f['id'])} | {_markdown(f.get('image'), 60)} | {_markdown(f.get('severity'), 20)} | {_markdown(f.get('confidence'), 20)} | {_markdown(f.get('message'))} |"
                         for f in selected)
            if not selected:
                lines.extend(["", "No recorded findings. This does not prove a valid build or working hardware."])
    if format == "markdown":
        lines.extend(["", f"Displayed {result['displayed']} of {result['total']}. Truncated: {'yes' if result['truncated'] else 'no'}.", "",
                      "Source freshness, runtime use and physical pin correctness require separate evidence."])
    result["content"] = "\n".join(lines) + "\n"
    return result
