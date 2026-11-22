<!-- SPDX-License-Identifier: Apache-2.0 -->
# Automatic UML Studio — milestone 1: CDT source-outline preview

**Development feature only.** This is a native Eclipse RCP view, not a
complete UML reverse-engineering engine or a verified release capability.

## Open it

1. Open a saved `.h`, `.c`, `.hpp` or `.cpp` file in a CDT-enabled Zephyr
   project, or select that file in Project Explorer.
2. Choose **Zephyr → Configure & Build → Automatic UML (C/C++ Class Diagram)**.
   Alternatively: **Window → Show View → Other → Zephyr Automatic UML (C/C++)**.
3. The view reads the selected file's **Eclipse CDT source outline** in an
   Eclipse background Job. It draws class/struct/union boxes with fields and
   methods, plus a **file module** box for top-level C/C++ functions in ordinary
   source files. No manual drawing or remote renderer is needed.
4. Double-click a type box to navigate to its declaration in the original
   source. Use **Refresh diagram** after saving source changes.
5. **Copy PlantUML** copies the exact displayed model as editable `.puml`
   text. This does not invoke a PlantUML server or install any dependency.

## Deliberate boundaries

- **No manual drawing** is needed for the initial class/structure view.
  The canvas is generated from CDT model elements, not handwritten example
  diagrams and not a regex-based source parser.
- The scope is **one selected translation unit**, including modelled nested
  definitions and top-level free-function signatures. Headers, macros, compile
  conditions and templates are only as complete as the current CDT outline.
  File-module functions have no inferred call edges or execution order.
  Namespaced free functions are not yet included in the module box.
  Generated relations are **not** claimed.
- This preview does **not** infer associations, inheritance, actual calls,
  RTOS messaging, state-machine transitions, or a sequence of execution.
  C function-pointer calls and indirect callbacks cannot be inferred safely
  from basic outlines.
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
- `tests/eclipse/UmlClassSmoke.java`: installed UI view registration,
  native Canvas projection, export availability and widget disposal.
- `scripts/verify_site.py`: checks all five implementation classes are
  present in the published bundle.
- Still required before promising release: exact-head Linux Tycho/p2 and
  installed SWT smoke, Windows tests, real C and C++ source acceptance
  (including a regular Zephyr `main.c` with no structures), large-file bounds
  and a clean-host update verification.

## Next reviewed increments

**Milestone 2:** CDT index-backed cross-file class relationships and
call graph, with exact links and uncertainty/truncation indicators.

**Milestone 3:** Zephyr API/object interaction maps (threads, semaphores,
mutexes, queues, work items) generated only when source or build artefacts
actually establish a relationship. Distinguish static usage from observed
runtime traces.

**Milestone 4:** State and sequence diagrams derived from explicit source
constructs or captured trace evidence, with provenance and navigate-to-source.
Do not infer an execution order from calls alone.

The long-term architecture should reuse the headless Evidence Graph rather
than create a parallel set of claims in a standalone UI.
