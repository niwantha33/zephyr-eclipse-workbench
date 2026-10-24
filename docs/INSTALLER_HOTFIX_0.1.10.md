# Windows CMake path hotfix 0.1.10

The Windows setup reached CMake Kconfig target creation, then failed with
`Invalid character escape '\S'` in `zephyr_custom_target_shared`.
The workbench passed `C:\Sandbox\Zephyr_Setup\python\python.exe` and Ninja's
path directly into CMake definitions. Zephyr's macro expands its argument list
again, interpreting backslashes as CMake escapes. This is workbench argument
handling; uninstalling a different Zephyr installation does not correct it.

## Change

- Pass Python and Ninja as forward-slash `:FILEPATH` CMake definitions, in both
  the setup build and native Eclipse managed project builder.
- Use forward slashes for managed build/application arguments and CMake-facing
  Zephyr source/SDK environment paths. Keep native PATH construction unchanged.
- Update the tools manager's SDK environment override for the same convention.
- Preserve existing tools, application source, lock files and verified downloads.
  Explicit corrected definitions replace old cached values on retry.

## Validation

- Use actual CMake 3.31.10 with the same `add_custom_target(${ARGN})` macro pattern
  used by Zephyr 4.4.0. The original Windows Python/Ninja values reproduce the
  exact `Invalid character escape '\S'` error.
- Normalize paths using the new installer helper and use typed FILEPATH values.
  Configure succeeds against both the original failed cache and a fresh build
  directory. Fixture directories include spaces and Unicode.
- All 38 Python inventory/setup/CMake checks, 32 Java core checks, 27 update checks and
  11 bootstrap checks pass (108 total). New checks cover raw Windows drive paths,
  single argument preservation and CMake definitions without backslashes.
- The real-parser regression is checked in as `tests/tools/test_cmake_paths.py`;
  set `ZEW_TEST_CMAKE` to the executable when it is not on PATH.
- Maven/Tycho build and packaged helper/metadata/licence verification pass.
  All three installable JAR versions match 0.1.10.202610241100.
- Headless Eclipse provisioning upgrades 0.1.9.202610241002 to
  0.1.10.202610241100 and replaces the old root feature.
- Update-site ZIP SHA-256:
  `cf6bb68c6b5630450d3690026a2b79c4413bfeeeb2a14f19e8151c8d734d0dc5`.

These tests run on Linux and exercise CMake's real parser with Windows path
strings. Complete native Windows first-build validation remains pending.

## Retry

Install 0.1.10 through **Zephyr > Check for Workbench Updates**, restart Eclipse,
then open **Set Up Zephyr** with `C:\Sandbox\Zephyr_Setup` and finish again.
Keep the installation and project; no deletion is required for this error.
