<!-- SPDX-License-Identifier: Apache-2.0 -->
# Real Devicetree configure validation — Phase 0 opt-in

The previous `zew dt-check` command is a **binding-aware interpretation**
of a generated DTS and a temporary plain overlay. It cannot execute the
preprocessor, discover all Zephyr modules or prove a real Zephyr configure.

Use `zew dt-configure` to ask the *selected* Zephyr checkout to run
`west build --cmake-only` in a freshly created disposable build directory
against a bounded copy of the selected application:

```powershell
python zew.py dt-configure `
  --build "C:\Zephyr_Project\.zew-build\ci" `
  --application "C:\Zephyr_Project" `
  --zephyr "C:\Zephyr_Workspace\zephyr" `
  --python "C:\Zephyr_Workspace\.venv\Scripts\python.exe" `
  --board "lp_mspm0g3507/mspm0g3507" `
  --ninja "C:\Tools\ninja.exe"
```

To test a *new additional* saved overlay (must be inside the application):

```powershell
python zew.py dt-configure `
  --build "C:\Zephyr_Project\.zew-build\ci" `
  --application "C:\Zephyr_Project" `
  --zephyr "C:\Zephyr_Workspace\zephyr" `
  --python "C:\Zephyr_Workspace\.venv\Scripts\python.exe" `
  --board "lp_mspm0g3507/mspm0g3507" `
  --overlay "C:\Zephyr_Project\candidate.overlay" `
  --ninja "C:\Tools\ninja.exe"
```

The examples are illustrative; substitute the **actual** build location,
Zephyr checkout, managed Python and board target from Project settings.
`--scratch-parent` can select an existing short-path directory outside the
application/build (especially useful on Windows); its temporary child is
removed afterwards. Timeout is 300 seconds by default (30–900 supported).

## What is verified

1. The prior build's `CMakeCache.txt` identifies the same `ZEPHYR_BASE`
   and selected board/target. There must already be a generated
   `zephyr/zephyr.dts` for that selected build.
2. A **bounded application snapshot** is copied into a fresh temporary
   directory. Version-control data, build folders, virtual environments and
   credential-like files are excluded; symlinks and unusually large trees
   are rejected rather than followed.
3. The actual selected Python runs `-m west build --cmake-only
   --pristine=always -b <board> -d <temporary-build> <temporary-application>`.
   If supplied, `--overlay` is passed via
   `-DEXTRA_DTC_OVERLAY_FILE:STRING=<temporary-copy>`, keeping ordinary
   Zephyr overlay discovery intact. Zephyr owns preprocessing, DTS binding
   resolution, Kconfig and board selection.
4. The resulting scratch build must have a real generated
   `zephyr/zephyr.dts`, an identifiable `ZEPHYR_BASE`/board, and
   CMake cache evidence of the optional extra overlay. A process exit 0
   without those artifacts is **not** accepted as a successful configure.
5. The original selected generated DTS and optional candidate input are
   independently hashed before/after validation. JSON exports provenance,
   fixed outcome codes, and sanitized bounded diagnostic categories.
   Raw CMake logs, absolute source paths, private property values and
   arbitrary exception messages are never exported.

Status `configured-in-scratch` (exit 0) establishes **only** that a
temporary copied application configured successfully using the selected
Zephyr toolchain/checkout. Other statuses return exit 1 and are not
evidence of working firmware. Invalid arguments return exit 2.

## Important limits

- This command is an **opt-in code-execution operation**, unlike
  `dt-check`. CMakeLists.txt and Zephyr/module hooks can execute code
  and can reference absolute external paths. Run it only with trusted
  projects and dependencies; the scratch copy is not a security sandbox.
- Some projects use absolute paths, custom scripts, cached `CONF_FILE`,
  `DTC_OVERLAY_FILE`, board roots, sysbuild configuration or non-standard
  runners not reconstructed by this bounded prototype. A scratch pass
  is **not** a proof of parity with the existing build; compare generated
  DTS hashes and the exact invocation, and do a real firmware build next.
- `EXTRA_DTC_OVERLAY_FILE` is **additional** and has higher precedence,
  not a replacement for any overlay already included in the original
  project. Do not claim the selected original build already contains it.
- A successful configuration **does not** compile C/C++, link the firmware,
  establish driver availability at runtime, verify physical pins or
  electrical safety, flash hardware, or prove a learning improvement.
- This Phase-0 command does not automatically save overlays or modify
  Configuration Studio's existing reviewed source-editing workflow.

Unit tests cover fake (isolated) CMake success/error and absence of fake
success, while Windows firmware CI runs the real candidate overlay through
Zephyr for MSPM0, checks original hashes, and verifies scratch cleanup.
A physical-board acceptance run is still needed. **G0 remains open.**

Zephyr's standard build documentation explains the behavior of
`DTC_OVERLAY_FILE`, `EXTRA_DTC_OVERLAY_FILE` and `west build --cmake-only`.
