Complete Zephyr Eclipse Workbench as a cohesive, attractive, reliable public release.

Repository: https://github.com/niwantha33/zephyr-eclipse-workbench

## Mission

You are responsible for implementing, integrating, testing and packaging the release—not merely producing a plan or adding documentation.

Build a tool that engineers want to recommend because it makes Zephyr development easier to understand:

- Get a project running with minimal setup.
- Configure hardware visually.
- Build, flash and debug reliably.
- Understand configuration errors and firmware failures.
- Inspect RTOS behaviour and peripheral registers.
- Share useful investigation results.

Preserve Eclipse as the main IDE. Structure reusable analysis logic so another frontend can use it later, without building a second frontend during this release.

Do not restart the project or replace working components unnecessarily.

## Execution rules

1. Read AGENTS.md and applicable repository instructions first.
2. Inspect the current main branch, active pull requests, CI, release process and implementation before editing.
3. Distinguish implemented features, incomplete implementations, documentation-only proposals and obsolete documentation.
4. Create a release completion checklist tied to actual code, tests and evidence.
5. Work through the entire checklist autonomously. Do not stop after the first feature or after writing a plan.
6. Make routine implementation decisions yourself. Ask only when credentials, destructive actions, essential product decisions or unavailable hardware genuinely block progress.
7. Use parallel agents for independent areas when available. Assign clear ownership and integrate changes carefully.
8. Keep changes in reviewable commits and an appropriate integration branch. Preserve unrelated user changes and existing work.
9. Reuse Zephyr, CDT, GDB, West and established libraries wherever suitable.
10. Do not introduce placeholder panels, fake measurements, decorative controls without behaviour or silent failures.
11. Never report a feature as verified solely because it compiles.
12. If a task needs hardware that is unavailable, finish all independent implementation and automated checks, provide a reproducible hardware test, and mark that specific result unverified.
13. Do not abandon remaining independent tasks because one task is blocked.
14. Follow repository approval rules for merging and publishing. Prepare the complete release before requesting any required final approval.

## 1. Establish the release baseline

Audit:

- Project creation and import.
- Managed host tools and SDKs.
- Board, SoC, core and runner selection.
- Build profiles and output discovery.
- Flash and debug lifecycle.
- Kconfig Studio.
- Devicetree and hardware configuration.
- SoC Registers.
- RTOS tracing and Trace Compass.
- Logs, fault analysis and existing observability code.
- Installation, updates and recovery.
- Windows and Linux compatibility.
- Dependency licences and redistribution rights.

Record current CI failures and fix their causes. Do not disable meaningful checks to obtain a green build.

Create one current release-status document. Link every claimed capability to its implementation and validation evidence. Reconcile contradictory README sections.

## 2. Deliver a coherent native Eclipse experience

Use native Eclipse/CDT commands, menus, selection models, progress reporting and debugger integration.

Provide an understandable main workflow:

Open or create project → select target → configure → build → flash → debug → investigate.

Requirements:

- Build, Flash, Debug, Stop and Configuration controls are discoverable.
- Enable actions only when their prerequisites are satisfied.
- Clearly show the active project, exact board/core and build profile.
- Follow the selected project consistently across views.
- Provide useful empty states and actionable error messages.
- Use background jobs for expensive work; keep SWT responsive.
- Support cancellation, keyboard navigation, high-DPI displays, and light/dark themes.
- Reuse existing Eclipse icons where appropriate; use consistent original icons for missing actions.
- Avoid redundant custom navigation and excessive dashboards.
- Explain missing prerequisites next to the action that needs them.

Inspect rendered screens or run UI checks where possible. Compilation alone is not UI verification.

## 3. Make installation and updates dependable

Provide a documented, repeatable installation from a clean supported system.

- Produce the correct installable p2 archive and release assets.
- Detect and reuse compatible tools.
- Isolate managed tools without damaging existing installations.
- Show download and installation progress.
- Handle partial downloads and interrupted setup safely.
- Verify downloaded assets using available trusted checksums or equivalent integrity controls.
- Pin versions where reproducibility requires it.
- Explain dependencies and licence requirements before installation when applicable.
- Make update failures recoverable.
- Distinguish source archives from the actual installer.

Validate Windows and Linux installation/package workflows in CI where feasible. State the exact environments tested. Do not invent macOS support.

## 4. Finish project and build workflows

Support:

- New applications from suitable Zephyr samples.
- Existing applications selected directly or discovered within a parent directory.
- Multiple candidates presented for selection.
- Paths containing spaces and supported non-ASCII characters.
- Multiple projects and independent build directories.
- Exact board qualifiers, cores and external board roots.
- Debug and Release profiles with visible configuration differences.
- Profile-specific overlays, configuration fragments and CMake arguments.
- Portable source projects and appropriate machine-local settings.
- Accessible ELF, HEX, BIN, map and compile database outputs when generated.
- Clear clean, rebuild and pristine-build actions.

Detect stale or incompatible build configuration before flashing or debugging.

Never silently select a different core or overwrite existing source files.

## 5. Finish cross-vendor flash and debug reliability

Use Zephyr runner metadata and native CDT debugging.

Cover supported workflows across TI, STM32, Nordic and Espressif. Discover other vendors through Zephyr metadata without implying universal hardware validation.

Requirements:

- Resolve tool executables and scripts consistently.
- Treat vendor OpenOCD variants as explicit capabilities; do not assume all distributions are interchangeable.
- Support configured OpenOCD, J-Link, pyOCD, LinkServer and other available runners through a maintainable adapter boundary.
- Show appropriate installation guidance for unavailable tools.
- Respect third-party licence and user-installation requirements.
- Validate target/build compatibility where reliable evidence exists.
- Report ambiguous target identity honestly.
- Avoid unsafe force-flash defaults.
- Prevent conflicting access to the same probe or target.
- Avoid port collisions.
- Use bounded startup and connection timeouts.
- Preserve useful diagnostic output.

Fix debugger termination thoroughly:

- Stop owned GDB and debug-server processes.
- Release owned ports and resources.
- Clean up after launch failure and cancellation.
- Avoid killing unrelated processes.
- Restore action states.
- Permit immediate relaunch.
- Handle disconnects without freezing Eclipse.

Test repeated launch/terminate cycles and failed-start cleanup. Cover subprocess ownership and Windows process-tree behaviour.

## 6. Complete Kconfig Studio

Provide an approachable editor backed by the selected Zephyr checkout.

Include:

- Hierarchical navigation and fast search.
- Symbol names, descriptions and help.
- Current and effective values.
- Dependencies, visibility and value origin where available.
- Explanations for unavailable or overridden options.
- Navigation from errors to relevant symbols.
- Clear distinction between editable inputs and generated outputs.
- Previewable changes and safe saving.
- Correct handling of profile-specific configuration.
- Refresh after external edits and checkout changes.

Use Zephyr Kconfig semantics. Do not create a simplified parser that silently disagrees with the build.

## 7. Complete Devicetree and chip configuration

Build a visual hardware configuration workflow based on real data.

Required experience:

- Browse board, SoC, buses, devices and peripherals.
- Select a peripheral from the tree or supported chip/package view.
- Open typed parameters for ADC, GPIO, UART, I²C, SPI, PWM and other supported bindings.
- Show pin assignments and detected conflicts.
- Explain binding requirements and configuration origins.
- Navigate between source DTS, overlays and the resolved Devicetree.
- Preview generated overlay changes.
- Preserve existing user content.
- Rebuild or reconfigure when necessary and show resulting diagnostics.

Important engineering constraints:

- Identify the exact chip package before displaying physical pin locations.
- Do not infer package pin numbers from generic GPIO identifiers.
- Treat pin capabilities, package layout and board wiring as separate information.
- Use authoritative, redistributable metadata.
- Provide a useful tree/table fallback when package data is unavailable.
- Implement vendor-specific pinctrl handling behind explicit adapters.
- Distinguish static validation from hardware verification.

Deliver at least one complete visual ADC workflow on a supported board, including applicable parameters, generated changes and a runnable example.

Extend functionality through bindings and adapters rather than hard-coding a collection of demonstrations.

## 8. Add guided configuration diagnosis

Provide a “Diagnose” action for the active build and selected peripheral.

Connect available evidence from:

- Configuration inputs and effective Kconfig.
- Resolved Devicetree.
- Driver selection.
- Pin assignments.
- Build metadata and diagnostics.
- Device initialisation logs.
- Runtime evidence when available.

Each finding must show:

- What was observed.
- Where the evidence came from.
- Whether the conclusion is confirmed or only a possibility.
- A specific next action.
- A preview for any proposed file change.

Use deterministic checks for known conditions. Optional AI explanations must remain separate from the core diagnostic engine.

Do not automatically rewrite project settings based on uncertain guesses.

## 9. Complete SoC register inspection

Audit and reuse relevant work from:

https://github.com/niwantha33/mcu_register_viewer

Check its licence and architecture before integration.

Finish:

- Exact-device SVD selection.
- Peripheral/register/bitfield navigation.
- Search and watches.
- Hexadecimal, decimal and bit views.
- Enumeration descriptions.
- Changed-value highlighting.
- Reset-value comparison.
- Clear CPU-register versus peripheral-register separation.
- Selection of the correct debug session and suspended context.

Respect register access semantics:

- Avoid indiscriminate reads of registers with read side effects.
- Treat writes, write-one-to-clear fields and read-modify-write operations correctly.
- Do not imply reset values are universally valid for the current operating state.
- Surface unsupported SVD constructs.
- Prevent stale values from appearing live.

Add meaningful SVD and debugger integration tests.

## 10. Deliver usable RTOS analysis

Complete an integrated workflow around available Zephyr tracing mechanisms.

Include:

- Guided tracing configuration.
- Supported RAM snapshot capture.
- Metadata and stream validation.
- Trace Compass integration with useful installation/opening diagnostics.
- Task names and scheduling information when captured.
- CPU usage and task execution views when sufficient timing evidence exists.
- Stack information with clearly described measurement methods.
- Logs correlated with traces only where timestamps permit it.
- Clear capture status, buffer capacity and data-loss indicators where detectable.

Do not:

- Present debugger samples as continuous measurements.
- Calculate trustworthy CPU percentages from incomplete scheduling evidence.
- Claim deadlock or priority inversion detection without sufficient events.
- Claim real-time performance guarantees from instrumented traces.

Show unavailable analysis with an explanation and the prerequisites needed to enable it.

Reuse existing trace formats and tools rather than inventing an unnecessary recorder.

## 11. Finish fault and core-dump investigation

Use Zephyr’s existing mechanisms and GDB support where practical.

Implement:

- Import supported serial fatal logs and core dumps.
- Associate captures with the matching ELF and firmware identity.
- Reject or prominently flag mismatched or unverified firmware.
- Resolve addresses to source/functions where symbols permit.
- Present available call stacks and thread context.
- Decode relevant architecture-specific fault registers.
- Explain captured evidence in plain language.
- Export an investigation report.

For supported Cortex-M targets, handle stacked PC/LR/SP, exception context and applicable fault status registers correctly. Account for architecture differences and incomplete captures.

Keep other architectures behind explicit capability adapters. Do not apply Cortex-M decoding to Xtensa or RISC-V.

Differentiate:

- Confirmed hardware fault condition.
- Plausible software cause.
- Missing evidence.

Do not promise a guaranteed root cause.

## 12. Provide safe peripheral test workflows

For explicitly supported board/peripheral combinations, provide small tests and example generation.

Start with:

- GPIO.
- ADC.
- I²C.

Then add UART, SPI and PWM where the implementation and validation support them.

Requirements:

- Show test prerequisites, pin assignments and required wiring.
- Preview generated source and configuration.
- Clearly identify when a test replaces currently flashed firmware.
- Require explicit initiation of hardware-changing actions.
- Use bounded timeouts and useful stop controls.
- Record observations and test conditions.
- Do not infer electrical correctness from software success alone.
- Avoid unsafe generic pin toggling or unrestricted bus probing.

Keep tests separate from production application source unless the user explicitly chooses integration.

## 13. Add investigation export and comparison

Create a portable investigation package containing selected:

- Firmware/build identity.
- Tool and Zephyr versions.
- Board and runner information.
- Configuration and resolved Devicetree.
- Logs, trace and crash information.
- Diagnostic findings.

Provide an inclusion preview and exclude credentials and unrelated files.

Allow another installation to inspect a package offline where dependencies permit.

Add useful comparisons between working and failing builds:

- Configuration differences.
- Devicetree differences.
- Tool/version differences.
- Memory-size changes.
- Comparable runtime metrics when supported.

Preserve provenance. Do not claim a changed setting caused a failure merely because it differs.

## 14. Include a convincing first-use demonstration

Ship a short guided experience using clearly labelled captured data and suitable runnable examples.

Let a new user:

1. Open a recorded failure.
2. Reach a relevant source location.
3. Understand a configuration problem.
4. Inspect a working-versus-failing comparison.

Never show recorded values as live target data.

Provide concise examples and screenshots showing real functionality. The first-use experience must explain what users gain within a few minutes.

## 15. Validate systematically

Use appropriate automated tests, UI checks and hardware procedures.

Cover:

- Existing regressions.
- Clean setup and project import.
- Configuration generation and preservation.
- Cross-vendor build cases.
- Missing dependencies.
- Stale build detection.
- Process cleanup and repeated debug sessions.
- Malformed SVD, trace and core-dump inputs.
- Firmware identity mismatch.
- Analysis correctness using known fixtures.
- Packaging and update metadata.

Use mocks for subprocess and transport failures, while clearly separating those results from physical hardware tests.

Record evidence separately for:

- Discovered.
- Configured.
- Built.
- Flashed.
- Debugged.
- Analysed.

A passing build does not establish the other states.

## 16. Prepare an honest public release

Deliver:

- Versioned installable assets.
- Fresh-install and update instructions.
- Supported host/board/probe capability matrix.
- Screenshots of actual implemented screens.
- Short demonstration instructions.
- Release notes.
- Known limitations and recovery guidance.
- Dependency/licence notices.
- Useful repository description and topics, following repository permissions.
- Clear issue templates for reproducible bug reports.
- A small set of complete examples.

Present the product through concrete outcomes: configure a peripheral, run a test, investigate a crash and share evidence.

Do not claim “all SoCs supported,” “production ready,” “better than every IDE,” certification or measured time savings without evidence.

Do not publish announcements, contact people or send messages externally.

## Completion criteria

The task is complete when:

1. The requested areas have functioning integrated implementations, with explicit capability limits.
2. Existing workflows remain working.
3. Required automated checks pass.
4. Release assets build and have been inspected.
5. Hardware-tested capabilities are distinguished from unverified ones.
6. Documentation matches the shipped implementation.
7. The changes are committed and available in a reviewable pull request.
8. The release is published if authorised, or fully staged for the required final approval.

If any requirement genuinely cannot be completed, identify the precise blocker and finish everything else. Do not disguise missing implementations as roadmap entries.

Final response:

- Release version and pull-request link.
- What users can now do.
- Validation performed.
- Exact outstanding blockers or hardware checks.
- Installer/release location.
- Any final approval required.

Begin by inspecting the repository, establish the checklist, and continue through implementation, integration and release preparation. Do not stop at planning.
