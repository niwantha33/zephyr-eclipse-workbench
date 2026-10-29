# Native Zephyr Configuration Studio — initial integration

The Zephyr menu now has **Configuration Studio (Kconfig / Devicetree)**.
Select an imported project in Project Explorer and open the command. This is
a native Eclipse SWT/JFace view; no secondary browser, VS Code, tkinter or
unmanaged Python installation is required.

## Beginner-first context-menu workflow

Right-click the **Zephyr project**, its Eclipse **.project** descriptor,
or a **.prj** project descriptor and select **Open Zephyr Configuration
Studio...**. Right-click **prj.conf** to open its project-owned settings
directly. Right-click **app.overlay** or another application `.overlay`
to open the hardware page; **Open overlay** opens the exact selected file.

This is not an editor for arbitrary `.prj` formats. The action appears only
when the containing Eclipse project is recognised as an existing Zephyr
application. Unrelated files and non-Zephyr projects retain their own
context menus.

Kconfig Studio now offers a plain-language feature list with **Enabled**
and **Disabled** labels and a one-click choice between **Turn on** and
**Turn off** for boolean settings. Other values still use an explicitly
reviewed text input. Search understands friendly names and `CONFIG_`
symbols. The left filter groups features by common purpose and distinguishes
my project settings and requests that need review.

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
