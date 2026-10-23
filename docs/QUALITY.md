# Product quality and comparison targets

Status: proposed acceptance targets; no benchmark or comparative superiority result exists.
Updated 2026-10-23.

## Ambition and scope

Make the common Zephyr workflow easier, more reliable and more explainable than the same workflow in CLion and VS Code configurations. Measure that claim per workflow before publishing it.

General C++ refactoring, language tooling and extension ecosystems are broader than this project's scope. Use and improve Eclipse CDT instead of claiming to outperform every capability of other IDEs.

## Acceptance targets

| Area | Proposed acceptance target | Evidence method |
|---|---|---|
| Existing setup import | Import a working workspace and complete first build without editing internal Eclipse files or reinstalling existing tools | Fresh Eclipse installation and scripted task checklist |
| New application | Select board and create/build Hello World within 10 minutes after required downloads/tools are ready | Timed session; report download and build time separately |
| Daily workflow | Build, clean, Kconfig, output reports and launch actions available without manually typing commands | UI walkthrough for each supported workflow |
| Hardware selection | Show exact board/revision/core/qualifiers; cached search responds within 1 second on a declared reference machine | Timed cached queries on recorded catalogue size; cold discovery reported separately |
| UI responsiveness | No network, dependency resolution or tool process blocks the UI thread; long actions have progress and cancellation | UI job tests and profiling during build/discovery/update |
| Build parity | Equivalent inputs produce equivalent firmware behaviour and build settings from terminal and Eclipse | Compare invocations, configurations and generated outputs, accounting for nondeterministic metadata |
| Build overhead | Aim for no more than 10% median warm-build overhead over direct West invocation on the same inputs | At least five runs each, same machine/cache; report absolute time and variation |
| Code navigation | Definitions and completion resolve application, Zephyr APIs, drivers and generated configuration in representative fixtures | CDT integration tests and manual checks; no unsupported claim for all C/C++ code |
| Diagnostics | Missing tools and representative compiler/Kconfig/Devicetree failures identify the cause and a concrete next action | Curated failure suite; do not hide raw logs |
| Process reliability | Failure, cancellation, reconnect and retry leave no owned orphan server/process or false success state in the tested suite | Lifecycle tests, process/port checks and repeated operations |
| Project portability | Clean clone builds at a second path without personal absolute paths | Fresh-host/path checks including spaces and Unicode |
| Update reliability | Eligible update, restart and recovery preserve application files and pinned firmware dependencies | Fault-injected upgrade/recovery suite in UPDATES.md |
| Accessibility | Core workflows keyboard operable, labels readable at OS scaling, status not conveyed only by colour | Keyboard and screen-reader review on supported host UI |
| Offline use | Existing projects can build with cached installed dependencies; offline update failures do not block editing/building | Disconnected-host check; disclose missing dependency requirements |

These targets are design goals until baselines and test fixtures are established. Revisit thresholds with recorded evidence rather than silently marking them passed.

## Comparative benchmark

Compare actual configurations and versions, not product names alone:

| Workflow | Eclipse workbench | CLion | VS Code |
|---|---|---|---|
| Reuse existing workspace | Measure steps, time and failed attempts | Same task and workspace | Name exact extension set |
| Create board application | Same sample and target | Same sample and target | Same sample and target |
| Diagnose a Kconfig dependency error | Measure correct resolution and help | Record actual result | Record actual result |
| Change board/core/configuration | Verify separate build outputs | Verify same behaviour | Verify same behaviour |
| Flash and debug | Same board, probe and application | Same board/probe | Same board/probe |
| Update and recover | Record supported scope and preserved state | Record supported scope | Record supported scope |

Freeze host, versions, application commit, board/probe, network conditions and compiler inputs. Report setup time separately from dependency downloads. Do not disable useful competing features or tune only one environment.

Include at least one Arm and one Xtensa target before a general multi-vendor comparison. Add RISC-V before architecture-wide claims. Hardware claims require physical evidence.

Collect task completion, failures, recovery actions, time, manual configuration and diagnostic clarity. User-study findings require actual participants; developer timing alone is not a usability study.

## Release quality gate

- Publish only results actually measured, including regressions and limitations.
- Keep discovery/build/flash/run/debug status separate per board.
- Document UI and recovery behaviour from the released build.
- Treat reproducibility, cancellation and recovery as release features.
- Maintain an issue backlog for failed quality targets and identify deferred criteria in preview release notes.
- Require applicable v1.0 targets to pass before the stable designation.

Initial priority: a dependable install/create/build workflow, then configuration, then verified hardware/debug, with advanced polish built on that foundation.
