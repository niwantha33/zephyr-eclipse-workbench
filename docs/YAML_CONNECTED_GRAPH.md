# YAML Connected-Block Visualizer (Eclipse RCP)

The original **Zephyr YAML Structure** view was a source tree/table with
binding, HWMv2, Twister and diagnostic inspectors. The **Connected blocks**
tab presents the same *observed source syntax* as a left-to-right, scrollable
graph in native SWT. It is the default tab; **Source outline** preserves the
original four-column view and scalar-edit selection.

## Use

1. In Project Explorer, expand `Build Outputs` and select a generated YAML
   such as `build_info.yml` or `zephyr/runners.yaml`, or a project-owned
   `.yaml` / `.yml` file.
2. Open **Window → Show View → Other → Zephyr YAML Structure (Read-only)**.
3. Click **Inspect selected YAML** if the selection is not already following.
4. Use **Connected blocks** for coloured map/list/scalar cards and curved
   parent-child connectors. Use the horizontal/vertical scrollbars, zoom
   controls (+/−/100%), or Ctrl+mouse-wheel. Double-click a block to navigate
   to the observed source line when a verified, saved source is selected.
5. Switch to **Source outline** for exact key/value positions, scalar editing
   (only user-owned application YAML), and the existing evidence tabs.

Generated YAML is read-only by policy: modify `prj.conf`, `app.overlay`,
Kconfig/DTS inputs or the appropriate owned source instead.

## Truth and security boundaries

- Connections are **ONLY observed YAML syntax nesting** (mapping keys and
  sequence indices). They are **not** Zephyr dependency, binding, pin, bus,
  peripheral, or build-success relationships. The visualizer does not infer
  hardware wiring, and a parsed YAML file is not a validated Zephyr schema.
- The graph consumes the same bounded, hashed, managed-Python observation as
  the structure tree. No new YAML parser, subprocess or network service is
  introduced. Existing source provenance and background cancellation remain.
- The graph displays at most 180 nodes and 10 child levels. Larger documents
  show an explicit **LIMITED PREVIEW** marker; no omitted nodes or links are
  invented. Layout calculations are Java-only, and SWT painting is confined
  to the UI thread.
- Imported observation JSON has no authenticated editable workspace source:
  graph navigation and editing never imply saved source ownership.
- Source/checkout errors remain fail-closed and now include a short diagnostic
  reason rather than silently showing a blank tree. Do not treat an inspection
  failure as proof that the YAML itself is invalid.

## Validation

`tests/core/YamlFlowGraphTests.java` exercises real graph projection, bounded
layout, source locations and no speculative edges.
`tests/eclipse/YamlStructureSmoke.java` opens the **real Eclipse view** and
requires the connected SWT canvas, two graph/outline tabs, observed nodes,
edges and zoom. Qualify the installed bundle and Windows firmware matrix
before merging. Manual acceptance should select an actual generated
`build_info.yml` after a valid configure/build and confirm that an error
reports its reason if managed PyYAML is unavailable.
