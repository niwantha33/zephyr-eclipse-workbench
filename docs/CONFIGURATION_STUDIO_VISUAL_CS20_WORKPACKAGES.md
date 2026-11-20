<!-- SPDX-License-Identifier: Apache-2.0 -->
# Configuration Studio visual modernisation — CS-20 through CS-26

**Architecture decision, 20 November 2026:** Use the same offline SWT Browser strategy as Hardware Studio, **inside the existing ConfigurationHomeView**. Do not start a second application, build an unreviewed Kconfig solver, or fork the source-editing engine. The SWT tabbed interface remains the fallback and the advanced guided workflow.

## User-facing design

One responsive Eclipse view, four short destinations:

| Destination | User question | Source of truth | Primary action |
| --- | --- | --- | --- |
| Overview | What's configured, and what needs attention? | FocusedConfigurationModel, exact receipt | Explore evidence / build |
| Hardware | Which controller should I configure? | Selected generated Devicetree, effective status | Existing validated guide / Hardware Studio |
| Kconfig | What effective settings did Zephyr resolve? | Generated zephyr/.config | Reviewed prj.conf/Kconfig editor |
| Build & System | Which board/profile/build did I actually select? | ProjectContext and build receipt | Build, profiles, diagnostics |

The inspector shows the selected item. The UI uses the same palette as Hardware Studio: three panes at desktop width, stacked inspector at medium width, compact navigation at narrow width, 10–13 px labels with a restrained 21 px page title, light/dark, native keyboard buttons, and no external fonts or network. The Overview source → resolved build → firmware flow is **conceptual**, not electrical wiring or a verified dependency graph.

## Safety and architecture constraints

- One Eclipse ViewPart and one evidence loader. The UI data serializer is a pure-Java, bounded, script-escaped document that cannot write project files.
- BrowserFunction is an explicit two-string allowlist. No arbitrary URLs, file paths, scripts, compiler commands, or source edits are accepted from HTML.
- Browser navigation is limited to about:blank; the bundled HTML has a restrictive offline Content Security Policy.
- Generated Kconfig and Devicetree are **read-only**. Missing and unverified evidence remains missing/unverified; absent CONFIG symbols are not assumed disabled.
- Source edits still use the native preview, backup, scratch configure, stale-file rejection, and explicit save gates.
- Browser/WebView2/WebKitGTK failures fall back to the SWT interface.
- Selected board, profile, DTS and receipt identities come from the existing evidence model. Never infer package pins, peripheral availability, memory usage or runtime state.

## Ordered packages and gates

| ID | Package | Deliverable | Status |
| --- | --- | --- | --- |
| CS-20 | Architecture and navigation | One view, clear task-oriented four-page information architecture | **Implemented on branch** |
| CS-21 | Graphical interface | Offline bundled HTML/CSS/JS, light/dark, responsive cards, inspector, search | **Implemented; screenshot review outstanding** |
| CS-22 | Evidence integration | Actual receipt state, bounded generated .config, safe JSON, truthful empty states | **Implemented; cross-board qualification outstanding** |
| CS-23 | Guided editing UX | Visual Kconfig/peripheral diff/validation/backup/apply flow | **Not implemented** — existing guarded SWT editor remains |
| CS-24 | Dependency diagnostics | Symbol origin, select/imply/dependency explanation via upstream Kconfiglib | **Not implemented** — existing diagnostic action remains |
| CS-25 | Hardware Studio handoff | Exact board/profile/node context, safe return, integrated source pane | **Partial** — same build opens in Hardware Studio |
| CS-26 | Release qualification | Core, asset, installed smoke, Windows/Linux p2, high-DPI, upgrades | **Partial; release gates open** |

## Verification

- Run python scripts/test_core.py: the core suite includes a Java escaping/missing-evidence/bounded-setting test and an offline HTML/JS asset test.
- Run python scripts/editor_smoke.py: the installed-Eclipse smoke covers graphical navigation, browser bridge denial and fallback.
- Run python scripts/verify_site.py against the built p2 repository archive: verify exact bundled HTML and evidence bridge.
- Collect genuine screenshots at 1366×768 and 1920×1080 with both themes and high DPI. Test Eclipse view resize/detach.
- Install/update/restart and verify real board/profile configuration before merge.

These are **not yet verified** in this source increment: actual PR-head Windows and Linux CI, real screenshots, board-generated build, physical hardware, upgrade/rollback, and user visual acceptance. Missing gates must not be described as passed.

## Quick user acceptance test

1. Select a Zephyr project in Project Explorer. Open Zephyr → Configure & Build → Configuration Studio.
2. Check project, board, profile and receipt status. Refresh.
3. Open Hardware, choose GPIO or ADC, then Guided setup: original guarded workflow opens; no source is changed merely by inspecting.
4. Return with Visual Studio, open Kconfig, search CONFIG_GPIO. Only effective generated symbols may be displayed.
5. In Build & System open profiles or diagnosis; Hardware Studio opens using the selected build.
6. Resize and detach the view; check both themes and a narrow window. Test native fallback when SWT Browser is unavailable.
7. Edit an overlay or prj.conf, verify changed or unsaved status is not represented as current until verified rebuild.

**Merge gate:** source presence is not release readiness. Require exact-commit CI, installed smoke and user UI review. If hardware qualification is deferred, record it as unverified.
