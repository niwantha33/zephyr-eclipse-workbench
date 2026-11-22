<!-- SPDX-License-Identifier: Apache-2.0 -->
# Automatic UML Studio — milestone 1: CDT source-outline preview

**Development feature only.** This is a native Eclipse RCP view, not a
complete UML reverse-engineering engine or a verified release capability.

## Open it

1. Open a saved `.h`, `.c`, `.hpp` or `.cpp` file in a CDT-enabled Zephyr
   project, or select that file in Project Explorer.
2. Choose **Zephyr → Advanced Diagnostics → Automatic UML...**.
   Alternatively: **Window → Show View → Other → Zephyr Automatic UML (C/C++)**.
3. The view reads the selected file's **Eclipse CDT source outline** in an
   Eclipse background Job. It draws class/struct/union boxes with fields and
   methods, plus a **file module** box for top-level C/C++ functions in ordinary
   source files. No manual drawing or remote renderer is needed.
4. Double-click a type box to navigate to its declaration in the original
   source. Use **Refresh diagram** after saving source changes.
5. **Copy PlantUML** copies the exact displayed model as editable `.puml`
   text. This does not invoke a PlantUML server or install any dependency.

If you see **No CDT translation unit** or **CDT cannot parse the saved-source
outline**, first build the application once (to create `compile_commands.json`),
then choose **Zephyr → Tools and Setup → Refresh C/C++ Editor Settings**.
Reopen the C/C++ file and press **Refresh diagram**. This is a CDT model
prerequisite, not a signal that the Zephyr firmware or build failed.

## Project links (development)

With a saved source file open in the target CDT project, choose **Project links**
in the Automatic UML view. This starts a bounded, read-only CDT index/AST scan
and draws **direct function-call** arrows across the project source files.
Double-click a function box to open its actual source definition. Choose
**Generate from active file** to return to the class/structure preview.
**Copy PlantUML** exports the currently visible graph, including verified arrows.

The initial project graph scans at most 24 source files, 64 function nodes and
96 unique arrows. The status explicitly marks incomplete scans. It excludes
unresolved calls, indirect callbacks, macro-only calls, potential/ambiguous
targets, virtual dispatch and out-of-project definitions. Relationships are
**static source evidence, not runtime execution**. Only CDT-managed workspace
source files count; generated or external files and CMake-linked sibling sources
outside the imported Eclipse project are not traversed. As with the file view,
build once and refresh CDT editor settings if the index is missing.

## Deliberate boundaries

- **No manual drawing** is needed for the initial class/structure view.
  The canvas is generated from CDT model elements, not handwritten example
  diagrams and not a regex-based source parser.
- **File outline mode** covers one selected translation unit, including modelled
  nested definitions and top-level free-function signatures. Headers, macros,
  compile conditions and templates are only as complete as CDT's model.
  The file-module preview itself has no inferred call edges or execution order.
  Namespaced free functions are not yet included in its module box.
- **Project links mode** displays only verified direct call expressions resolved
  by CDT to a unique function definition. It does not infer associations,
  inheritance, RTOS messaging, indirect function-pointer calls, dynamic dispatch,
  state-machine transitions or any sequence of actual execution.
- It does **not** imply a source file is in the firmware image, that an ELF
  was flashed, that a function executed or that a thread was scheduled.
- The view is **read-only**. It makes no build, flash, source edit or network
  request. Work with saved source; unsaved editor changes may not be reflected.
- The UI renders at most **32 boxes** with up to **18 attributes** and
  **18 operations** per box. Large function lists include an omission marker,
  and larger source models show a truncation notice.
  It is possible to encounter CDT outline errors; these produce an explicit
  unavailable state, never an invented diagram.
- PlantUML is only a **text export format**; the installed view's native SWT
  renderer does not require the PlantUML plug-in or external binaries.

## Tests and qualification

- `tests/core/UmlClassModelTests.java`: deterministic class/module export,
  immutable snapshots, bounds and escaping of source content.
- `tests/core/UmlProjectGraphTests.java`: project links, deterministic export,
  omitted unsupported edges, immutability and sanitization.
- `tests/eclipse/UmlClassSmoke.java`: installed UI registration,
  file/project Canvas projections, export availability and disposal.
- `scripts/verify_site.py`: requires all UML implementation classes in the
  installed bundle.
- Still required before promising release: exact-head Linux Tycho/p2 and
  installed SWT smoke, Windows tests, real C and C++ source acceptance
  (including a regular Zephyr `main.c` with no structures), large-file bounds
  and a clean-host update verification.

## Next reviewed increments

**Milestone 2 (partial):** Bounded CDT-backed direct-call relationships
across imported project sources. Inheritance and richer relationships are
not yet implemented.

**Milestone 3:** Zephyr API/object interaction maps (threads, semaphores,
mutexes, queues, work items) generated only when source or build artefacts
actually establish a relationship. Distinguish static usage from observed
runtime traces.

**Milestone 4:** State and sequence diagrams derived from explicit source
constructs or captured trace evidence, with provenance and navigate-to-source.
Do not infer an execution order from calls alone.

The long-term architecture should reuse the headless Evidence Graph rather
than create a parallel set of claims in a standalone UI.
