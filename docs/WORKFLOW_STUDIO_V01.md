<!-- SPDX-License-Identifier: Apache-2.0 -->
# Zephyr Workflow Studio — v0.1 (development preview)

Workflow Studio is a native Eclipse view opened from **Zephyr > Automate Workflow > Workflow Studio**.
It is designed to remove repeated manual operations, not to replace Zephyr West
or the Workbench's existing build, flash and debugging implementations.

## First workflow

1. Select a managed Zephyr project from the project selector.
2. Keep the safe starter workflow **Clean > Build**, or drag blocks from the
   palette to extend it.
3. Drag blocks within the ordered table to reorder them, or use **Move up /
   Move down / Remove**.
4. Add **Flash** or **Flash & Debug** as the last block. Physical programming
   cannot be followed by another operation in v0.1.
5. Save to the project at **.zew/workflows/default.properties**.
6. Press **Run workflow**. The native Flash dialog still requires explicit
   confirmation before programming. **Cancel** requests cancellation of the
   currently running operation. The workflow stops on any failure.

The project-owned file has version 1, a name, and comma-separated action IDs
(CLEAN, BUILD, FLASH, FLASH_DEBUG). The loader validates unknown and
misordered steps. The starter workflow has **no hardware writes**.

## Execution and safety

- Clean and Build use the Workbench's existing ZephyrProjectBuilder and
  build-profile selection.
- Flash and Flash & Debug use the existing **native Eclipse launch delegate**,
  including its board/profile checks, verified build, runner and confirmation.
  The workflow calls the launch synchronously on its worker and checks for a
  created flash process; a cancelled confirmation does not report success.
- The selected project, board, profile and output directory must remain
  unchanged throughout execution. Builds and flashes stop on errors.
- **Limitation:** Native Flash deliberately performs another verified build.
  A separate Build block immediately before Flash therefore causes two builds
  in v0.1. This is retained until safe, proven build-reuse evidence exists.
- A completed **Flash & Debug** block means Flash completed and the Workbench
  requested the native debugger. **Debug attach/connection is not yet a
  separately proven workflow-success event** and must be checked in Eclipse.
- The workflow runs a linear sequence, not arbitrary branches or parallel
  execution. One project-local default workflow is supported initially.
- No hidden process executor, bespoke probe protocol, CI server, or network
  access is introduced by this feature.

## Qualification gate

Run the pure-model smoke test using Java 21:

    python3 scripts/test_workflow_definition.py

The standard PR package job must still compile the full Eclipse UI plug-in.
Before merging, perform interactive Windows testing:

1. Use an imported MSPM0G3507 project and its active build profile.
2. Drag Clean, Build, Flash & Debug; save, close and reopen the view.
3. Confirm block order and saved text survive a Workbench restart.
4. Run Clean > Build and verify both stages complete with accurate output.
5. Introduce a compiler error and confirm Flash is not attempted.
6. Verify refusing the Flash dialog cancels without programming hardware.
7. Run with connected supported hardware, verify flash completion, then verify
   a real native debug connection and breakpoints.
8. Cancel a long operation and check no child build or programmer processes
   remain; re-run successfully afterward.
9. Switch board/profile mid-workflow and verify the workflow stops safely.
10. Repeat at 1366x768 and high-DPI Windows scales; ensure palette/table/details
    remain usable, including keyboard-only button operations.

Hardware qualification and the user-experience gate remain **unverified** until
these tests are performed on the target machine.
