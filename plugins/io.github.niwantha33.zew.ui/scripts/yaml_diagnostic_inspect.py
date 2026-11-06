#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Step 10: source-linked, bounded, read-only YAML diagnostics.

Syntax evidence comes from Step 3's restricted PyYAML observation. Twister
schema evidence comes ONLY from the selected checkout's scl._yaml_validate
and schema; never infer DTS binding/HWMv2 validity from a successful parse.
No source file writes, build execution, network, or raw exception messages.
"""
from __future__ import annotations

import argparse
import base64
import contextlib
import hashlib
import importlib
import io
from pathlib import Path
import sys

from yaml_observe_file import SourceRejected, read_selected
from yaml_twister_inspect import (
    MAX_API_BYTES, MAX_SCHEMA_BYTES, read_bounded, source_kind,
)
from zew_evidence.yaml_observation import observe_yaml

ZERO = "0" * 64
MAX_LINE = 999999
MAX_DESCRIPTION = 160

REASONS = {
    "yaml-syntax-invalid": "YAML syntax could not be parsed.",
    "duplicate-mapping-key": "A YAML mapping contains a repeated key.",
    "non-string-mapping-key": "The YAML mapping key is not a supported string.",
    "aliases-or-anchors-unsupported": "YAML aliases and anchors are not supported by this inspector.",
    "multiple-documents-unsupported": "Only one YAML document is supported.",
    "maximum-depth-exceeded": "YAML nesting exceeds the inspection limit.",
    "maximum-node-count-exceeded": "The YAML node count exceeds the inspection limit.",
    "maximum-scalar-length-exceeded": "A YAML scalar exceeds the inspection limit.",
    "unsupported-yaml-tag": "This YAML tag is not supported by the safe inspector.",
    "unsupported-yaml-node": "The YAML contains an unsupported node.",
    "invalid-utf8": "The YAML source is not valid UTF-8.",
    "nul-byte-unsupported": "NUL bytes are unsupported in YAML source.",
    "maximum-source-size-exceeded": "The YAML source exceeds the inspection limit.",
    "invalid-unicode-input": "YAML source contains invalid Unicode.",
}

SCHEMA_DESCRIPTIONS = {
    "type": "The selected Twister schema rejects the value type at this location.",
    "required": "A required field is absent in this Twister mapping.",
    "additionalProperties": "The selected Twister schema rejects an additional property in this mapping.",
    "enum": "The selected Twister schema rejects a value outside its allowed choices.",
    "pattern": "The selected Twister schema rejects this value's format.",
    "minItems": "This Twister sequence has too few entries.",
    "maxItems": "This Twister sequence has too many entries.",
    "uniqueItems": "This Twister sequence contains duplicate entries.",
}
_ALLOWED_TAGS = {"str", "int", "float", "bool", "null", "timestamp", "binary"}


class Unavailable(Exception):
    """Expected absence of verified evidence, not a YAML source error."""


def _mark(node):
    if node is None:
        return 0, 0
    mark = getattr(node, "start_mark", node)
    line = getattr(mark, "line", None)
    column = getattr(mark, "column", None)
    if not isinstance(line, int) or not isinstance(column, int):
        return 0, 0
    line, column = line + 1, column + 1
    if not (1 <= line <= MAX_LINE and 1 <= column <= MAX_LINE):
        return 0, 0
    return line, column


def _scan_node(yaml, node, reason, depth=0):
    if node is None or depth > 32:
        return None
    tag = getattr(node, "tag", "")
    if reason == "unsupported-yaml-tag":
        if (not tag.startswith("tag:yaml.org,2002:")
                or tag.rsplit(":", 1)[-1] not in _ALLOWED_TAGS | {"seq", "map"}):
            return node
    if isinstance(node, yaml.nodes.MappingNode):
        seen = set()
        for key, value in node.value:
            if reason == "non-string-mapping-key" and (
                    not isinstance(key, yaml.nodes.ScalarNode)
                    or key.tag != "tag:yaml.org,2002:str"):
                return key
            if isinstance(key, yaml.nodes.ScalarNode):
                if reason == "duplicate-mapping-key" and key.value in seen:
                    return key
                seen.add(key.value)
            found = _scan_node(yaml, value, reason, depth + 1)
            if found is not None:
                return found
    if isinstance(node, yaml.nodes.SequenceNode):
        for item in node.value:
            found = _scan_node(yaml, item, reason, depth + 1)
            if found is not None:
                return found
    return None


def _syntax_location(raw, reason):
    try:
        import yaml
        text = raw.decode("utf-8-sig", "strict")
        for event in yaml.parse(text, Loader=yaml.SafeLoader):
            if reason == "aliases-or-anchors-unsupported" and (
                    isinstance(event, yaml.events.AliasEvent)
                    or getattr(event, "anchor", None)):
                return _mark(event)
            if reason == "maximum-scalar-length-exceeded" and (
                    isinstance(event, yaml.events.ScalarEvent)
                    and len(event.value) > 16384):
                return _mark(event)
        node = yaml.compose(text, Loader=yaml.SafeLoader)
        return _mark(_scan_node(yaml, node, reason))
    except Exception as error:
        try:
            import yaml
            if isinstance(error, yaml.YAMLError):
                return _mark(getattr(error, "problem_mark", None)
                             or getattr(error, "context_mark", None))
        except ImportError:
            pass
        return 0, 0


def _node_for_path(yaml, node, path):
    """Return the last verified key/item for a JSON Schema instance path."""
    current = node
    location = node
    for component in path:
        if isinstance(current, yaml.nodes.MappingNode) and isinstance(component, str):
            match = next(((key, value) for key, value in current.value
                          if isinstance(key, yaml.nodes.ScalarNode)
                          and key.value == component), None)
            if match is None:
                break
            location, current = match[0], match[1]
        elif isinstance(current, yaml.nodes.SequenceNode) and isinstance(component, int):
            if component < 0 or component >= len(current.value):
                break
            current = current.value[component]
            location = current
        else:
            break
    return _mark(location)


def _schema_result(raw, kind, root, relative, source_root, zephyr, app):
    """Schema evidence only from pinned Twister scl; errors are never echoed."""
    import yaml
    scl_file = zephyr / "scripts/pylib/twister/scl.py"
    schema_file = (zephyr / "scripts/schemas/twister"
                   / ("testsuite-schema.yaml" if kind == "testsuite"
                      else "test-config-schema.yaml"))
    try:
        api = read_bounded(scl_file, MAX_API_BYTES)
        schema_bytes = read_bounded(schema_file, MAX_SCHEMA_BYTES)
        schema = yaml.safe_load(schema_bytes)
        data = yaml.safe_load(raw)
        if not isinstance(schema, dict) or not isinstance(data, dict):
            raise Unavailable("selected-twister-schema-unavailable")
        api_sha, schema_sha = hashlib.sha256(api).hexdigest(), hashlib.sha256(schema_bytes).hexdigest()
        search = zephyr / "scripts/pylib/twister"
        old_path = sys.path[:]
        old_scl = sys.modules.pop("scl", None)
        try:
            sys.path.insert(0, str(search))
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                scl = importlib.import_module("scl")
                if Path(scl.__file__).resolve(strict=True) != scl_file.resolve(strict=True):
                    raise Unavailable("selected-twister-validator-unverified")
                validator = getattr(scl, "_yaml_validate", None)
                if not callable(validator):
                    raise Unavailable("selected-twister-validator-unavailable")
                try:
                    validator(data, schema)
                    error = None
                except Exception as exc:
                    try:
                        from jsonschema.exceptions import ValidationError
                    except ImportError:
                        raise Unavailable("twister-jsonschema-unavailable") from exc
                    if not isinstance(exc, ValidationError):
                        raise Unavailable("selected-twister-validation-unavailable") from exc
                    error = exc
        finally:
            sys.path[:] = old_path
            sys.modules.pop("scl", None)
            if old_scl is not None:
                sys.modules["scl"] = old_scl

        if (hashlib.sha256(read_bounded(scl_file, MAX_API_BYTES)).hexdigest() != api_sha
                or hashlib.sha256(read_bounded(schema_file, MAX_SCHEMA_BYTES)).hexdigest() != schema_sha
                or hashlib.sha256(read_selected(str(root), relative, source_root)).hexdigest()
                != hashlib.sha256(raw).hexdigest()):
            raise Unavailable("twister-source-or-validator-changed")
        if error is None:
            return "checked", "twister-schema-valid", []
        validator_name = str(error.validator)
        description = SCHEMA_DESCRIPTIONS.get(validator_name,
                                              "The selected Twister schema rejected this declaration.")
        code = ("schema-" + validator_name.lower()
                if validator_name in SCHEMA_DESCRIPTIONS else "schema-rejected")
        # Location is the known mapping key/sequence element, never guessed
        # from the validator's free-form diagnostic string.
        location = _node_for_path(yaml, yaml.compose(raw.decode("utf-8-sig"), Loader=yaml.SafeLoader),
                                  list(error.absolute_path))
        return "invalid", "twister-schema-rejected", [("error", code, *location, description)]
    except Unavailable:
        raise
    except Exception as error:
        raise Unavailable("selected-twister-validation-unavailable") from error


def inspect(root_text, relative, source_root, zephyr_text, app_text):
    root, zephyr, app = (Path(p).absolute() for p in (root_text, zephyr_text, app_text))
    if source_root not in ("zephyr", "application") or (
            root.resolve(strict=True) != (zephyr if source_root == "zephyr" else app).resolve(strict=True)):
        raise Unavailable("selected-source-root-unverified")
    raw = read_selected(str(root), relative, source_root)
    sha = hashlib.sha256(raw).hexdigest()
    evidence = observe_yaml(raw, path=relative, source_root=source_root)
    status = evidence["status"]
    if status == "unsupported":
        return "unsupported", "safe-parser-does-not-support-source", sha, "none", []
    if status == "unavailable":
        return "unavailable", "safe-yaml-parser-unavailable", sha, "none", []
    if status == "invalid":
        reason = evidence["reason"]
        if reason not in REASONS:
            raise Unavailable("unsupported-diagnostic-reason")
        line, column = _syntax_location(raw, reason)
        return ("invalid", reason, sha, "syntax",
                [("error", "syntax-" + reason, line, column, REASONS[reason])])
    if status != "parsed":
        raise Unavailable("yaml-observation-not-verified")
    kind = source_kind(relative, source_root)
    if kind in ("testsuite", "test-config"):
        state, reason, details = _schema_result(raw, kind, root, relative, source_root, zephyr, app)
        return state, reason, sha, "twister", details
    return "checked", "yaml-syntax-valid", sha, "syntax", []


def _enc(value):
    return base64.b64encode(str(value).encode("utf-8")).decode("ascii")


def emit(report):
    status, reason, sha, scope, diagnostics = report
    print("ZEW-YAMLDIAG-1")
    print("H\t" + "\t".join(map(_enc, (status, reason, sha, scope))))
    for severity, code, line, column, detail in diagnostics[:8]:
        print("D\t" + "\t".join(map(_enc, (severity, code, line, column,
                                            detail[:MAX_DESCRIPTION]))))


def main(argv=None):
    parser = argparse.ArgumentParser(allow_abbrev=False)
    for flag in ("root", "relative", "source-root", "zephyr", "application"):
        parser.add_argument("--" + flag, required=True)
    args = parser.parse_args(argv)
    try:
        report = inspect(args.root, args.relative, args.source_root,
                         args.zephyr, args.application)
    except (Unavailable, SourceRejected) as error:
        report = ("unavailable", str(error), ZERO, "none", [])
    except (Exception, SystemExit, RecursionError):
        report = ("unavailable", "yaml-diagnostics-unavailable", ZERO, "none", [])
    emit(report)


if __name__ == "__main__":
    main()
