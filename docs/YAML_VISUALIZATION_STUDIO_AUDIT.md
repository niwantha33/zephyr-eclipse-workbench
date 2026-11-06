<!-- SPDX-License-Identifier: Apache-2.0 -->
# YAML Visualization Studio — Step 1 source audit and integration contract

**Status:** design/audit only; no YAML editor, parser API, or Eclipse registration is
added by this step. **Branch:** `feature/yaml-visualization`.
**Source baseline:** `main` at `304f898cfca14c37f42a9aebab1df1e5972281c7`
(2026-11-06). This is not evidence of a new qualified release or of passing G0.

## User problem and scope

Zephyr uses YAML for Devicetree bindings, Hardware Model v2 board/SoC
metadata, and Twister tests and samples. Today, users can inspect selected
information through other workbench features, but cannot open an arbitrary
YAML document as a structured, source-linked graphical outline in this plugin.

The requested feature is an evidence-aware **YAML inspection surface** in the
existing Eclipse workflow, not a parallel configuration system. Initially it
must not edit source, configure firmware, or claim an upstream schema passed.

## Existing implementation: reuse, do not duplicate

| Existing code | Observed capability | Boundary |
| --- | --- | --- |
| `plugins/io.github.niwantha33.zew.ui/plugin.xml` | Registers the native `DevicetreeEditor` for `overlay,dts,dtsi` | No YAML editor/outline registration |
| `plugins/io.github.niwantha33.zew.ui/META-INF/MANIFEST.MF` | Declares existing Eclipse UI/CDT/OSGi dependencies | No dedicated Java YAML library dependency |
| `plugins/io.github.niwantha33.zew.ui/scripts/dt_editor_index.py` | Uses the selected checkout's upstream `edtlib.bindings_from_paths` and `prop2specs` | Binding-specific DTS assist, not generic YAML parsing or editing |
| `plugins/io.github.niwantha33.zew.core/src/io/github/niwantha33/zew/core/DevicetreeBindingInspector.java` | Bounded read-only bridge to selected Zephyr `edtlib` | Does not expose a YAML document tree |
| `plugins/io.github.niwantha33.zew.ui/tools/hardware_catalog.py` | Uses Zephyr's hardware discovery APIs, including HWMv2 board/SoC data | Does not visualise individual source YAML documents |
| `plugins/io.github.niwantha33.zew.ui/tools/project_versions.py` | Reads frozen West manifests through PyYAML `BaseLoader` to preserve SHA-like strings | Purpose-specific; not a safe general-purpose editor loader |
| `zew_evidence/studio_images.py` | Uses a bounded PyYAML `SafeLoader` variant; rejects aliases/anchors and duplicate keys for generated `domains.yaml` | Sysbuild-only model; do not treat its parser as a universal schema |
| `zew_evidence/studio_compare.py` | Reads selected `build_info.yml` scalars with a restricted PyYAML loader | No general-purpose hierarchy, location model, or user file editing |
| `tests/tools/test_studio_images.py`, `tests/tools/test_dt_editor_index.py` | Existing negative-input and DTS index test patterns | No YAML visualization fixtures or UI wiring tests |

The project already installs PyYAML for relevant managed/CI workflows (see
`.github/workflows/build.yml`), and bundles Python tools in the UI plugin
via `build.properties`. Future Python integration must work under the
selected managed Python and with packaged plugin resources, not only in a
source checkout. Where the same evidence module is bundled and developed at
the repository root, packaging identity must be checked.

## Gap classification on this baseline

| Requested capability | Step 1 finding |
| --- | --- |
| General read-only YAML tree/outline | Missing |
| Structured Devicetree binding YAML editor | Missing; upstream binding semantics are already consumed elsewhere |
| HWMv2 `board.yaml`, `soc.yaml`, `hw_metadata.yaml` viewer | Missing; upstream board/SoC discovery is present |
| Twister `testcase.yaml`, `twister.yaml`, `sample.yaml` viewer | Missing |
| Source-linked YAML validation markers | Missing as a dedicated YAML workflow |
| Previewed visual YAML editing | Missing; defer until inspection and validation are qualified |

A source-level absence does not prove that no external Eclipse YAML plugin
could be installed separately by a user. This audit concerns this repository.

## Proposed architecture for subsequent approved steps

1. **Detection:** classify `.yaml` and `.yml` from the selected file's exact
   path and context. Prefer Devicetree bindings under `dts/bindings`, then
   recognised HWMv2/Twister file names, then generic YAML. Ambiguous matches
   are `unknown`, not an invented schema; never classify by basename alone
   when an upstream path or project context is necessary.
2. **Parsing:** prefer the existing managed PyYAML environment; use a bounded,
   read-only subprocess, safe/restricted loading, duplicate-key rejection,
   bounded depth/node count and predictable error codes. Explicitly decide how
   anchors, tags and multiple documents are handled before implementation.
   No arbitrary constructors, network fetches, shell interpolation or
   user-provided Python imports.
3. **Transport/model:** introduce a documented versioned JSON observation
   containing document kind, ordered node hierarchy, stable source ranges,
   file content SHA-256, parser/checkout identity when known, and explicit
   `parsed`, `unsupported`, `unknown`, or `invalid` findings. A valid YAML
   syntax tree **does not** establish Zephyr schema validity.
4. **Eclipse surface:** use the current plugin structure and SWT/JFace
   selection/navigation conventions, background Jobs, cancellation and
   source-file navigation. Do not parse on the SWT UI thread or build a second
   complex Configuration Studio. Generated build files remain read-only.
5. **Domain validation:** for Devicetree bindings, defer semantic claims to
   upstream `edtlib` and the exact selected Zephyr checkout; for HWMv2 use
   Zephyr's hardware APIs; for Twister use relevant upstream validation
   rules/tools when available. If the checkout/rules are unavailable, show
   that explicitly. Do not copy today's schemas into a fragile custom parser.
6. **Source safety:** only user-owned source files may become editable in a
   later approved step. Preserve original comments/formatting or show a
   faithful patch; preview before saving, reject changed-on-disk input, use
   backup/atomic-write safeguards, and require explicit user action.

## Proposed test contract (not yet implemented)

- Document classification: exact binding paths, HWMv2/Twister filenames,
  mixed-case/ambiguous names, generic `.yml`, and unsupported extensions.
- Parsing: maps, sequences, scalars, quoting, UTF-8, multiline strings,
  empty files, nested structures, comments/source ranges, duplicate keys,
  aliases, unsafe tags, malformed text, oversized/deep/huge inputs.
- Evidence: source change after load, missing checkout, unknown schema,
  generated vs user-owned files, and simultaneous project/build selections.
- Eclipse: inspect/open/refresh/navigation, keyboard focus, no UI blocking,
  cancellation, paths with spaces, installable p2 packaging and source
  integrity checks. No firmware/hardware claim from parsing or a CI fixture.

## Step 1 acceptance and next gate

This step changes only this audit document. It intentionally does **not**
register an editor, add a parser, add dependencies, change settings, or edit
user files. Check that each named source path exists at the pinned baseline
and that the plugin XML lacks a YAML editor registration. No code build or
physical board run is required to validate this documentation-only change.

**Next approval gate — Step 2:** implement only an independently tested
Zephyr YAML file-type classifier. Do not begin Step 3 or any UI changes
without a separate approval.
