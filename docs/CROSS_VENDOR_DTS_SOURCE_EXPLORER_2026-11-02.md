<!-- SPDX-License-Identifier: Apache-2.0 -->
# Cross-vendor Devicetree source navigation — 2026-11-02

## Scope of this increment

Configuration Studio > Hardware now includes a native **DTS / DTSI Sources** dock. It displays original source files from the **selected build**, with each row explicitly labelled by evidence and broad file role. This does not use a hard-coded list of TI, STM32, Nordic or Espressif boards; sources are derived from Zephyr's own preprocessing dependency output at `<selected-build>/zephyr/zephyr.dts.d`. Zephyr 4.4 emits that file from `zephyr_dt_preprocess(DEPS_FILE ...)` during CMake configuration. Include paths and board revisions come from the selected target itself, including its external modules and overlays.

A click shows the exact file path and original text **read-only**. A source-only project without a successful configure shows `app.overlay`, if present, only as an **application candidate — not verified in build**. No guessed SoC/board file, pin, active overlay, property origin, build success, runtime GPIO or physical package relationship is displayed. `DTS / DTSI Sources` is deliberately appended after the existing six tabs so the installed SWT workflows keep their current tab indices.

## User workflow

1. Select/import a Zephyr application, choose an exact board/qualifier/profile and configure/build it.
2. Open **Configuration Studio > Hardware > DTS / DTSI Sources**.
3. Click board DTS, SoC DTSI or included source files to view their original definitions. The row differentiates build inputs from candidate overlays.
4. Use **Create / open app.overlay…** to create an empty application overlay only with an explicit confirmation, or **Edit selected application overlay…** on a project-owned overlay. Vendor/source files are never edited from this browser.
5. Continue using the existing binding-checked guided draft, scratch CMake preflight, verified save with backup, and **Build & validate**. After building, **Refresh sources** to inspect the updated selected build.
6. Use the existing **Generated DTS (read-only)** tab to inspect effective configuration, not the original declaration.

## Evidence / safety boundaries

- A dependency entry proves that a file participated in preprocessing for the last configure, **not** which definition won precedence or where a specific effective property originated.
- Changes to sources after the last configure may make dependency information stale. Reconfigure to update evidence.
- File selection is bounded and restricted to existing regular `.dts`, `.dtsi`, `.overlay` files; symbolic-link files and oversized source/depfile content are rejected. No filesystem-wide scan or network operation.
- An application overlay that is not in the generated dependency file is not asserted active. The editor does not auto-insert node labels, status, pinctrl, ADC channels or driver Kconfig.
- Existing guided overlay write/check paths are preserved. No vendor file write, flash, debug launch, OpenOCD operation, hardware usage or chip/pin capability claim happens via this browser.

## Acceptance tests

- Java core regression: missing build, actual board DTS / SoC DTSI / application overlay from one selected build, escaped spaces and dependency continuation, duplicate paths, Windows drive-target parsing, and stale removed depfile.
- Installed SWT smoke: a seventh source tab, actual native file table, read-only source viewer, and original six tab indices preserved.
- Required before release: installed SWT smoke on Windows and Linux, cross-vendor real CMake configure examples on selected exact Zephyr checkout, and manual screenshot/keyboard/HiDPI inspection.
- The PR does **not** claim that visual pin mapping, clickable property-to-origin provenance, automatic overlay semantics validation, or cross-device hardware testing is finished. Those are separate, build-gated follow-up deliverables.

References:
- https://docs.zephyrproject.org/latest/build/dts/intro-input-output.html
- https://docs.zephyrproject.org/latest/build/cmake/index.html
- https://github.com/zephyrproject-rtos/zephyr/blob/v4.4.0/cmake/modules/dts.cmake
