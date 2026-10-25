# Workbench 0.1.17 — automatic MCUboot signing dependencies

## Reproduced failure

On Zephyr 4.4.0, `esp32s3_devkitc/esp32s3/appcpu` completed compilation,
linked `zephyr.elf`, then failed its post-link `zephyr.signed.bin` command:

```text
python.exe: can't open file '.../zephyr/IMGTOOL-NOTFOUND'
```

The ELF and memory-usage log prove **compiler and linker success**, not the
complete firmware build. The pinned Zephyr CMake `FindHostTools.cmake`
resolves `IMGTOOL` from an active, installed West `mcuboot` module
(`scripts/imgtool.py`) or from a standalone executable. Previous Workbench
board dependency preparation installed `hal_espressif` but not `mcuboot`.

## Fix

- Espressif targets: prepare the Zephyr-manifest-pinned `mcuboot` West
  project before starting a build.
- Other hardware targets: prepare `mcuboot` if project `prj.conf`,
  `sysbuild.conf`, or a board/sysbuild `.conf` selects MCUboot.
  Continue avoiding unnecessary MCUboot downloads for unrelated projects.
- Install only hash-locked Windows CPython 3.13 dependencies used by imgtool
  (`cbor2`, `cryptography`, `click`, `intelhex`, `PyYAML`,
  `cffi`, `pycparser`). Source: upstream original wheel packages.
- Store packages in the managed `board-python/mcuboot` path, keep the
  existing `board-python/esp32s3` tool installation intact, and map the
  directory only through the managed Python's `.pth` entry.
- Validate `python <manifest_mcuboot>/scripts/imgtool.py --help` before
  reporting READY. A missing or incomplete install is a builder error with
  a direct cause, rather than a late Ninja `IMGTOOL-NOTFOUND` failure.
- Reuse pinned West modules and already-verified Python packages across
  builds; do not upgrade the SDK, modify the application, or replace an
  existing installation with a mismatched pin.

## Acceptance

1. Merge **only after** core tests, Tycho, and `verify_site.py` all pass.
2. Install Eclipse Workbench 0.1.17 from the published p2 update channel.
3. Rebuild the existing ESP32-S3 app CPU build **without deleting the
   project**. Confirm `mcuboot` appears in West active modules,
   `imgtool --help` succeeds and both `zephyr.elf` and
   `zephyr.signed.bin` exist.
4. Verify the existing TI MSPM0 project still builds without downloading
   signing tools. Check STM32/Nordic when MCUboot is enabled.
5. Confirm that no signing keys or development keys are silently generated
   or changed; production MCUboot signing requires user-owned production keys.

Native Windows builds and hardware flashing remain to be verified. A clean
GitHub Actions p2 package does not prove the ESP32-S3 flash/boot sequence.
