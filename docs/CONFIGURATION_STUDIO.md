# Native Zephyr Configuration Studio — initial integration

## Step 07A — Hardware from the real Devicetree

The Hardware workspace now has a native **Detected hardware** inventory
built strictly from the selected target's generated `zephyr/zephyr.dts`.
It lists paths, compatible bindings and enabled/disabled status and shows
the selected device's label and register declaration. The complete generated
source remains read-only below the inventory, alongside the existing
**Open application overlay** action.

The inventory is informative, not a complete DTS parser. It does not
resolve aliases, all phandles, `pinctrl` encodings, inherited bus status or
vendor-specific channel capabilities. Use the exact selected build and
Devicetree bindings to verify hardware. No pins, channels or device nodes
are silently created or enabled. A missing build shows no fabricated devices.

The following step extends this with binding-driven pin/channel validation,
a graphical status editor, reviewed `app.overlay` patch previews, backups
and a reconfigure/build gate. Do not implement automatic DTS changes without
validating node identity and preventing conflicts.


The Zephyr menu now has **Configuration Studio (Kconfig / Devicetree)**.
Select an imported project in Project Explorer and open the command. This is
a native Eclipse SWT/JFace view; no secondary browser, VS Code, tkinter or
unmanaged Python installation is required.

## Kconfiglib dependency inspector (Step 05)

When a **configured Ninja build** is selected, the feature inspector can use
the *same* generated Kconfig environment that Zephyr's `guiconfig` target
uses. The packaged, read-only `kconfig_inspect.py` resource retrieves the
Ninja command with `ninja -t commands guiconfig`, reads its generated
Kconfig environment variables and root file **without executing the target**,
then loads `zephyr/.config` through the selected Zephyr checkout's
`scripts/kconfig/kconfiglib.py`. No Kconfig input, project source or build
artifact is modified by inspection.

The **Explanation & dependencies** tab then shows the Kconfiglib symbol type,
effective value, prompt, direct dependency expression and evaluated result,
visibility, currently assignable values, `select`/`imply` relationships,
definitions and upstream help. If a resolved boolean is not assignable to
`y`/`n` in the last built state, the corresponding inline toggle is
disabled and the save handler rejects the blocked request even if clicked by
a keyboard/accessibility action.

When the selected build has no Kconfig Ninja target, the toolchain cannot
be reconstructed, a symbol is missing, or a build is stale, this is **clearly
marked as unavailable**; the normal Kconfig source declaration viewer and
backup/review workflow remain intact. A disabled setting is **not**
automatically enabled by changing its dependencies. Kconfiglib results
represent the last successful build, so changes to `prj.conf` still require
rebuilding before the inspector can show updated dependency states.

This is the first evaluator-backed dependency inspection stage. A complete
interactive Kconfig dependency graph, transitive change planning, graphical
pinmux and board-specific peripheral wizard remain later roadmap items.

## Peripheral workspaces and inline controls (Step 02–04)

The **Features** workspace now contains separate, native SWT peripheral tabs:
**Overview, ADC, GPIO, I²C, SPI, UART, CAN, PWM, Debug, RTOS, Network and More**.
Selecting a tab filters only symbols present in the project's generated
`.config` (or explicitly requested in `prj.conf` if configuration has not
yet succeeded). **More** collects options outside the common groups, so
no vendor-specific symbols are silently hidden.

Each tab provides a readable purpose statement, real relevant symbols from
the selected build, the driver request's last-effective value where present,
and a clear reminder that enabling a Kconfig driver does not manufacture
a peripheral instance or choose pins/DMA. ADC and GPIO tabs provide
copyable **template** C code, explicitly requiring valid board Devicetree
aliases or `io-channels`; the tool does not insert that code automatically.

On/off controls now live **inside the table's Enable / Disable value column**:
the selected resolved boolean row exposes two accessible native SWT toggle
buttons. This uses **one reusable editor**, avoiding hundreds of heavyweight
buttons and native handles for large Kconfig configurations. Non-switch and
unresolved options retain safe read-only values and the existing Edit/Remove
path. A cancelled change review restores the selected toggle state. Every
accepted change still uses preview, backup, optimistic edit checking and a
subsequent Zephyr build; it does **not** write `zephyr/.config` or claim to
solve Kconfig dependency expressions.

The **Hardware** workspace explains the distinction: `prj.conf` requests
software components; `app.overlay` describes application hardware devices
and properties. The generated `zephyr.dts` remains read-only. Graphical
pin mapping, valid ADC channel lists and vendor pinmux validation are
future steps, not fabricated by the current view.

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

## GPIO references and usable examples

The GPIO bank view accepts the actual generated DTS forms with either numeric
controller phandles or symbolic references such as `&gpioa`. Exact node labels
are distinct from the optional `label` property. A symbolic reference must
resolve to a known controller with a supported `#gpio-cells`; unknown or malformed
mixed lists are not partially attributed to another bank. Logical register bit
ranges do not establish package bonding, pin ownership or electrical availability.

The **GPIO Evidence** dock lists **board DTS declarations** and **unverified
saved-source patterns**, not proven application usage. A board-provided LED
declaration on GPIO B is still inspectable when GPIO A is selected, but is
labelled `Board DTS only`; selecting the row navigates to the bank. Default
planner cells remain neutral, and `led0` is hidden unless the user explicitly
enables the board-DTS layer. An empty inventory shows an explanation rather
than an unexplained blank table.

Saved-source candidates include `device_get_binding()` plus literal-index
`gpio_pin_configure()` (also in conditionals), exact
`DEVICE_DT_GET(DT_NODELABEL(...))`, and common `gpio_dt_spec` initializers using
`GPIO_DT_SPEC_GET(DT_ALIAS(...), gpios)` or `DT_NODELABEL(...)`, including a simple
selector macro such as `LED0_NODE`. The scanner is intentionally not a C compiler,
AST or preprocessor: inactive branches and uncompiled files may appear. Arbitrary
macro expansion, cross-file aliases and dynamic pointers are not inferred.
`GPIO_0` is not guessed to mean GPIO A; unresolved candidates remain separate.

Select a GPIO index and open **GPIO Example C** to inspect and copy an
exact-controller template. It includes readiness and API error checks. Templates
are read-only and nothing rewrites the project. Check the circuit before using
an output template. The complete importable application is
[`examples/gpio_usage_demo`](../examples/gpio_usage_demo/README.md): it blinks the
board's `led0` alias and leaves its extra PA15 output off by default.

The central planner receives more of the initial vertical space; the evidence
dock remains resizable. GPIO indices also support arrow-key selection. No
physical package-pin diagram or live register configuration is claimed.

## Named GPIO tiles and reviewed additions

Used logical GPIO tiles now show their bank/index and exact built consumer
identity on two lines, for example `PB22 / led0`. The bank/index comes from the
selected controller, so a GPIO B consumer is never relabelled as GPIO A. Names
prefer compiled aliases, then a `label` property, node label, or node name. A
short hover and the inspector retain the full name if the tile is too narrow.

Select an index with no built reference or exact saved-source candidate to
activate **Add DTS GPIO…**. Choose a lowercase DTS name, input/output and
active-high/active-low polarity, then select `app.overlay` or another active
source overlay. The Changes dock and final confirmation show the source
addition before anything is saved. Existing content is retained, backed up and
protected against edits since preview. The new alias maps underscores in the
node label to hyphens, following DTS alias conventions.

Output requests use the standard `gpio-leds` consumer binding; input requests
use `gpio-keys`. These are semantic LED/key consumers, not arbitrary live GPIO
register writes. Optional input event codes and any enabled driver's ownership
remain an explicit application choice. No pull configuration, output initial
level, package pad or free-pin claim is guessed. A missing controller label,
used index, name collision, disabled controller, stale build or unsupported
cell shape blocks the flow. Rebuild after saving and verify the selected overlay,
controller binding, driver configuration and actual circuit before using it.
