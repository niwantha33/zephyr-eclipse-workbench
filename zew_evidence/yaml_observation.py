# SPDX-License-Identifier: Apache-2.0
"""Step 3: bounded, read-only YAML syntax observations for an Eclipse consumer.

The caller supplies an in-memory snapshot and a verified source-root-relative
path. This module performs no file I/O, applies no Zephyr schema, and writes
nothing. A parsed document does NOT prove a valid binding, board or Twister
scenario. Source ranges are 1-based and the SHA-256 covers original UTF-8 bytes.

Protocol: zew.yaml-observation/1 with status parsed, invalid, unsupported, or
unavailable; stable machine-readable reason codes; and at most one document.
"""

from __future__ import annotations

import hashlib
from pathlib import PurePath

from .yaml_classification import classify_yaml_path

MAX_SOURCE_BYTES = 256 * 1024
MAX_NODES = 4096
MAX_DEPTH = 32
MAX_SCALAR_CHARS = 16384

_ALLOWED_SCALARS = frozenset({
    "str", "int", "float", "bool", "null", "timestamp", "binary",
})


class _Rejected(Exception):
    """Expected input rejection; never includes the user's YAML text."""


def _range(node):
    return {"start": {"line": node.start_mark.line + 1,
                      "column": node.start_mark.column + 1},
            "end": {"line": node.end_mark.line + 1,
                    "column": node.end_mark.column + 1}}


def _preflight(yaml, text):
    """Enforce limits and prohibit aliases before composing a node graph."""
    depth = count = documents = 0
    for event in yaml.parse(text, Loader=yaml.SafeLoader):
        if isinstance(event, yaml.events.AliasEvent) or getattr(event, "anchor", None):
            raise _Rejected("aliases-or-anchors-unsupported")
        if isinstance(event, yaml.events.DocumentStartEvent):
            documents += 1
            if documents > 1:
                raise _Rejected("multiple-documents-unsupported")
        if isinstance(event, (yaml.events.MappingStartEvent,
                              yaml.events.SequenceStartEvent)):
            depth += 1
            count += 1
            if depth > MAX_DEPTH:
                raise _Rejected("maximum-depth-exceeded")
        elif isinstance(event, (yaml.events.MappingEndEvent,
                                yaml.events.SequenceEndEvent)):
            depth -= 1
        elif isinstance(event, yaml.events.ScalarEvent):
            count += 1
            if len(event.value) > MAX_SCALAR_CHARS:
                raise _Rejected("maximum-scalar-length-exceeded")
        if count > MAX_NODES:
            raise _Rejected("maximum-node-count-exceeded")


def _node(yaml, node):
    """Convert safe YAML nodes into ordered, JSON-compatible source evidence."""
    if isinstance(node, yaml.nodes.ScalarNode):
        tag = node.tag.removeprefix("tag:yaml.org,2002:")
        if node.tag != "tag:yaml.org,2002:" + tag or tag not in _ALLOWED_SCALARS:
            raise _Rejected("unsupported-yaml-tag")
        return {"type": "scalar", "scalar_type": tag,
                "value": node.value, "range": _range(node)}
    if isinstance(node, yaml.nodes.SequenceNode):
        if node.tag != "tag:yaml.org,2002:seq":
            raise _Rejected("unsupported-yaml-tag")
        return {"type": "sequence", "items": [_node(yaml, n) for n in node.value],
                "range": _range(node)}
    if isinstance(node, yaml.nodes.MappingNode):
        if node.tag != "tag:yaml.org,2002:map":
            raise _Rejected("unsupported-yaml-tag")
        entries, keys = [], set()
        for key, value in node.value:
            if not isinstance(key, yaml.nodes.ScalarNode) or key.tag != "tag:yaml.org,2002:str":
                raise _Rejected("non-string-mapping-key")
            if key.value in keys:
                raise _Rejected("duplicate-mapping-key")
            keys.add(key.value)
            entries.append({"key": key.value, "key_range": _range(key),
                            "value": _node(yaml, value)})
        return {"type": "mapping", "entries": entries, "range": _range(node)}
    raise _Rejected("unsupported-yaml-node")


def observe_yaml(source: str | bytes, *, path: str | PurePath,
                 source_root: str = "unknown") -> dict:
    """Inspect bytes without reading a path or instantiating arbitrary tags.

    The result is JSON-serialisable. On errors, no YAML input is echoed in the
    response or in a PyYAML exception. Bytes are decoded strictly as UTF-8.
    Bounds must ALSO be enforced by a future file reader before loading bytes.
    """
    classification = classify_yaml_path(path, source_root=source_root)
    result = {"schema": "zew.yaml-observation/1", "status": "invalid",
              "reason": "", "classification": {
                  "kind": classification.kind, "reason": classification.reason},
              "provenance": {"source_root": source_root,
                             "relative_path": (str(path).replace("\\", "/")
                                               if classification.kind != "unknown" else None),
                             "sha256": None, "size_bytes": None},
              "documents": []}
    if classification.kind in ("unknown", "unsupported"):
        result.update(status="unsupported", reason=classification.reason)
        return result
    if not isinstance(source, (str, bytes)):
        raise TypeError("source must be a str or bytes snapshot")
    try:
        raw = source if isinstance(source, bytes) else source.encode("utf-8", "strict")
    except UnicodeError:
        result["reason"] = "invalid-unicode-input"
        return result
    result["provenance"]["size_bytes"] = len(raw)
    if len(raw) > MAX_SOURCE_BYTES:
        result["reason"] = "maximum-source-size-exceeded"
        return result
    result["provenance"]["sha256"] = hashlib.sha256(raw).hexdigest()
    try:
        text = raw.decode("utf-8-sig", "strict")
    except UnicodeError:
        result["reason"] = "invalid-utf8"
        return result
    if "\x00" in text:
        result["reason"] = "nul-byte-unsupported"
        return result
    try:
        import yaml
    except ImportError:
        result.update(status="unavailable", reason="pyyaml-unavailable")
        return result
    try:
        _preflight(yaml, text)
        root = yaml.compose(text, Loader=yaml.SafeLoader)
        if root is not None:
            result["documents"] = [{"root": _node(yaml, root)}]
        result.update(status="parsed", reason="syntax-observed" if root else "empty-document")
    except _Rejected as error:
        result["reason"] = str(error)
    except (yaml.YAMLError, RecursionError, UnicodeError, ValueError, TypeError):
        result["reason"] = "yaml-syntax-invalid"
    return result
