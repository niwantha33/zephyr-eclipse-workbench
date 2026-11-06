<!-- SPDX-License-Identifier: Apache-2.0 -->
# YAML Visualization Studio — Step 8: Hardware Model v2

**Scope:** read-only board/SoC hierarchy from the **selected pinned Zephyr
checkout** using Zephyr's HWMv2 resolver modules. It does not change any YAML,
create an editor, build firmware, infer actual board selection or probe a CPU.

## Using the native view

1. Select a managed Zephyr application, with its exact checkout configured in
   **Project Properties → Zephyr**.
2. Open **Window → Show View → Other… → Zephyr YAML Structure (Read-only)**.
3. Select a workspace YAML source at a canonical HWMv2 path:
   `boards/<vendor>/<board>/board.yml` or `soc/<vendor>/<soc>/soc.yml`
   (nested source roots are allowed under the selected application/checkout).
4. The main tree continues to display the source YAML syntax and source
   line/column positions. In the **Hardware Model v2** tab, inspect a
   separate hierarchy resolved through the selected checkout:
   - board name, full name, vendor and revision format/default/exact policy;
   - individually declared revision names and effective *qualifier suffixes*
     (not a claim about the active build target);
   - related SoCs, series, families and CPU clusters;
   - a SoC file may contain multiple SoCs; all matching resolved records
     are shown, rather than assuming one SoC per YAML.
5. Save changes and click **Refresh source** after editing YAML or switching
   checkouts. Imported Step 5 JSON reports alone have no verified project
   source context and therefore cannot claim resolved HWMv2 semantics.

The Devicetree binding inspector remains on its own neighbouring tab. Other
YAML types (including `board.yaml`) are **not** promoted to HWMv2. Missing
or ambiguous metadata, missing upstream scripts, resolver errors and files
that exceed conservative limits appear as explicit unavailable states.

## Evidence model and boundaries

- The packaged `scripts/yaml_hwmv2_inspect.py` delegates to the **selected
  checkout's original** `scripts/list_boards.py` and
  `scripts/list_hardware.py`. Its entry points are
  `find_v2_boards`, `board_v2_qualifiers`, `find_v2_systems`,
  and `get_socs`. The workbench does **not** implement a parallel board/SoC
  schema or calculate cluster variants with its own assumptions.
- The selected YAML path/root and contents must pass Step 6 root protection,
  256-KiB maximum file size, no-symlinks policy, and Step 3 safe syntax
  observation. The selected source and HWM resolver API bytes are SHA-256
  checked for changes across resolution. Root-relative origin is matched
  before any result is shown.
- The Java `YamlHardwareModel` consumes a bounded
  `ZEW-HWMV2-1` base64/UTF-8 protocol with explicit status
  `resolved`, `unavailable` or `not-hwmv2`. The source is checked again
  by the Java bridge. The hierarchy rows are immutable and rendered in a
  separate native SWT/JFace TreeViewer.
- Parsing/metadata resolution is asynchronous and cancellable with a 25 s
  subprocess limit. A busy, unsupported or inconsistent checkout may be
  rejected without interrupting the YAML syntax tree.
- Resolution concerns HWMv2 *definitions*, not `BOARD` from a chosen
  build, `zephyr.dts`, Kconfig, enabled drivers, pin mappings, flash status
  or physical chip identity. Revisions and variants are not asserted to
  have matching built images; no changes to source or hardware are made.

## Qualification

```sh
python -m unittest discover -s tests/tools -p test_yaml_hwmv2_inspect.py -v
python scripts/test_core.py
```

The Python tests exercise the import/selection contract using **test-only
stubs** for the upstream HWMv2 API. They do not establish real Zephyr semantic
compatibility. The Java tests cover bounds, malformed protocol, source
provenance, hierarchy order, revision/qualifier/cluster rendering and failure
states.

Before merging, validate against a **real pinned Zephyr checkout**, including
an MSPM0 LaunchPad board, an ESP32-S3 board with two CPU-cluster qualifiers,
a `soc.yml` with family/series, board revisions, multi-board `boards:`,
and an out-of-tree application board. Confirm the installed Eclipse view
works at the PR's exact CI head and that no build identity is inferred.

**Not implemented:** Step 9 Twister YAML visualization, Step 10 diagnostics,
Step 11 visual editing, Step 12 final qualification and packaging work.
