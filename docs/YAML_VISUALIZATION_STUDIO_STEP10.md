<!-- SPDX-License-Identifier: Apache-2.0 -->
# YAML Visualization Studio — Step 10: source-linked diagnostics

**Scope:** strictly read-only YAML syntax diagnostics and *selected-checkout*
Twister JSON Schema diagnostics, with scoped transient Eclipse problem markers.
This is **not** a full Zephyr binding/HWMv2 diagnostic engine and never
represents syntax success as a build, hardware or schema success.

## Use the Diagnostics tab

1. Select a managed Zephyr application and its pinned checkout. Open
   **Window → Show View → Other… → Zephyr YAML Structure (Read-only)**.
2. Select a saved `.yaml` or `.yml` source in Project Explorer. The
   **Diagnostics** tab below the YAML structure tree now displays **Severity**,
   **Reason**, **Line:column** and a safe **Explanation**.
3. For malformed YAML, duplicate mapping keys, unsupported tags/aliases,
   missing required Twister fields or other selected-schema rejection, the
   view displays a finding with its closest verifiable source position.
4. Double-click a finding with a line and column to open the origin file.
   This reuses the Step 6 source hash/identity checks and rejects dirty
   editor buffers. Save changed source and select **Refresh source**.
5. Located errors on a verified workspace `IFile` also appear as temporary
   YAML-specific problem markers in Eclipse Problems and editor annotations.
   They are replaced on refresh and removed when another source is selected,
   an imported report replaces the source, or the view is disposed.

A syntax error with no trustworthy source mark stays in the Diagnostics tab
at **Location unavailable** and is **not** assigned an invented editor line.
A saved `zew.yaml-observation/1` JSON import does not prove an authenticated
workspace source and therefore **cannot** publish source markers.

## Evidence and limits

- `scripts/yaml_diagnostic_inspect.py` reads an independently verified,
  root-relative saved file (256 KiB maximum), then uses the same bounded
  Step 3 YAML syntax observation. Syntax problems have stable reason codes,
  sanitized explanations and a verified PyYAML mark when available.
- Only canonical `tests/.../testcase.yaml`, `samples/.../sample.yaml`,
  selected application-root sample/testcase files, and supported
  `test_config.yaml` files are checked against a domain schema. This uses
  the **selected Zephyr checkout's** own
  `scripts/pylib/twister/scl.py::_yaml_validate` and its matching
  `scripts/schemas/twister` JSON Schema. Schema error `absolute_path`
  is mapped to the closest known original YAML key/element. No free-form
  upstream error or source content is echoed into diagnostics.
- If upstream Twister validation cannot run or is inconsistent, its state
  is **unavailable**, not a fabricated schema error or a valid result.
  Generic YAML and selected Devicetree binding or HWMv2 YAML receive
  **syntax inspection only** here; their existing domain-specific tabs
  remain separate, and no guessed domain problem marker is emitted.
- The Java 21 `YamlDiagnosticModel` validates a strict, finite
  `ZEW-YAMLDIAG-1` protocol (maximum 8 findings, 64-KiB report), compares
  the source SHA-256 with Step 6's observation and rehashes saved bytes
  before accepting results. It runs the managed Python process on a
  background Job with a 25-second timeout and cancellation.
- Only **verified, source-located** findings may be published as Eclipse
  `io.github.niwantha33.zew.ui.yamlProblem` markers. They are
  nonpersistent, have a separate marker type and never delete or replace
  CDT, build, Configuration Studio or unrelated markers. Workspace marker
  changes use an Eclipse workspace rule in a background Job.
- The workflow neither mutates source YAML nor runs Twister, configures
  Kconfig, reads a generated DTS, compiles firmware, flashes a board or
  asserts test execution status. Unsaved editor changes are **not** the
  inspected input.

## Qualification

```sh
python -m unittest discover -s tests/tools -p test_yaml_diagnostic_inspect.py -v
python scripts/test_core.py
```

The Python fixture uses a **test-only** selected-checkout Twister validation
module with JSON Schema Draft 2020-12. The tests check exact YAML source
positions, duplicate keys, invalid YAML, Twister schema type/additional
property/required failures, generic syntax-only status, root and symlink
rejection, source integrity, sanitized error messages and missing validator
behavior. Java headless tests check strict protocol bounds, provenance,
immutability, unknown-location treatment, process cancellation and live
managed-Python syntax inspection.

A green test does not replace an installed Eclipse UI/manual qualification:
verify annotation visibility, Problems view markers, clearing on refresh and
source switching, clean-file navigation, no markers from imported JSON,
and no interference with existing build diagnostics. Confirm real pinned
Zephyr Twister schemas and a Windows managed checkout in addition to
exact-head CI (Windows, Tycho/p2, native UI smoke). The marker class itself
requires an Eclipse workspace to exercise and is not covered by pure-Java
tests.

**Stopped before Step 11:** no controlled YAML editing, patch preview,
source rewriting or configuration mutation is implemented in this step.
