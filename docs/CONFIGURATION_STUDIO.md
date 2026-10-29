# Native Zephyr Configuration Studio — initial integration

The Zephyr menu now has **Configuration Studio (Kconfig / Devicetree)**.
Select an imported project in Project Explorer and open the command. This is
a native Eclipse SWT/JFace view; no secondary browser, VS Code, tkinter or
unmanaged Python installation is required.

## Configuration Studio — native Eclipse workspace UX

The Configuration Studio is now organised into **Features**, **Hardware** and
**Insights** tabs, with a compact project/target header, native **Project
settings** and **Build & validate** actions, and live summary metrics for
available symbols, application overrides, requests needing review and the
last generated build snapshot.

The Features tab provides quick filters for **All features**, **My changes**,
**Needs review** and **RTOS & tracing**; a category selector and search by
feature name, `CONFIG_` symbol or requested value remain available. The
feature grid includes readable names **and** exact technical symbols, effective
values, requested overrides and validation status. A selected setting shows
its name and status alongside source help and safe, visible **Enable**,
**Disable**, **Edit value** and **Remove override** controls. The feature
context menu offers copy-symbol, source opening and eligible on/off actions.
Double-clicking an explicit override opens its original `prj.conf`; a
different inherited feature remains in the inspector.

A modified `prj.conf` newer than the generated `.config` is marked
**Build required**. The **Needs review** filter includes both unresolved
requests and requested/effective mismatches. These diagnostics are advisory;
the actual Zephyr configure step remains authoritative. No build outcome or
hardware setting is fabricated.

Fonts and UI resources are owned and disposed by the native Eclipse view;
the UI uses the host platform's SWT controls, not browser styling. Existing
project source files, right-click project import behaviour, read-only
generated files, optimistic edit checks and backup/confirmation paths are
preserved.

## Configuration Studio context-menu workflow

Right-click the **Zephyr project**, its Eclipse **.project** descriptor,
or a **.prj** project descriptor and select **Open Zephyr Configuration
Studio...**. Right-click **prj.conf** to open its project-owned settings
directly. Right-click **app.overlay** or another application `.overlay`
to open the hardware page; **Open overlay** opens the exact selected file.

This is not an editor for arbitrary `.prj` formats. The action appears only
when the containing Eclipse project is recognised as an existing Zephyr
application. Unrelated files and non-Zephyr projects retain their own
context menus.

Kconfig Studio provides **Enable** and **Disable** buttons directly in the
details panel for options whose effective generated Kconfig value is `y` or
`n`. The action is disabled for unresolved and non-switch options.
**Edit value** remains available for numeric or text settings; **Remove
override** clears an application request without guessing the resulting
Zephyr default. The table separately displays effective values, requested
values, and entries needing build verification. All changes require a
review, backup and a subsequent Zephyr build; this is not a live Kconfig
dependency solver. Search accepts technical `CONFIG_` names and display
labels. Filters group common subsystems and show local project requests.

Category assignment is a display heuristic, **not** Kconfig dependency
evaluation. Generated build outputs may also be stale; the real Zephyr
configure-and-build step remains authoritative. Removing a setting from
`prj.conf` does not guarantee a specific default if another active fragment
or board configuration sets it.

## Kconfig Studio

The selected exact board and build profile come from the existing project
settings. The viewer reads the generated build's zephyr/.config without
changing it and displays assignments in the application's prj.conf.
An invalid prj.conf remains inspectable even when configure has failed.
For example, CONFIG_DEBUG_INFO=y can still be selected and removed.

Search by symbol or requested value. Select a setting to inspect its
last-effective value, prj.conf request and line number. A bounded
asynchronous scan locates matching config/menuconfig declarations in the
checked-out Zephyr source and shows a raw source excerpt, including help and
declared dependencies where available. This is supplemental context, NOT
evaluation of dependency expressions or proof that a declaration is active
for the selected board. The generated .config represents effective values
only after a successful configure.

An edit to prj.conf must be explicitly previewed and confirmed. The writer
rejects unknown arbitrary symbol names, multiline values, symlinked files,
concurrent edits and non-atomic replacements. It preserves unrelated lines
and backs up the complete original file under .zew/config-backups/.
Build afterwards to check the new configuration with Zephyr itself.
Existing CONF_FILE and EXTRA_CONF_FILE fragments are not rewritten: only
prj.conf is editable in this first integration.

## Devicetree Studio

The second tab displays zephyr/zephyr.dts generated for the selected build.
This output is read-only. The user may explicitly create or open app.overlay
as a normal Eclipse source file; existing content is never overwritten.
The editor does not automatically assign GPIO pins, clocks or peripherals.
Such an implementation requires vendor-specific constraints and binding
validation rather than simply inserting arbitrary properties.

## Configuration Analysis

The third tab shows generated setting count, explicit prj.conf requests,
requests absent from the generated .config, compiled DTS availability, and
ELF/BIN file sizes if present. These are byte counts of files, NOT target
flash or RAM utilisation. RTOS trace options are listed for inspection,
but final support depends on the selected target and backend.

## Validation gates

- Portable Java regression test ConfigurationStudioTests: edit preview,
  invalid request repair, duplicate assignments, configuration backup,
  stale-file rejection, generated file preservation and source help lookup.
- Existing Java/Python suite, Tycho package build and p2 verification.
- Manual Eclipse GUI tests for filter, navigation, project switching, errors,
  source edits and performance until automated UI testing is available.
- Fresh Windows and hardware validation must be reported separately.

## Known remaining work

- Full Kconfiglib-backed menu hierarchy and live dependency evaluation.
- Undo/redo and staged configuration transactions across all active fragments.
- Sandbox configure/rollback before committing user configuration.
- Devicetree node/binding graph and vendor-specific pinmux/clock visualisation.
- Actual measured flash/RAM and RTOS analysis integration.
- Automated cross-vendor GUI and hardware tests.

These are not claimed as completed features of this first integration.
