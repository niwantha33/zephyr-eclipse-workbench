# Workbench 0.1.15 — extracted Python helper repair

## Reported Windows failure (0.1.14)

Building an imported TI MSPM0G3507 project produced:

```text
FileNotFoundError: ...\\tools\\board_tools.py
  from board_dependencies.py: spec.loader.exec_module(legacy)
```

The native Eclipse bundle is an OSGi JAR. `FileLocator.toFileURL()`
extracts a **single** `board_dependencies.py` entry from that JAR;
the companion `board_tools.py`, `setup.py`, board catalog and pinned
JSON dependency locks are not necessarily in the extracted directory.

## Fix

The native `ZephyrProjectBuilder` now uses `BoardTools.stageTools`
to read *all seven* sibling Python/JSON resources directly from the
installed plug-in bundle and copy them into a version-specific staging
directory under the **managed setup root**:

```text
<managed-setup-root>/.zew-board-tools/<installed-bundle-version>/
    board_dependencies.py
    board_tools.py
    hardware_catalog.py
    setup.py
    managed_sitecustomize.py
    sdk-toolchains-windows-1.0.1.json
    esp32s3-python-lock.json
```

- Invoked automatically under the existing `.zew-install.lock` before
  native Eclipse Build Project; no user-run scripts or manual file copying.
- Each staged helper is checked against its corresponding bundled bytes.
  A missing/corrupt partial helper is repaired atomically on the next build.
- No application source, existing SDK/compiler or pinned West module is
  overwritten. Other versions' tool staging remains untouched.
- Missing resources and symlinked staging paths fail clearly.
- A test reproduces exactly the isolated-single-file crash and checks that
  the staged complete set loads with Python isolated mode.
- Packaged JAR verification ensures that the compiled builder includes the
  versioned sibling staging logic and all required resources.

## Header indexing

Zephyr headers shown as unresolved in Eclipse after an unsuccessful build
do not establish that the C source is incorrect. `EditorIntegration`
derives **actual** include paths and preprocessor macros from the successful
Zephyr build's `compile_commands.json`. After the board build succeeds,
the Workbench schedules a CDT refresh automatically.

### Acceptance checklist on Windows

1. **Zephyr → Check for Workbench Updates**; install 0.1.15 and restart.
2. Right-click existing `MyZephyr2` → **Properties → Zephyr**.
   Verify `lp_mspm0g3507/mspm0g3507` (or the chosen exact board).
3. Right-click the project → **Build Project**. The Zephyr console must
   show the dependency-selection details, not a traceback concerning
   `board_tools.py`.
4. Confirm that the pinned ARM compiler and `cmsis` / `hal_ti` were
   checked or installed; Zephyr must generate `zephyr/zephyr.elf`.
5. The **Zephyr Editor** console should report refreshed source settings.
   Open `src/main.c` and confirm `zephyr/kernel.h` and
   `zephyr/sys/printk.h` resolve without unknown-header warnings.
6. If another failure occurs, retain the **entire Zephyr console**, from
   dependency selection through the first error; the generic Eclipse
   “Tool exited with code 1” message alone is insufficient.

Linux GitHub CI validates portable regression tests and Eclipse packaging,
but it does **not** prove Windows-specific dependency installation or
physical-board compilation.
