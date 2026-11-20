<!-- SPDX-License-Identifier: Apache-2.0 -->
# Hardware Studio 0.1 — candidate release notes (NOT PUBLISHED)

Hardware Studio is a new graphical **read-only Devicetree explorer** integrated in the existing Zephyr Eclipse Workbench update.

### Included
- Locally bundled dark/light responsive three-pane Eclipse webview with native browser-unavailable fallback.
- Searchable observed nodes, exact statuses and keyword categories; connected topology with hierarchy and binding-resolved edges only.
- Keyboard-accessible help, navigation, zoom/fit, pan and focused graph; inspected node path, status, compatible, source line, and verified references.
- Selection and SHA-backed source navigation from the exact project, board/profile and generated DTS. CMake application/Zephyr checkout mismatch and stale configuration detection.
- Existing Configuration Studio/DTS Graph retained; no independent executable, new installer, network/CDN dependency or new debugger connection.
- Python/parser, pure Java, installed SWT browser and offline asset tests; Windows and Linux CI/p2 package checks.

### Known constraints
- This is generated *configuration evidence*, **not** live hardware, physical wiring or an enabled driver claim.
- The graph displays a bounded neighbourhood; unsupported bindings have no synthetic links.
- Browser availability depends on WebView2 on Windows or WebKitGTK on Linux; fallback opens classic Graph Studio.
- Snapshot source navigation requires hash-matched, non-stale generated DTS.
- Overlay editing, package-accurate pin placement and RTOS Observatory handoff are outside the v0.1 scope.

### Before release
Final exact-commit p2 and CI, real target build/snapshot validation, native UI screenshots/DPI and Windows+Linux upgrade/rollback must pass; never publish this candidate otherwise. Hardware testing: **NOT PERFORMED**.

See [qualification checklist](../../docs/HARDWARE_STUDIO_RELEASE_READINESS.md).
