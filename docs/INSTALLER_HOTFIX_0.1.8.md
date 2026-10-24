# Installer hotfix 0.1.8

The Windows field retry passed Docopt installation and SDK/source setup, then
failed in `scripts/list_boards.py`: `No module named 'list_hardware'`.
The executable and source both belonged to the new managed installation.
Removing a previous Zephyr installation cannot correct this failure.

## Change

Embedded Python's `python313._pth` enables isolation and omits the executed
script's directory. Zephyr's board, Kconfig and devicetree scripts use sibling
imports. Install a small `python/sitecustomize.py` startup hook that restores
only the parent of an existing `.py` script under this managed installation's
`workspace/zephyr/scripts` tree, including its subdirectories. Resolved paths
prevent outside symlinks from granting an unrelated directory.

Keep the explicit `._pth` list, ignored external Python environment variables,
and exclusion of arbitrary cwd and user packages. Do not modify Zephyr source.
Reinstall the hook on setup retries. The Docopt source build wrapper from 0.1.7
continues to grant its separately verified temporary source directory.

Before creating/building the application, execute Zephyr's board-list script
with the managed interpreter and explicit board/SoC roots. A failed check stops
setup before it can mark the environment ready.

Dependency versions and hashes are unchanged; no new third-party dependency.

## Validation

- All 34 Python inventory/setup tests pass, including actual isolated child
  interpreter startup, sibling and nested script imports, outside-tree exclusion,
  paths with spaces/Unicode, retry repair and preflight failure readiness guards.
- All 31 Java command/process, 27 update and 11 bootstrap checks pass: 103 total.
- A fresh checkout at pinned Zephyr commit
  `684c9e8f32e4373a21098559f748f06915f950c9` reproduces the exact
  `list_hardware` failure under `python -I`. With the hook, the same command lists
  `qemu_x86/atom` and its variants successfully.
- Actual Zephyr `scripts/build/gen_kobject_list.py`,
  `scripts/kconfig/kconfig.py` and `scripts/dts/gen_defines.py` execute `--help`
  successfully under the same isolated interpreter and hook.
- Maven/Tycho five-module build and distributable verification pass. Verification
  also compares packaged startup/setup helpers byte-for-byte with tested source.
- Headless p2 provisioning upgrades 0.1.7.202610240835 to
  0.1.8.202610240858 and replaces the previous root feature.
- Update-site ZIP SHA-256:
  `aa9bc151b9b492e695ec4bdbfff7856a4cf40ecdd124f4f21c414ff2a1891b86`.

These checks ran on Linux using CPython isolation to reproduce the omitted script
directory. A complete native Windows embedded-Python firmware build and desktop
wizard retry remain pending. Script help checks do not constitute full firmware,
emulator or physical-hardware validation.

## Retry

Install 0.1.8 through **Zephyr > Check for Workbench Updates**, then restart.
Keep an existing failed managed folder to reuse its verified downloads, or use
a new empty destination for a clean trial. Do not merge or delete user source.
If the failed folder was renamed as a backup, restoring its original name before
retry preserves cached paths. Close Eclipse before renaming it; leave the separate
older Zephyr workspace and system tools intact.
