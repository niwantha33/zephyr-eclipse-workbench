<!-- SPDX-License-Identifier: Apache-2.0 -->
# Build failure investigator

Managed Eclipse project builds now save configure/compiler merged output in a
unique `.zew/local-build-logs/<build-key>/build-*.log` file. The console prints its path. Logs
survive a pristine rebuild because they are outside the generated build directory.
These local logs may contain private source paths and diagnostics; inspect them
before sharing. They are not automatically uploaded. Dependency preparation and
host-tool inventory precede or bypass this capture and remain in the console.

On failure, the error status and console show a bounded summary of actual compiler,
linker and CMake errors, their log line numbers, up to four following context
lines, explicit Ninja `FAILED:` targets and the following reported command.
Normal parallel progress is not evidence of which source failed. A wrapper-only
log reports unknown cause and requests full evidence instead of blaming the last
file. Terminal colours are removed from summaries. Existing Problems markers
continue handling compiler source navigation.

A detailed single-job retry is only run after the user clicks the rebuild action.
It reuses the project's selected managed environment. Builds execute project
hooks; reviewing a log does not execute any.
No source/configuration repairs are made by this investigator.

## Native Workbench workflow

1. Select the affected Zephyr project in Project Explorer.
2. Open **Zephyr → Configure & Build → Investigate Build Failure…** (also under
   **Inspect & Diagnose**). A failed captured managed build opens this view
   automatically.
3. Read the saved final-attempt report. **Open complete log** opens the log in
   Eclipse; existing compiler Problems markers still navigate to source.
4. If the compiler diagnostic is missing, click **Rebuild with detailed output**.
   This schedules the same managed build using the selected Python/West, SDK,
   board, core and profile, with verbose output and one build job. This is a
   real build, so it runs the project's normal build hooks. It does not perform
   a pristine deletion or change stored configuration.
5. **Investigate saved log…** analyses an explicitly selected UTF-8 log locally;
   imported logs have no bound project, so their rebuild control stays disabled.
   **Copy report** copies the displayed findings and provenance.

Failures are indexed separately by exact build directory. A later successful
build retains the previous failed attempt, labelled as recorded evidence rather
than the current result. A queued detailed build refuses a changed build profile.
Recovered configure attempts do not displace the final attempt's report; the
complete log retains every captured attempt. Saved reports are bounded and the
reader is cancellable; switching input or closing the view invalidates pending
results. Imported logs are limited to 64 MiB.

The local evidence directory is excluded from project environment snapshots and
matches newly generated projects' existing `.zew/local*` Git ignore rule. No
application ignore file is edited. Preparation failures before configure/compile
capture remain in the existing console/error status.

## Headless saved-log inspection

From this repository, with a JDK installed (Java 21 is the Workbench baseline):

```sh
java plugins/io.github.niwantha33.zew.core/src/io/github/niwantha33/zew/core/BuildFailureInvestigator.java saved-build.log
java plugins/io.github.niwantha33.zew.core/src/io/github/niwantha33/zew/core/BuildFailureInvestigator.java --json saved-build.log
```

JSON includes the SHA-256 of exactly the log bytes consumed, a deterministic
summary and unknown producing-tool version/root cause. It observes reported
errors, not independently proven causal relationships. Exit 0 means inspection
completed, not that firmware built; invalid arguments return 2 and unreadable
input returns a Java error/nonzero status. Logs/JSON include private paths if
present in diagnostics; this is local inspection rather than a redacted bundle.

## Validation

`BuildFailureInvestigatorTests` and `BuildFailureLogsTests` run through
`scripts/test_core.py`: wrapper-only failure, Windows GCC, parallel progress,
Ninja target/command, ANSI, linker, multiline CMake, retained first diagnostic,
attempt reset, bounded deterministic JSON, exact input hash, per-build storage,
failed-attempt selection, duplicate protection and symlink exclusions.
Project-version tests verify diagnostic output never changes a source snapshot.

`scripts/build_failure_smoke.py --eclipse DISPOSABLE_ECLIPSE --java-home JDK --site
UPDATE_SITE` exercises the installed native view in a display-capable environment
without requiring a debugger. Package 0.1.21.202611050611 passed all 12 native
view/action/command-registration checks locally. The full core suite passed,
including 440 Python tests (4 skips) and 49 new Java investigation/storage checks.
The wider existing editor/debug smoke remains
unchanged and separately required. Real Windows MSPM0 acceptance remains pending.

The detailed rebuild uses upstream West's documented verbosity and build-tool
options: [Building, Flashing and Debugging](https://docs.zephyrproject.org/latest/develop/west/build-flash-debug.html).
