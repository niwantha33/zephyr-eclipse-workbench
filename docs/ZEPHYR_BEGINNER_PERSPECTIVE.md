# Zephyr Beginner perspective

Status: development preview, G0 remains open. No advanced features are removed.

## First launch and workspace compatibility

In a fresh, empty Eclipse workspace, the Zephyr UI startup contribution
switches to the Zephyr Beginner perspective once, before offering the existing
setup wizard. This start-up transition is used because the project currently
ships as an Eclipse feature installed into an existing IDE, not as a branded
Eclipse product with a product-level default perspective.

Saved perspective layouts are preserved for configured workspaces, workspaces
with existing projects, and users who have already seen the setup prompt.
Changing perspective manually never flashes or resets a target.

Use **Zephyr > Beginner Workspace** to return at any time. Other perspectives
remain available under Window > Perspective > Open Perspective.

## First screen

| Area | Default view | Details |
| --- | --- | --- |
| Left | Project Explorer | Choose one imported Zephyr project |
| Centre | Standard Eclipse editor | Source files and configuration files open normally |
| Right | Zephyr Getting Started | Five actions and a small project/board/profile/output summary |
| Bottom | Console, Problems | Output and diagnostics without tracing panels |

Configuration Studio and the advanced hardware graph are opened only on
request. The status panel reports the actual board/profile for the selected
project. ELF presence is a filesystem check only, not verified firmware
provenance, a successful flash, or evidence of a responsive target.

## Five actions

1. **Select board:** Create or import a project. Board and CPU can be changed
   in Project Properties > Zephyr.
2. **Configure:** Open Configuration Studio for an overview or a guided
   hardware section.
3. **Select profile:** Open the existing profile selection in Project
   Properties; the default profile remains valid.
4. **Build:** Build the explicitly selected Zephyr project. If configuration
   is missing, Project Properties opens before any build is scheduled.
5. **Debug:** Open Zephyr Hardware Debug, then explicitly select a Flash and
   Debug operation. Merely switching perspective does nothing to hardware.

Use Advanced views inside the guide for a one-click menu of Devicetree Graph,
YAML Structure, SVD registers, trace console and saved RTOS analysis. Other
tools remain available under Zephyr > Advanced Tools and Window > Show View.

## Implementation

The existing io.github.niwantha33.zew.ui plug-in owns:

- ZephyrBeginnerPerspective: Project Explorer / editor / guide / Console layout.
- ZephyrBeginnerView: short SWT workflow and honest selected-project evidence.
- ZephyrBeginnerPerspectiveHandler: explicit return to the beginner layout.
- SetupStartup: only-first-run empty-workspace switch.
- plugin.xml: registered contributions and reorganized Zephyr menu.
- ZephyrBeginnerPerspectiveSmoke: native SWT perspective registration check.

The existing Hardware Debug perspective remains independent. No existing
view ID, command ID, handler or debug integration is deleted.

## Verification

The native SWT smoke checks the registration of both perspectives, presence of
Project Explorer, guide, Console and Problems, and absence of eagerly opened
SVD registers. Also manually test a clean workspace and an upgraded existing
workspace, switching between perspectives, profile selection, 1366 x 768
scrolling, an incomplete configuration, advanced Show View access, and
non-destructive entry to Hardware Debug.

Release qualification is separate from implementation tests.
