<!-- SPDX-License-Identifier: Apache-2.0 -->
# Hardware Studio v0.1 — release candidate / qualification

**Product:** the existing Zephyr Eclipse Workbench. Hardware Studio is an integrated Eclipse ViewPart shipped in the **same p2 update site**; there is no new `.exe`, no standalone repository, no secondary probe connection, no separate installer. RTOS Observatory remains independent.

**Candidate branch:** `feat/hardware-studio-eclipse-hs01` (draft PR #266). **Do not tag, publish, merge automatically or call this production-qualified.** The release commit must be explicitly approved after testing.

## Open and use

1. Update an existing Zephyr Eclipse Workbench using the **p2 artifact from the successful `Build development update site` workflow at the exact reviewed commit**; do not assume the public updates URL is installed or usable.
2. Select a ready Zephyr project and the exact board, SoC, build profile, and generated build.
3. Build the firmware; a real `zephyr/zephyr.dts` and `CMakeCache.txt` are required. In Eclipse open **Zephyr → Configure & Build → Hardware Studio — Devicetree**, or **Configuration Studio → Hardware → Hardware Studio**.
4. Search by path/name/compatible/label, filter on observed status, focus nodes, zoom or pan, and inspect read-only binding references. Use **Open generated DTS source** only for a current hash-matched generated file.
5. To return to the native SWT tool, select **Classic Graph Studio**. The original Configuration Studio, SVD, flash, and debug views remain intact.

**Browser prerequisites:** SWT Browser uses the host's native WebView2 on Windows or WebKitGTK on Linux. No browser runtime downloads, network/CDN scripts, secondary Node runtime, or HTTP server are performed on view startup. If absent, Eclipse remains usable with a link to classic Graph Studio.

## Drag-to-arrange graph blocks (UI-only)

- Left-click and drag a visible graph card to reposition that block inside the canvas.
- Parent-child and resolved-reference lines follow every card as its position changes; no DTS relationships are created or deleted.
- Click selects a node; double-click still focuses its neighbourhood.
- Focus a graph card and use **arrow keys** to move it by 20 canvas units, or **Shift+arrow** for 4 units. On the canvas background, drag to pan; Fit and zoom remain available.
- **Reset layout** returns to the original generated positions. The reset control is disabled until a card has moved.
- This changes **only the visual arrangement in the open embedded browser document**. Positions are deliberately not persisted to Devicetree, project properties, source files or repository. They reset on graph refresh, focus/navigation that reloads the document, or closing Hardware Studio.
- The existing browser bridge only permits the same read-only navigation/refresh/theme actions. Dragging adds **no new Eclipse host commands**, file writes, target reads, probe or network access.
- Coordinate changes are clamped to a bounded canvas, and movements are adjusted for zoom.

**Release verification:** CI static HTML/CSP checks and JavaScript parse are required, plus manual installed WebView2/WebKitGTK checks: dragging at 38%, 100% and 160% zoom; connection redraw; pointer capture/release; graph background panning; click/double-click separation; keyboard arrow/Shift+arrow; Reset Layout; focus and refresh reset; small-window scroll and high-DPI scaling. A static fixture or Node parse alone does not prove native pointer input works.

## Meaning of evidence

- `okay` means configured/enabled in Devicetree, **never** physical wiring, driver initialisation or live device activity. `disabled` and unknown/malformed statuses remain distinct.
- Solid edges are confirmed generated-DTS parent/child relationships. Dashed edges are only links verified by the selected Zephyr checkout's dtlib/edtlib/bindings. Unknown bindings do not produce guessed GPIO, clock, reset or pinctrl edges.
- The centre canvas is a bounded focused neighbourhood, **not** the complete chip. The full observed node list remains searchable with a bounded first-page result.
- Current file SHA-256, selected project and profile, build identity and config/overlay freshness are checked; inconsistent evidence blocks navigation. Source output remains read-only and requires an actual build.
- No external hardware, package-specific pads or SVD register data are fabricated. This feature does not open a GDB session.

## CI and artifact gate

- [ ] Final PR head: CodeQL, Linux Tycho/p2 package, pure-Java core, Python parser/asset fixtures, installed Eclipse SWT smoke and Linux native regression **success**.
- [ ] Final PR head: native Windows managed firmware matrix **success**.
- [ ] Integrity check `python scripts/verify_site.py` passes for the **same** downloaded p2 ZIP and all plug-in resources are exact.
- [ ] Installed Eclipse browser actually renders; if WebKitGTK/WebView2 is unavailable and SWT fallback is used, mark the **browser render gate NOT PERFORMED**, even if the fallback smoke passes.
- [ ] Review screenshots from the installed Eclipse CI artifact `zephyr-native-ui-evidence`: `hardware-studio-fixture-1366x768-{dark,light}.png` and `hardware-studio-fixture-1920x1080-{dark,light}.png`. These are explicitly **synthetic layout fixtures**, not evidence that a board was observed.
- [ ] Manually verify actual Windows WebView2, Linux WebKitGTK, 100/125/150% DPI, keyboard Tab/focus/shortcuts, graph card links, search, filters, long paths and scroll within Eclipse (not a browser mock).
- [ ] Select at least two real Zephyr board/profile builds (including MSPM0G3507 and a non-TI target), inspect actual generated DTS SHA and resolved references, then modify an overlay and confirm **stale** state; rebuild, repeat.
- [ ] Regression: Configuration Studio, legacy DTS Graph, Kconfig, SVD, build/flash/debug and RTOS views.
- [ ] Clean Windows and Linux p2 install, restart, upgrade from previous preview, and rollback to prior install in a disposable environment. Never modify a user's working installation as the qualification test.
- [ ] Review release notes, Apache-2.0 notices, dependency licences, checksum of each exact shipped archive, and existing update URL accessibility before publication.
- [ ] Hardware qualification is **NOT PERFORMED** until a physical target is used.

**Publication rule:** all mandatory checklist items require recorded evidence and approval. A successful source build or CI screenshot of a fixture is insufficient to claim a validated hardware product.

## Honest limitations and deferred work

Version 0.1 is a **read-only generated-Devicetree explorer**. It does not offer editable overlay drafts, authoritative pin/package planners, runtime device readings or cross-app causal analysis. Those are HS-10, HS-11 and HS-12, gated separately after the first developer preview.

No real probe measurements or commercial production release certification are implied by this document.
