<!-- SPDX-License-Identifier: Apache-2.0 -->
# Quick Help — offline first edition

**Scope:** The first catalogue covers `k_thread_create`, `gpio_pin_toggle_dt`, `k_timer_start`, `k_sem_take`, `k_mutex_lock`, and `k_msgq_put`. It supports exact API names, prefixes, aliases like `create_task`, phrases like `wait for semaphore`, and bounded spelling tolerance. Search phrases are described as intentions, never falsely presented as API symbols.

**Open:** Zephyr > Help and Updates > Quick Help; alternatively Window > Show View > Other > Zephyr Quick Help, or select text in a text editor and right-click > Quick Help for Selection. The panel is dockable and retains the standard Eclipse view lifecycle. Search is served entirely from the bundled local catalogue. No network request occurs until the user clicks **Open Documentation**, which launches the official source link in their browser. **Copy Example** copies the complete `main.c` example only.

**Applicability:** Reference material is authored against official Zephyr **v4.4.0** headers (`kernel.h` and `drivers/gpio.h`); an exact `VERSION` match is labelled, a different version is labelled **MISMATCH**, and any missing, modified or unparseable release is labelled **UNKNOWN**. A matching version alone is not proof that an example builds on a particular board. Every item states hardware and configuration assumptions. GPIO requires a usable `led0` alias; code fails at compile time if the alias is absent. No hardware support is inferred from keyword searches.

**Content format:** `plugins/io.github.niwantha33.zew.core/quick-help/v1/index.txt` declares `format: 1` and enumerates `.help` records. Records contain unique `id`, `title`, `category`, pinned `version`, synonyms, signature, official `source`, related APIs, and five required sections. The reader rejects unsupported formats, missing fields, duplicate IDs, unpinned/unofficial URLs, oversized files and unknown section names. To add an API, verify it from the desired official versioned headers, add a record and index entry, and add search tests. Never silently re-label a v4.4.0 record as another release.

**Attribution and redistribution:** Official Zephyr Project source files used for API metadata are Apache-2.0 licensed. This package does not redistribute upstream headers or samples; the summary text and short C examples are originally authored. Links point to the pinned official project revision. The Workbench's existing Apache-2.0 licence applies to this original addition. Review source/version correctness before enlarging the catalogue.

**Optional future AI:** `QuickHelpExplanationProvider` is an internal interface only. No provider is installed, no key is requested, no AI button exists, and no project data is transmitted. Any later implementation must retrieve and show real source evidence first, require explicit opt-in before sending project information externally, and separate generated text from official references.

**Validation:** Run `python scripts/test_core.py` after applying the patch; it executes pure-JDK `QuickHelpCatalogTests` with the other Java core tests. For standalone validation of the changed core only, compile with JDK 21 and put the core plugin's root on the classpath so `quick-help/v1/index.txt` is accessible. IDE view/packaging verification requires an installed Eclipse/Tycho environment. Six examples have not been built against Zephyr SDK/boards in the delivery environment; label this limitation, do not claim firmware verification.

**Versioned source references:**
- `https://github.com/zephyrproject-rtos/zephyr/blob/v4.4.0/include/zephyr/kernel.h`
- `https://github.com/zephyrproject-rtos/zephyr/blob/v4.4.0/include/zephyr/drivers/gpio.h`

## Reader-friendly UI (presentation iteration)

- The panel provides a single prominent search field (no task-shortcut buttons), a compact two-column API result browser,
  and a short title/summary instead of showing every documentation field in one text dump.
- The detail pane has **Overview**, **C example**, and **API reference** tabs. It includes
  version-state labels (UNKNOWN/MISMATCH/MATCH), colour-assisted section headings, a read-only
  C code viewer with basic syntax highlighting, copy and official-source buttons.
- **A− / A+** adjusts text sizes inside this view (not globally); the splitter rotates vertically
  in narrow docks and overview/reference pages scroll with wrapping text. Dark/light colours
  adapt to the active Eclipse parent background. All created fonts and colours are disposed.
- Every example remains marked as **not independently compiled for the selected board**.
  No AI, network lookup, source edits, or inferred build/flash support was added.
- Native SWT smoke now verifies all three learning tabs, the offline search and read-only code.
  A separate integration test prevents reintroduction of the monolithic text dump.

**UI verification:** The Eclipse/Tycho native integration workflow and an actual human visual
review of narrow/dark/high-DPI modes must pass on the PR head before approval.

## Search-first simplification (2026-11-24)

Removed the six preset task buttons to reduce visual clutter. Type the task or API name in the single search field. Existing offline catalogue, matching logic, results, documentation tabs, copy example, pinned sources and project version states remain unchanged.
