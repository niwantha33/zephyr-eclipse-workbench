# SPDX-License-Identifier: Apache-2.0
"""Classify Zephyr YAML *candidates* from an explicit source-root-relative path.

No filesystem access, YAML parsing, schema validation, or source modification.
The caller must establish which root (Zephyr, module, or application) owns the
relative path. The result is a navigation hint, not proof of Zephyr validity.
"""

from dataclasses import dataclass
from pathlib import PurePath
import re


SOURCE_ROOTS = frozenset({"zephyr", "module", "application", "unknown"})
_TEST_FILENAMES = frozenset({"tests.yaml", "testcase.yaml", "sample.yaml"})
_TWISTER_CONFIG_FILENAMES = frozenset({"test_config.yaml", "twister.yaml"})


@dataclass(frozen=True)
class YamlClassification:
    kind: str
    reason: str


def classify_yaml_path(path: str | PurePath, *, source_root: str = "unknown") -> YamlClassification:
    """Identify a likely Zephyr YAML role without inspecting the file contents.

    ``path`` is relative to an *already selected and verified* source root.
    Absolute, traversing and ambiguous paths are intentionally not classified.
    ``source_root='unknown'`` restricts results to generic YAML.
    Zephyr filenames and path segments are case-sensitive, even on Windows.
    """
    if source_root not in SOURCE_ROOTS:
        raise ValueError("source_root must be zephyr, module, application, or unknown")
    if not isinstance(path, (str, PurePath)):
        raise TypeError("path must be a string or PurePath")

    raw = str(path).replace("\\", "/")
    if (not raw or len(raw) > 4096 or "\x00" in raw or raw.startswith("/")
            or re.match(r"^[A-Za-z]:", raw) or "//" in raw):
        return YamlClassification("unknown", "not-a-safe-root-relative-path")
    parts = raw.split("/")
    if len(parts) > 128 or any(part in {"", ".", ".."} for part in parts):
        return YamlClassification("unknown", "not-a-safe-root-relative-path")

    filename = parts[-1]
    if not filename.lower().endswith((".yaml", ".yml")):
        return YamlClassification("unsupported", "not-a-yaml-file")
    if source_root == "unknown":
        return YamlClassification("generic-yaml", "source-root-unverified")

    # Binding semantics require the selected checkout's edtlib in later steps.
    if len(parts) >= 3 and parts[:2] == ["dts", "bindings"]:
        return YamlClassification("devicetree-binding", "binding-directory-candidate")

    # HWMv2 uses board.yml and soc.yml. A board.yaml is NOT board.yml.
    board_dir = len(parts) == 4 and parts[0] == "boards"
    soc_dir = len(parts) == 4 and parts[0] == "soc"
    if board_dir and filename == "board.yml":
        return YamlClassification("hwmv2-board", "canonical-board-path-candidate")
    if soc_dir and filename == "soc.yml":
        return YamlClassification("hwmv2-soc", "canonical-soc-path-candidate")
    if board_dir and filename == "board.yaml":
        return YamlClassification("board-auxiliary-metadata", "not-hwmv2-board-yml")
    if (board_dir or soc_dir) and filename == "hw_metadata.yaml":
        return YamlClassification("hardware-metadata", "hardware-tree-filename-candidate")

    # Twister files can also belong to a selected standalone application.
    in_tests = parts[0] in {"tests", "samples"} and len(parts) >= 2
    app_root = source_root == "application" and len(parts) == 1
    if in_tests or app_root:
        if filename in _TEST_FILENAMES:
            return YamlClassification("twister-test", "test-filename-candidate")
        if filename in _TWISTER_CONFIG_FILENAMES:
            return YamlClassification("twister-config", "config-filename-candidate")

    return YamlClassification("generic-yaml", "no-specific-role-established")
