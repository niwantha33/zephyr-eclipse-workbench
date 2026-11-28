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

## HS-03 exact target identity (issue #267)

Hardware Studio and Classic DTS Graph now pass the selected full board identifier to the bundled observer. The observer checks Zephyr's `CACHED_BOARD` (pre-split target), then `BOARD_TARGET`, and uses `BOARD` only when it exactly equals the request. Missing, duplicate, blank or mismatched target evidence suppresses all nodes, links and pin mappings. This includes ESP32 dual-core qualifier mismatches and avoids guessing a qualified board from a base name. Existing low-level observer calls without an explicit board remain supported for backward-compatible diagnostics, not as exact-target IDE evidence. The selected build-directory/profile, checkout and application checks remain in place. This is a generated-build identity safeguard, not physical package qualification; actual Windows/Linux installed UI and real board tests remain required. No source writes, probe access or external downloads.

## HS-03 tracked-input freshness (issue #267)

Both embedded Hardware Studio views reuse the existing build-local ConfigurationEvidenceReceipt rather than guessing freshness from root overlay timestamps. Current means the recorded board/profile, tracked configuration inputs (including recorded DTS dependency files), outputs and observed DTS hash match. Changed, missing or partial receipts present historical evidence with a separate explanation, not a current hardware claim. Refresh/rebuild to update; generated-source navigation rechecks the selected board/profile and receipt in a background Job before opening the hash-matched generated file. Older builds without a complete receipt remain inspectable but source navigation is disabled until verified. This is a snapshot at inspection, not continuous monitoring or proof that untracked inputs, physical pins or runtime match. Existing receipt limitations and installed browser testing remain; no new source writes or browser commands.

## HS-04 logical ADC/I/O and PWM connections (issue #267)

The observer now preserves binding-resolved `io-channels` and `pwms` controller references, their specifier indices and valid named input/channel/period/flags cells. This uses the selected checkout's edtlib, not numerical phandle guessing. The package/connection view displays these as **logical controller references**, including consumers that do not have peripheral keywords in their names, and offers controller focus for inspecting its independently resolved pinctrl routes. A channel is never assigned to a package pad or matched to a mux merely because numbers coincide. Generic I/O channels are not automatically ADCs. PWM period/flags are raw binding cells, not measured timing or decoded polarity. Unknown, missing or unsupported cells remain unspecified. Existing vendor-specific pinmux support stays unchanged. Fixtures cover multiple vendors' logical presentation; real-board generated DTS and installed browser review remain pending.

## HS-04 bus route state inspection (issue #267)

Hardware Studio's physical-package/connection view now includes a searchable, bounded signal-route table for every board (including those with no supported package diagram). UART, I2C, SPI and CAN routes use the existing selected-checkout decoder results, retain the signal, logical SoC pad and pinctrl state, and link back to the controller. The route filter distinguishes configured default, alternate/sleep, disabled/reserved, unresolved/unknown and historical evidence. Default requires enabled source and target ancestry and current tracked-input evidence; it never means an active runtime mux. Non-default, historical and unresolved routes do not colour the package as current default assignments. Logical channel references remain separate; label-only candidates do not become verified pads. Category names are navigation heuristics, not binding classification. No collision or pin-availability claim is made; physical conflicts and package mapping need separate authoritative metadata and real-board evidence. No new bridge commands, source edits, dependencies or target access. Node fixtures exercise states, disabled ancestors, filtering and historical data; installed browser and real-board validation remain required.

## HS-04 conservative shared default pad detection (issue #267)

The existing Hardware Studio connection view now flags **potential shared default pads** only when two or more distinct enabled peripheral nodes have decoded default pinmux routes to the same logical pad within the same recognized pinctrl controller path, and the tracked-input receipt is current. The TI, STM32, Nordic and ESP32 decoder domains are allowlisted; sleep/alternate states, disabled/reserved ancestry, unknown or historical evidence, GPIO/channel references and label-only candidates are excluded. Repeated routes from one owner do not count as separate owners. Different controller domains are never combined merely because pad text matches. The global summary is independent of search filters; select Potential shared pads to inspect matching rows and navigate to owners. On the supported TI package, matching shared default pads are highlighted. A finding is a review hint, not proven electrical contention or simultaneous runtime activation. Zero findings is not a guarantee of free/safe pins; GPIO sharing, intra-peripheral conflicts, unsupported bindings, cross-domain physical aliasing and untracked configuration remain outside this slice. No new host commands, source edits, network, or physical target access. Fixture coverage exercises all four decoder domains, duplicate owners, alternate/disabled routes and historical evidence. Installed UI and real-board validation remain required.
