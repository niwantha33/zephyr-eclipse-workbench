# Smart view binding — first native increment

This is a development implementation, not verified hardware behaviour.

## Selection contract
The window-local `ZephyrViewContextHub` transports an imported Eclipse project,
a canonical selected build directory, a typed exact Devicetree node path (or
blank for build-only selection), and the view that initiated navigation.
It does **not** convey live runtime state, proven pin assignment, SVD identity
or a register base address.

The Configuration Studio hardware node table publishes a selection from its
selected build. DTS Graph publishes when opened and when a verified graph node
is clicked or focused. An open graph follows incoming node selections **only
for the exact same project AND selected build**. The Hardware Register Explorer
follows the selected project/build and starts its existing local exact-device
SVD discovery process when the target context changes. It does not guess an SVD
peripheral from the DTS path and does not automatically read any registers.

Both graph and Register Explorer offer **Pin Selection**. The graph also offers
**Follow Selection**. Pinning prevents unrelated views from changing their
selection, and unpinning re-enables subsequent navigation.

## Boundaries and follow-up
- Building, flashing, debugger selection and debugger session ownership are unchanged.
- This does not replace the Debug perspective, change the active CDT frame, or
  open a second GDB/OpenOCD connection.
- SVD matching, MMIO access and register reads retain their current explicit,
  fail-closed rules. A selected DTS node is not proof of a physical peripheral.
- Cross-build selections never change the currently displayed graph implicitly;
  open the new build explicitly from Configuration Studio.
- Selection events are synchronous on Eclipse's UI thread; subscriptions are
  removed when views are disposed. No background polling is introduced.

## Validation
- `ZephyrSelectionIdentityTests` checks canonical path matching, profile isolation,
  project isolation and rejection of malformed DTS paths.
- Still required before production: Tycho compilation, packaged UI smoke, Windows
  target/profile switching, and physical-target integration checks.

## Second increment — explicit DTS-to-debug and memory navigation
From a selected node in DTS Graph, **Open Hardware Registers** opens the
existing SVD-backed view for that project and build. It intentionally does not
claim the node address equals any SVD base or issue an MMIO read. Real register
reads still require an exact SVD and explicit user action in a halted session.

**Open SoC Memory Map** uses the selected graph's generated DTS only if it
remains inside the exact project/build directory and its SHA-256 matches the
previous graph observation. The map is configuration evidence, **not** target
memory contents. If bytes changed, refresh the graph instead of showing stale
cross-view data.

Installed SWT smoke asserts both new actions are initially disabled without
an exact project/build. Real target integration and native toolbar acceptance
remain required.
