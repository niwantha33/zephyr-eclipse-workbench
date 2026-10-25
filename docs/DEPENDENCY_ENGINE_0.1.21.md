# Vendor-neutral dependency and build engine

Version 0.1.21 extends the working 0.1.20 baseline. The published 0.1.20 release
remains immutable. This document records implementation scope; CI evidence is
reported separately and does not imply hardware validation.

## Boundaries

`board_dependencies.py` resolves the exact target with the pinned Zephyr 4.4
hardware catalogue and West manifest. `dependency_engine.py` owns ordered,
code-defined providers: compiler, pinned West modules and activation, Python
utilities, and a registry of host capabilities (IMGTOOL and ESPTOOL). Adding a reviewed host capability
does not require changes to the Eclipse build loop. Unrecognised mandatory
capabilities fail before installation. Metadata is never interpreted as commands.

The compiler provider reuses the approved SDK asset lock and verifies the
compiler's `-dumpmachine` result. The generated Kconfig and CMake compiler are
cross-checked before compilation. HAL selection uses the current exact-target metadata resolver, closest matching
SoC paths and declared module references. Ambiguous architectures
fail closed. This does not claim support for all upstream SoCs.

The module provider prepares transitive `zephyr/module.yml` dependencies,
activates only required West project filters, verifies immutable revisions and
rejects local modifications. New modules are shallow-fetched from the pinned
manifest's GitHub HTTPS origin into a sibling stage, checked, then renamed into
place. Failed stages never become discoverable modules. Submodules require a
future reviewed provider and currently produce an explicit unsupported error.

The Python utility provider reuses the approved Espressif tools lock when the
resolved HAL requires it. MCUboot is a generic IMGTOOL capability, independent
of vendor. The provider uses the existing wheel locks, isolated site mappings,
and real imgtool execution check. No user signing key is generated.

Successful provider passes atomically write a per-target dependency record under
`.zew-dependency-state`: versions, archive/lock hashes, module revisions and a
stable fingerprint. The record is evidence, not a substitute for revalidation.
Existing compiler executables are checked by execution and target tuple; full
installed toolchain file integrity is not yet hashed. Local edits and incomplete
final install directories are preserved and rejected rather than overwritten.

## Native build lifecycle

Eclipse holds an OS file lock for the entire shared environment build session.
Independent environments may build concurrently. Projects sharing a mutable West
environment serialize; contention waits automatically with progress, cancellation and a 45-minute bound. A second
cross-process provider lock also protects standalone CI provisioning. Process
exit releases locks after cancellation or termination.

Configure-only preflight remains in place. Ninja `-t commands` inspects commands
reachable from the default build graph without executing them. Optional targets
outside that graph do not require tools. The recovery registry permits one
attempt per capability with a two-capability budget. Discovered capabilities are remembered in the
verified per-target evidence record so repeat builds retain their dependency plan. Unknown tools and ordinary
C/C++ compilation failures never cause guessed downloads or repeated builds.
After provisioning, CMake reconfigures with only the corresponding tool cache
entry unset. No pristine deletion is needed for host-tool recovery.

After the complete West build returns success, `BuildArtifacts` checks nonempty
outputs from generated Kconfig: the configured kernel ELF name and requested
BIN, HEX, S19 and UF2 outputs. Image-generation CMake byproduct entries add
mandatory signed/encrypted images. Output paths must remain inside the selected
managed build directory. An ELF alone is insufficient when other outputs are
configured. Editor integration and `compile_commands.json` export stay on the
existing path, after output verification.

## Automated evidence

Pure tests require no network. CI installs version-pinned test-only metadata
dependencies in its disposable runner Python; product provisioning still uses
reviewed hash-locked packages. Windows unit tests use the full runner Python for
venv-based fixtures, while all firmware provisioning/builds use embedded Python. They cover provider selection, unsupported tools,
architecture mismatch, stable dependency records, interrupted stages, dirty and
wrong-revision modules, process lock contention and release, independent
environments, migration of signing paths, bounded recovery, nested Ninja rules,
optional tools, configured image outputs, and Unicode/space paths. Existing
cache checksum, source preservation, editor metadata and cancellation tests
remain in the suite.

The PR workflow runs Linux Java/Python tests, Tycho packaging and p2 verification.
Its Windows job creates a fresh pinned embedded CPython 3.13.7, Git 2.50.1,
Zephyr 4.4.0 and SDK 1.0.1 installation using the same setup helper as Eclipse.
It resolves exact targets from that checkout, builds sequentially to reuse
compilers/modules, repeats builds and checks dependency records and source hashes.
It performs a TI clean/rebuild and exercises unsigned MCUboot image generation
on Nordic without generating keys. Each subprocess has a bounded timeout; the
job has a 100-minute limit. Individual archives retain existing size/hash limits.
No additional unpinned pip dependency installation or global environment changes
are used. A fresh job intentionally avoids restoring mutable installed tools
from untrusted CI caches; completed verified archives are reused within the job.

| Scenario | Pinned board identity (exact qualifier resolved at runtime) | Evidence required |
|---|---|---|
| ARM M0+ | lp_mspm0g3507 | ELF and configured outputs, CMSIS/TI HAL |
| ARM M4 | nucleo_f446re | ELF and configured outputs, STM32 HAL |
| ARM M4 | nrf52840dk | ELF and configured outputs, Nordic HAL |
| Xtensa | esp32s3_devkitc / primary CPU | ELF and configured images |
| RISC-V | esp32c3_devkitm | ELF and configured images |
| MCUboot application | nrf52840dk | non-Espressif unsigned MCUboot image |

## Explicit limitations

Full sysbuild/multi-image orchestration, arbitrary user toolchains, module
submodules, host capabilities beyond reviewed IMGTOOL/ESPTOOL, and every Zephyr SoC are
outside the verified managed profile. Missing dependencies that cannot be
identified before a failed configure stop with the original diagnostic; there
is no catch-all log-driven repair. The bootstrap architecture resolver still
rejects mixed or unsupported metadata instead of guessing. Configure is still
performed before each build; incremental compilation remains Ninja-managed.
Interrupted anonymous staging directories may remain after hard termination;
they are not activated or automatically deleted. Physical flash/debug and native
Eclipse desktop interaction require separate verification. A package build alone
is not firmware-build evidence, and firmware builds are not hardware evidence.
