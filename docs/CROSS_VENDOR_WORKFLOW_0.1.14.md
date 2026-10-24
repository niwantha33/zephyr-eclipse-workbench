# Zephyr Workbench 0.1.14: Cross-vendor board dependencies and Eclipse integration

## Scope

The per-project exact board target is the input, **not a user-selected compiler or a hardcoded vendor installer**. Eclipse uses the pinned Zephyr 4.4.0 checkout and board/SoC metadata, then selects one compatible SDK 1.0.1 compiler and the required West modules. No user-side scripts, separate installations or PATH editing are required.

### Example dependency decisions

| Exact project target | SoC family | Compiler | West modules selected |
| --- | --- | --- | --- |
| `lp_mspm0g3507/mspm0g3507` | TI MSPM0G / Arm Cortex-M0+ | `arm-zephyr-eabi` | `cmsis`, `hal_ti` |
| `nucleo_f446re/stm32f446xx` | ST STM32F4 / Arm Cortex-M4 | `arm-zephyr-eabi` | `cmsis`, `hal_stm32` |
| Nordic nRF board with an ARM CPU qualifier | Nordic nRF / Cortex-M | `arm-zephyr-eabi` | `cmsis`, `hal_nordic` |
| `esp32s3_devkitc/esp32s3/procpu` | Espressif ESP32-S3 / Xtensa | `xtensa-espressif_esp32s3_zephyr-elf` | `hal_espressif`; pinned esptool and Python dependencies |
| `qemu_x86/atom` | Managed x86 starter | Previously verified x86 compiler | Existing managed starter is unchanged |

Actual board qualifiers are queried from the user's checked-out Zephyr tree. Family examples are **planning expectations**, not claims of native Windows/physical board verification. Mixed CPU architectures and unknown SoC families stop with an actionable error instead of installing an arbitrarily chosen compiler.

### User workflow

1. **File > New > Other > Zephyr > Zephyr Project**, or **File > Import > Zephyr > Existing Zephyr Application**.
2. Select the ready managed environment and exact target. Confirm it on the wizard's second page.
3. Later change hardware through **right-click project > Properties > Zephyr**. The project-specific exact SoC and CPU is saved in `.zew/project.json`; legacy metadata is preserved when migrating.
4. **Project > Build Project** runs dependency planning/preparation from the Eclipse-managed helper followed by the ordinary project-specific West build. **Project > Clean** uses the Eclipse builder and does not install dependencies.
5. **Run > Run Configurations > Zephyr Application**: choose a project and `Run in QEMU`, `Flash physical board` or `Start debug server`. Eclipse owns the console and termination. Debug-server mode is not yet CDT source-level debugging.

The old top-level **Select ESP32-S3 CPU** and **Select Board** commands were removed to avoid conflicting settings.

### Safety and repeatability

- Compiler binaries must match the official **Zephyr SDK 1.0.1 Windows x64** release's recorded **size + SHA-256** before extraction, and must pass `gcc --version` before promotion.
- Modules come from the selected checkout's **West manifest** and must match the manifest-pinned immutable Git commit. Missing selected modules are installed; existing installations with different commits are preserved and a specific error is reported.
- A shared environment install lock serialises projects that use the same SDK and modules.
- Compiler extraction is staged. Existing compiler folders and unrelated x86 toolchain are not overwritten.
- `.zew-build/<target-hash>` preserves independent output per selected board; projects and original source remain untouched.
- The updater remains private and requires a verified p2 package. Do not publish a binary manually or advance `updates/channel.properties` to point at an untested site.
- The Windows auto-preparation path currently assumes the managed, pinned Windows x64 environment. Non-Windows hosts continue using their provisioned external dependencies.

### Known limitations / acceptance gates

- Not every Zephyr SoC can be inferred unambiguously from its Kconfig family. Unknown or multi-architecture parts need specific CPU mappings, rather than a silent fallback.
- Vendor-specific runner/flash utilities, licensing, a CDT debugger connection and hardware performance are **not** implied by successful compiler/HAL preparation.
- Pinned Zephyr is 4.4.0; moving the Zephyr or SDK versions requires a separate, reviewed lock/update.
- Automated Python unit checks and the packaging assertions are included. Before promotion, run `python3 scripts/test_core.py`, `mvn --batch-mode --no-transfer-progress clean verify`, `python3 scripts/verify_site.py` and native Windows Eclipse smoke acceptance.
- GitHub Actions on this private repository has reported failures **before a runner was assigned**. A source commit/PR by itself is not a published Eclipse update.

This release is not considered fully validated until the above gates pass; preserve the last working 0.1.13 installation and existing Zephyr projects during qualification.
