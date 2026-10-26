# Native IDE integration and professional editor roadmap

Status: Eclipse launch integration implemented in this change; cross-IDE adapters and editor improvements below are proposed work, not compatibility claims. Physical Windows flash/debug validation remains required.

## Native controls

| Task | Eclipse control | Behavior |
|---|---|---|
| Flash | Run dropdown / Run As > Zephyr Flash | Saved Run configuration; prepare/build exact selected target, confirmation, board runner and existing ESP32 selected-port/chip-match preflight |
| Debug | Debug dropdown / Debug As > Zephyr Application | Saved native CDT DSF-GDB configuration, managed OpenOCD and exact ELF |
| Build | Project > Build Project | Existing Zephyr builder; compiler diagnostics in Problems |
| Stop | Eclipse Terminate | Native launch process termination, owned child cleanup |

The first launch may require choosing the named shortcut. Subsequent main-button behavior follows Eclipse's own launch preferences/history. The Run control is not globally rebound: other applications and QEMU configurations retain their own Run actions. Flash and Debug shortcuts find only configurations with the matching project and action. Flash is explicitly selected and confirmed; Debug does not implicitly program firmware.

Both Zephyr menu Flash and native Run Flash enter the same native delegate, including repeated launches from Run history. Native Flash rebuilds before validating the build receipt and firmware artifacts. ESP32 serial selection and non-programming chip identity check run before any programming command. Other vendors retain their board-defined West runner. Duplicate custom Flash/Debug toolbar buttons are removed. Existing configurations remain available.

## Host compatibility

The current manifest requires Java 21, CDT core [9.3.0,10.0.0), native DSF-GDB and Eclipse Platform services. The build target pins Eclipse 2026-09 and CDT 12.6. This is the tested compilation target, not proof of compatibility with vendor-modified IDEs.

| Host | Proposed implementation | Current boundary |
|---|---|---|
| Pinned Eclipse/CDT | Existing p2 plug-in, builders, Problems, CDT indexer and DSF-GDB | Source compilation and regression checks; desktop/hardware evidence is separate |
| CCS 12.x | Separate compatibility investigation/adapter; external West tools and shared artifacts are the first candidate | Do not install the Java 21/current-CDT bundle into an older CCS and assume it works; preserve TI builders, project metadata and debugger |
| CCS 20+ / Theia | Separate VSIX/Theia frontend, supported task/debug APIs and language-server client, against a shared engine contract | Eclipse p2 bundles cannot serve as Theia extensions; validate the exact host VS Code API before enabling capabilities |
| VS Code | Separate VSIX frontend using tasks, language-server integration and a verified debug adapter | Proposed, not implemented by this PR |
| STM32CubeIDE / other Eclipse products | Host-version/API adapter or external-tool integration | Per-host Java/CDT/GDB capability checks and clean-install tests required |
| Other CMake/LSP editors | Compilation database plus explicit build/flash commands | Editor support does not imply a vendor debug backend is supported |

Existing TI-RTOS/CCS projects must retain their native workflow. Supporting an IDE does not port an MCU, convert TI-RTOS to Zephyr or provide a Zephyr board definition. Vendor debugger/XDS support is a separately tested backend; no proprietary debugger is bundled or made a core dependency here.

## Shared project and engine contract: next architectural slice

Keep the existing portable `.zew/project.json` as the seed of the project contract. Its current schema 1 supports one exact board and the `first` profile; Debug/Release profiles are not already implemented. Introduce versioned migrations before extending it. Keep host paths and credentials in local settings.

Expose a small local command/protocol interface over existing West/CMake/Ninja/Python behavior rather than reimplementing it in each UI. The current Java core and Python helpers are not yet a stable, cross-IDE service. Proposed requests: inspect environment, resolve target capabilities, configure/build, editor metadata, flash preflight/flash, debug-server start/stop. Use argument arrays, structured diagnostics, cancellation, operation IDs and a build identity. Return an explicit unsupported result for an unavailable tool/probe/backend.

Each IDE owns its native buttons, progress, Problems, console, source lookup and debug views. A future adapter must validate the backend/architecture rather than assume that every board is OpenOCD/GDB-compatible. Add physical-probe ownership across IDE processes; choosing another TCP port does not release an occupied USB/JTAG interface. Unknown external debugger processes must never be killed automatically.

## Editor: existing evidence and next improvements

The current `editor_settings.py` reads the compilation database, captures per-application-source flags, probes the selected SDK GCC for builtins/includes and emits verified metadata. `CdtEditor` applies per-file settings through the owned CDT configuration and schedules indexing. This is already compiler-derived completion, not a manual include-directory list. Application-source filtering means complete kernel/HAL implementation-source navigation still needs deliberate external-source indexing.

Priorities, in order:

1. **Reliable build/debug identity.** Resolve the current ESP32 flash-mapping/thread-memory discrepancy (the reported size byte 255 versus expected 4). Add matching firmware/ELF/config identities and verify stop/relaunch before claiming production readiness.
2. **Editor health and target changes.** Show the exact board/profile, compiler, compilation-database freshness, generated-header status and indexing progress. Refresh only the affected owned configuration after a successful board/profile/build change. Keep user/CCS configurations intact. Test switching boards with conflicting macros and C/C++ language flags.
3. **Completion/navigation acceptance.** Test API completion and parameter help, hover types/docs, definition/declaration, references and inactive Kconfig branches. Include generated headers and linker/generated symbols where applicable, plus read-only Zephyr/HAL source roots without indexing every build directory. Remove stale file settings after source deletion.
4. **Cross-editor language service.** Add an optional clangd adapter bound to the active build's compilation database. Limit query-driver access to the selected trusted compiler. Treat GCC/Xtensa flag compatibility explicitly; do not strip all `-m*`/`-f*` flags or claim every embedded architecture parses correctly. Preserve user-owned `.clangd`, `.vscode`, `.theia` and `.cproject` files.
5. **Professional code tools.** Project-owned clang-format policy; compiler warnings and optional clang-tidy/static-analysis integration with baselines; refactoring previews; include/source-header navigation and searchable API examples. Formatting/static analysis must not silently edit firmware or substitute for compiler results.
6. **Reproducibility and maintainability.** Debug/Release profiles with separate outputs, structured operation state/cancellation, focused services instead of duplicated UI workflows, locked dependency versions, source/build identity in logs and tested upgrade/migration paths. Test Windows path spaces/Unicode, failed builds, cancellation, target switches and busy probes. Record hardware evidence separately from build-only results.

## Acceptance for the native-control change

- Project or C/C++ editor context exposes Debug As > Zephyr Application and Run As > Zephyr Flash only for imported Zephyr projects.
- Flash creates/reuses the Flash configuration, Debug the Debug configuration; the other action cannot be chosen by shortcut discovery.
- A second click from native history repeats the correct action. Cancelling confirmation/port choice, a failed build, missing artifacts or mismatched chip prevents programming.
- Eclipse Terminate ends the owned runner; a stopped session can be launched again. Other IDE processes are left alone.
- Firmware flash and breakpoint/step/variable/thread tests must pass on the exact physical board; no vendor-wide claim follows from one successful board.

## Primary references

- Eclipse launch-shortcut API and extension: https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/extension-points/org_eclipse_debug_ui_launchShortcuts.html
- TI CCS/Theia and VS Code-extension API caveats: https://software-dl.ti.com/ccs/esd/documents/users_guide/ccs_faqs.html
- clangd compile database/LSP setup: https://clangd.llvm.org/installation.html
- clangd query-driver/configuration: https://clangd.llvm.org/config

Research checked 2026-10-26. Vendor host compatibility remains unverified until a versioned adapter and the required tests exist.
