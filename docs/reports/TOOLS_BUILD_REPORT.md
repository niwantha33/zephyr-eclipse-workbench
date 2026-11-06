# Tools Manager 0.1.4 validation

Development package: 0.1.4.202610232226, built 2026-10-23.

| Check | Result |
|---|---|
| Java command/process core | 31 checks passed, including explicit process environment and PATH precedence without modifying the parent |
| Private updater transport/staging | 27 checks passed |
| Python inventory | 12 tests passed: protocol encoding, invalid explicit paths, failed/old tools, SDK recommendation and compiler failure, requirements markers/includes/cycles, literal arguments, real missing-workspace scan |
| Actual venv creation | Created isolated Python with bundled pip in a temporary path containing spaces and Unicode; verified prefix and cleared incomplete marker after success |
| Maven/Tycho | Five modules built successfully with OpenJDK 21.0.2 / Maven 3.9.11 / Tycho 5.0.3 |
| Packaging | Original licences, metadata, handlers, ToolsDialog and Python helper verified; packaged helper exactly matches source |
| Provisioning | Headless Eclipse 4.41 upgrade 0.1.3 to 0.1.4; feature root replaced |

Total: 70 automated Java/Python checks. Requirement-parser tests use the optional
`packaging` module; they are explicitly skipped if the test interpreter lacks it.
The local run included all 12 inventory tests with no skips.

Package ZIP SHA-256:
`0ac7b0e68e6b92e34699cdbbe10bc4f4441f3c37942f3e20f05e7c1e14192a29`.

Desktop layout, Windows registry discovery and real ESP32-S3 inventory/build
bindings still require Windows validation. Headless provisioning does not render
the manager dialog. SDK/GCC version commands establish tool presence, not complete
board compatibility or licence eligibility. External package downloads, repair,
fresh West workspaces and project/debug wizards are not implemented in this slice.

Upstream references reviewed for the compatibility inventory:

- [Zephyr v4.4.0 Python minimum](https://github.com/zephyrproject-rtos/zephyr/blob/v4.4.0/cmake/modules/python.cmake)
- [Zephyr v4.4.0 SDK recommendation](https://github.com/zephyrproject-rtos/zephyr/blob/v4.4.0/SDK_VERSION)
- [SDK layouts and install command implementation](https://github.com/zephyrproject-rtos/zephyr/blob/v4.4.0/scripts/west_commands/sdk.py)
- [Base Python package requirements](https://github.com/zephyrproject-rtos/zephyr/blob/v4.4.0/scripts/requirements-base.txt)

Original helper code is Apache-2.0. No SDK, vendor firmware or upstream tool
binaries are redistributed by the workbench package.
