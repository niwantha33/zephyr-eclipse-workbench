# RTOS demo build and CDT configuration compatibility

The supplied logs showed two independent problems after the project-open fix:

- Zephyr 4.4 found the TI board, SDK and application, but rejected the original
  sample's `CONFIG_DEBUG_INFO=y` because that symbol is undefined.
- CDT's queued **Change Build Configurations** job attempted to select a host
  run/toolchain configuration after generic CMake metadata had been removed.
  The West project had no CDT Core Build provider to handle that request.

## Project configuration

West projects now have a dedicated Zephyr nature and CDT Core Build provider.
Native configuration selection can create and restore West configurations
without retaining the CMake nature. Build/Clean delegate to the existing West
builder. Board, SDK, compiler, optimization profile and output directory still
come from the Zephyr project settings; a selected desktop toolchain in CDT
launch metadata does not replace the firmware compiler.

The compatibility check adds the nature to existing West projects automatically.
It also covers queued configuration jobs that outlive the CMake-to-West
conversion. Managed `.zew-build` output remains independent of host CMake
folders.

## Original demo migration

The corrected source is in `examples/rtos_analysis_demo`. Zephyr emits compiler
debug information by default, so removing the obsolete assignment retains DWARF
sections. Debug optimization is selected separately with Workbench's Debug
profile.

Existing extracted copies of the original supplied demo are migrated only when
all of these conditions hold:

1. Configure failed with the actual `undefined symbol DEBUG_INFO` diagnostic.
2. The normalized `prj.conf` matches the original supplied file's SHA-256.
3. `CMakeLists.txt` matches the original demo's SHA-256.

The original `prj.conf` is backed up under `.zew/config-backup`, only that
assignment is replaced with a comment, and configure is retried once. Other
options and Windows line endings are preserved. Customized applications and
other Kconfig failures are not rewritten. Kconfig warnings remain fatal.

## Validation

The Java migration tests cover the diagnostic requirement, original-file
identity, unrelated/custom configurations, Windows line endings, exact backup
and idempotence. The full Eclipse test exercises CDT configuration selection,
queued CMake-to-West conversion and saved configuration restoration.

The corrected sample compiled and linked with Zephyr 4.4.0
`684c9e8f32e4373a21098559f748f06915f950c9`, its pinned TI/CMSIS modules and Zephyr
SDK 1.0.1 for `lp_mspm0g3507/mspm0g3507`:

| Configuration | Flash | RAM |
|---|---:|---:|
| Application settings | 26,356 B | 13,712 B |
| Debug (-Og) | 34,712 B | 13,728 B |

Both ELFs contain DWARF `.debug_info` and `.debug_line`; the Debug ELF includes
`.debug_frame`. Both configurations enabled CTF RAM tracing with a 4096-byte
buffer, and neither build suppressed Kconfig warnings. These checks used a
Linux cross-build; physical execution and Windows probe/console operation
remain to be verified on the user's board.
