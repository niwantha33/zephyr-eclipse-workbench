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

The first-letter popup is enabled by default for Zephyr projects only. Turn
it off under **Window > Preferences > Zephyr Workbench > Automatically show C/C++
completions from the first letter**. Workbench also turns off CDT's two implicit
insertion preferences at startup, including saved values from older workspaces.
These CDT settings are shared by all native C/C++ editors inside this Workbench
installation (including non-Zephyr projects), but are not changed in other
Eclipse installations or in any project source files. Custom Devicetree/YAML
editors keep their existing behavior. CDT continues to own `.`, `->` and
`::` completions, ranking and proposal information.

## Safe suggestion-only mode

The Workbench's first-letter request must not change source code. CDT's own
**C/C++ > Editor > Content Assist** has two independent insertion options:

- **Insert single proposals automatically**
- **Insert common prefixes automatically**

Workbench sets both the **defaults and effective saved preferences** to off
when it starts. Unlike changing defaults alone, this also repairs existing
workspaces that previously enabled automatic single-proposal insertion or
common-prefix insertion. This is a Workbench-wide CDT setting, not an individual
project setting. It keeps proposal popups while protecting typed source text
(for example, `static void` must not turn into `static volatileid`).

If either option is subsequently re-enabled manually during the session, the
Workbench **does not automatically invoke** first-letter CDT Content Assist
until both are unchecked again; this fails closed rather than modifying code.
The guard checks on typing and immediately before the delayed request.

Manual **Ctrl+Space** follows CDT preferences. With both insertion preferences
off, it is suggestion-only. Only explicitly choosing a proposal with Enter, Tab,
or a click inserts that proposal.

## Safety and tests

The listener fires after a letter is entered; it checks the focused native CDT
editor, the Zephyr project nature, the active preference, the caret/selection,
and whether the typed identifier still exists. The pending request is cancelled
if focus or editor changes, the token is removed, or typing moves beyond it.
The plugin removes its listener at stop. No workspace configuration or firmware
files are modified by the trigger itself.

Run `python3 scripts/test_smart_completion.py` for standalone trigger and
saved-preference repair tests, then the ordinary Tycho/native installed Eclipse
smoke before merging. On Windows, test an **existing workspace** that had both
CDT insertion preferences enabled: restart the updated Workbench, confirm they
are unchecked, and type `static void`, `void test(void)`, and `volatile`
both quickly and with pauses after `v` and `vo`. The text must not change
without an explicit proposal selection. Verify first-letter suggestions still
appear, and Enter/Tab/click still accept a chosen suggestion. Also verify a
non-Zephyr editor's insertion settings are suggestion-only within the same
Workbench installation.

The standalone test does **not** validate actual popup positioning, CDT index
coverage, or physical UI behaviour. Those require the installed Eclipse smoke
and manual Windows confirmation.
