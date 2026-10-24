# Automatic ESP32-S3 build-tool preparation — 0.1.13

The 0.1.12 wizard could select ESP32-S3 although its beginner SDK only contained the x86 compiler. A first ESP32-S3 build therefore failed in `cmake/compiler/gcc/target.cmake` with the missing `xtensa-espressif_esp32s3_zephyr-elf-gcc` path. This release prepares the missing profile before invoking West build.

## Existing user project

1. Update through Zephyr > Check for Workbench Updates and restart Eclipse.
2. Select the existing MyZephyr project, then Zephyr > Select ESP32-S3 CPU.
3. For standalone Hello World select `esp32s3_devkitc/esp32s3/procpu`. `appcpu` is the secondary core and needs primary-core firmware; the wizard now warns about this.
4. Build the project. Compiler, esptool and Espressif HAL preparation is automatic, with logs/progress and cancellation. No terminal or separate user-run script is required. The first preparation needs network access; later builds reuse the verified tools.

## Preparation contract

- Windows x64 managed Python 3.13 and SDK 1.0.1 only. Other SoCs and hosts do not receive this toolchain.
- Official SDK asset `toolchain_gnu_windows-x86_64_xtensa-espressif_esp32s3_zephyr-elf.7z`, 47,622,966 bytes, SHA-256 `a3af742531382e94a378cb3d5f36c11150f54832037ab399ff765c830d5a5901`.
- Reuse and execute `--version` for an existing compiler. Otherwise verify the download, reject unsafe archive members, extract beside the SDK, execute the staged compiler and rename only its toolchain directory into `sdk/gnu`. Preserve the x86 compiler and SDK root. An incomplete existing compiler directory is reported rather than overwritten.
- Download esptool 5.2.0 and fifteen exact dependency artifacts listed in `tools/esp32s3-python-lock.json`, with fixed URLs, sizes, hashes and upstream licence metadata. Preserve downloaded notices. Pip installs only those verified local files, with no dependency resolution, index access or isolated build downloads. The esptool source uses the already managed setuptools/wheel.
- Python additions live under `board-python/esp32s3`, staged and import-tested before activation. A local site-packages `.pth` adds that directory to the managed interpreter. No global Python/PATH registry settings are changed. Project subprocess PATH includes the resulting console launchers.
- Obtain only the Espressif HAL Git source pinned by Zephyr 4.4.0: `b7953b8019361d09e613f7011d2ccc41b984d087`. Verify the selected Zephyr source revision, stage a new clone, then rename it into the manifest path. Reuse the matching existing revision; preserve mismatched module directories. Enable Git long paths for Windows checkout. Do not run `west blobs fetch`.
- Serialize preparation against the existing setup installation lock. Interrupted staged additions do not replace the working compiler or application. Completed downloads are cached for retry.
- Changing the CPU writes portable metadata atomically with a stale-target check. Per-target hashed output preserves the prior CPU's build directory and all application source.

## Validation and limits

53 Python checks run (one existing CMake executable availability check skips on this host); all remaining checks pass. Java core/update/bootstrap/project/editor suites pass, including four additional target-change preservation/staleness checks. Tycho compilation and p2 package verification pass.

The real Windows compiler archive was downloaded, its hash and actual extraction layout were checked, and the staged installation path was exercised. Linux cannot execute the Windows compiler; the native `--version` call was substituted only in that archive fixture. The esptool source was successfully built as a wheel with network-isolated build dependencies. Windows compiler execution, the new Eclipse dialog, complete Windows ESP32-S3 compilation and physical execution still require user-host validation. This is a build-tool repair, not evidence of standalone APPCPU boot support, radio-blob support or completed hardware debugging.
