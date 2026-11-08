<!-- SPDX-License-Identifier: Apache-2.0 -->
# First-letter C/C++ code completion

## Scope

In native Eclipse CDT C/C++ source editors opened from projects carrying the
Zephyr nature, typing the first identifier character requests **CDT Content
Assist**, after a short 120 ms delay. Existing CDT proposals are not replaced
by a hardcoded function list. Actual suggestions depend on active compilation
settings, available headers, parsed symbols, and the state of the CDT indexer.
This does not promise every upstream API is indexed.

Examples: typing `g` may offer `gpio_pin_set_dt` if the relevant header and
symbols are available; `DT_` and `CONFIG_` can complete only macros known to
the current translation unit. The popup is prefix-sensitive, not a catalogue
of all possible Zephyr symbols. Use **Ctrl+Space** at any point to force a
manual refresh.

## Controls

The feature is enabled by default for Zephyr projects only. Turn it off under
**Window > Preferences > Zephyr Workbench > Automatically show C/C++ completions
from the first letter**. It does not alter Eclipse's global CDT completion
settings or the contents of any project. Non-Zephyr C/C++ projects and custom
Devicetree/YAML editors keep their existing behavior. CDT continues to own
`.`, `->` and `::` completions, insertion, ranking and proposal information.

To prevent CDT automatically committing a single proposal, users can untick
**C/C++ > Editor > Content Assist > Insert single proposals automatically**.
This is a normal CDT preference, not changed by Zephyr Workbench.

## Safety and tests

The listener fires after a letter is entered; it checks the focused native CDT
editor, the Zephyr project nature, the active preference, the caret/selection,
and whether the typed identifier still exists. The pending request is cancelled
if focus or editor changes, the token is removed, or typing moves beyond it.
The plugin removes its listener at stop. No workspace configuration or firmware
files are modified by the trigger itself.

Run `python3 scripts/test_smart_completion.py` for standalone trigger tests,
and run the ordinary Tycho/native installed Eclipse smoke before merging.
The standalone test does **not** validate actual popup positioning, CDT
index coverage, or cross-platform physical UI behaviour. Those require the
installed Eclipse smoke and manual Windows confirmation.
