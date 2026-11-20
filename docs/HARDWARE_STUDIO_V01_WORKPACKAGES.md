<!-- SPDX-License-Identifier: Apache-2.0 -->
# Hardware Studio — Eclipse-first v0.1 work packages

**Decision (19 November 2026):** Hardware Studio is a first-class view inside Zephyr Eclipse Workbench, not a separate repository, standalone executable or Tauri installer. The existing Workbench p2 update is the only distribution channel. RTOS Observatory remains independent for dynamic RTOS analysis.

**Release target:** Developer-preview v0.1 by **30 November 2026**, conditional on all release gates. Source presence, clean compilation and installed-Eclipse operation are separate requirements.

## User experience

- Top bar: title, exact selected project/build, evidence state, refresh and theme.
- Left: searchable Devicetree navigation, enabled/disabled filters, names and paths.
- Centre: modern hardware graph with fit/zoom/pan. Solid lines mean parent/child; dashed lines mean upstream-resolved references. Only a bounded local neighborhood appears.
- Right: source-backed inspector, properties, node status, references and trusted source navigation.
- Empty states: initial/no build, loading, built, hierarchy-only, stale, unavailable and missing browser runtime.
- Dark and light themes, keyboard focus and multiple resolutions/DPI are mandatory for release.
- Graph links are configuration evidence only, not physical wiring, package pads, driver or running-MCU status.

## Engineering boundary

The existing Eclipse UI plugin owns the ViewPart, command and menu. Locally bundled, offline HTML/CSS/JS renders inside SWT Browser for the first vertical slice; no CDN, separate Node runtime or Tauri packaging. Reuse existing DtsGraphLoader, DtsGraphModel, ZephyrViewContextHub and the selected checkout's Zephyr dtlib/edtlib. A strict JSON encoder moves evidence one way; browser actions are tightly allowlisted to existing nodes. Source navigation rechecks generated DTS digest, project containment and staleness. A native SWT fallback remains available when browser support is missing.

React + TypeScript + React Flow can replace the renderer within the same Eclipse view in HS-02 only if pinned offline bundle builds and Windows WebView2/Linux WebKitGTK qualification succeed. No independent repo/app is authorized.

## Sequential packages

| Package | Target | Deliverable | Gate |
|---|---|---|---|
| HS-01 | Nov 19–20 | Eclipse view, bundled offline graphical shell, selected-build loader, strict evidence bridge, fallback and menu | JDK core, Tycho/p2, bundle integrity, installed SWT smoke |
| HS-02 | Nov 20–21 | Visual polish, responsive three panes, accessible dark/light, keyboard | Actual screenshots at 1366×768/1920×1080 and high DPI |
| HS-03 | Nov 22–23 | Evidence parser identity, stale/partial semantics and fixtures | Cross-board golden test, zero invented links |
| HS-04 | Nov 24–25 | Semantic graph navigation, focus, pan, zoom, node selections | Bounded focus, verified links, large snapshot responsiveness |
| HS-05 | Nov 26 | Inspector, source navigation and diagnostics | Exact selected build, SHA, no writes |
| HS-06 | Nov 27 | Search/filter/grouping, accessibility, performance | Keyboard and no-result states, bounded large tree |
| HS-07 | Nov 28 | Configuration Studio one-click context sync and migration | No regressions to classic DTS Graph/SVD/Debug |
| HS-08 | Nov 29 | Existing p2 installation/update and licensing gates | Clean Eclipse install, restart, upgrade and rollback tests |
| HS-09 | Nov 30 | Developer-preview v0.1 release decision | All blocking CI and installed smoke green on exact commit |
| HS-10+ | Later | Safe overlays, qualified pin/package view and Observatory handoff | Approval, verified vendor evidence, runtime identity |

One package per scoped PR where feasible; do not merge before required tests. Hardware testing may be deferred but must never be marked passed.

## HS-01 source landmarks

- Embedded view: HardwareStudioView.java; menu: HardwareStudioHandler.java
- Exact JSON data serializer: HardwareStudioDocument.java
- Offline browser resource: hardware-studio/index.html in the existing UI bundle
- Existing authoritative observer: scripts/dts_graph_observe.py and DtsGraphModel/Loader
- Tests: HardwareStudioDocumentTests, HardwareStudioSmoke and p2 verify_site.py
- Legacy DTS Graph Studio remains intact and can be opened from the new view.

Open from Zephyr → Configure & Build → Hardware Studio — Devicetree, or the primary Configuration Studio Hardware tab.

## Implementation snapshot (source changes staged on draft PR #266)

- **HS-01**: Eclipse view/command, offline webview, exact build-selection bus, fallback and bundled parser implemented; baseline Linux and Windows CI passed before later release-candidate changes.
- **HS-02**: polished responsive three-pane light/dark UI, readable error states, keyboard help and test-only installed screenshot capture at 1366×768 / 1920×1080. Screen capture and manual DPI review remain release qualification gates.
- **HS-03**: exact Zephyr checkout, CMake application-source cache identity, generated DTS SHA-256, stale overlay/config detection and safe partial-evidence semantics implemented.
- **HS-04–06**: parent-child/reference graph, zoom/fit/drag and keyboard pan, read-only evidence inspector, guarded source jump, text search, keyword categories, enabled/disabled status and capped navigator. Large target-specific regression qualification pending.
- **HS-07**: Configuration Studio integration and return to classic DTS Graph preserved. Regression smoke on installed workbench included.
- **HS-08–09**: p2 packaging, CI, integrity/smoke tests and release documentation prepared; **candidate only until exact-head CI, real board, screenshots, upgrade/rollback and reviewer gates pass**.
- **HS-10–12**: intentionally postponed beyond v0.1; editing overlays, pin/planner authority and RTOS Observatory links are NOT shipped or claimed.

The screenshot fixture is visibly marked **CI FIXTURE — NO REAL BOARD OR PROBE** and is evidence of rendering only, not actual silicon support.

## Release gates

- [ ] Linux/Windows Java core and Tycho/p2 build pass at the exact PR head.
- [ ] Installed Eclipse smoke opens view and handles missing browser runtime safely.
- [ ] A real generated DTS with exact SHA and real typed reference renders.
- [ ] Stale/unknown/disabled states are correctly represented.
- [ ] All visible controls work and don't trigger target operations or network requests.
- [ ] Real screenshots at both resolutions, dark/light and high DPI reviewed.
- [ ] Existing build/flash/debug/configuration/RTOS features do not regress.
- [ ] Clean first installation, restart and update via existing p2 are verified.
- [ ] Release notes, licensing, version, checksum, known limits accurate.

If gates fail by November 30, keep development-preview status and do not publish an unqualified release.
