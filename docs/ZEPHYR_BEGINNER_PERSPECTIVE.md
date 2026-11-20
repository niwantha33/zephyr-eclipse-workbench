<!-- SPDX-License-Identifier: Apache-2.0 -->
# Zephyr Workbench task-focused UX

Status: development preview. No release or hardware qualification is implied by the interface change.

## One developer workflow

**New / Import Project → Choose Board → Configure → Build → Flash or Debug → Trace & Analyse**

The Zephyr menu now uses task names, with specialised investigations in dedicated submenus. Commands and the existing build/debug/trace backends remain registered. The unavailable live-streaming placeholder is not advertised as a working action.

## Two everyday workspaces

- **Getting Started Workspace**: Project Explorer (left), editor (centre), Zephyr project guide (right), Console and Problems (bottom). Five actions: project, board, configuration, build, and explicit flash/debug.
- **Developer Workspace**: Project Explorer, editor, Configuration Studio, Console and Problems. Hardware Studio, boards, tracing and registers are available on demand, not forcibly opened.
- **Hardware Debug Workspace** remains separate for actual debug sessions.

Both workspaces are layouts only: switching them cannot flash/reset a target.

## Board browser

**Board & Hardware > Browse Available Boards** shows a searchable 4-column list with an SoC/vendor filter and optional technical details. **Change Project Board** uses the same validated Project Properties workflow as before. Merely browsing metadata cannot change the saved build target.

## Native acceptance to execute before release

1. Ensure new commands, handlers and perspectives register and open cleanly.
2. Exercise new/import, changing project board, Configuration Studio, build, flash and debug with deliberate user confirmation.
3. Test board filtering with both search and silicon vendor, empty results, current target highlight, environment failure, cancellation and switching projects during discovery.
4. Check 1366×768 and 1920×1080, high DPI, light/dark system themes, keyboard focus and screen-reader labels.
5. Verify existing workspace layouts survive upgrade and that all advanced commands are still reachable.
6. Run Tycho, core tests, Eclipse native smoke and Windows release matrix. Hardware tests are separate qualification and must not be marked PASS without evidence.
