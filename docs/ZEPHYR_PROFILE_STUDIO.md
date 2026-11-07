# Zephyr Profile Studio — named firmware configurations (v1)

Zephyr Profile Studio extends the original three built-in modes
(Application settings, Debug -Og, Release -O2) without changing their
existing outputs. It is a project-owned, native Eclipse feature.
**No profile is applied merely by selecting it**: build, flash and debug use
the currently selected build path, but the firmware is current only after a
successful configure/compile and evidence verification.

## Create and select a profile

1. Right-click an imported Zephyr application and open **Properties > Zephyr**.
2. Choose **Manage profiles...**, then **New...**. Give the profile a stable ID
   such as `debug-rtt` or `release-pre` (lowercase, digits and hyphens).
3. Specify a display name, one of the existing optimization policies,
   optional C/C++ compiler macros and a Zephyr Kconfig fragment.
4. Save and select **Profile: ...** in the **Build configuration** dropdown.
5. Apply Project Properties, then **Build**. Examine the generated `.config`
   and build console evidence. Builds run in independent board-specific folders.
6. To switch, choose another profile and build that profile before flashing or
   debugging. Previously generated outputs are not silently relabeled as the
   newly selected profile.

Profiles support New, Edit, Duplicate and Delete in native SWT/JFace dialogs.
A profile selected in Eclipse cannot be deleted until another profile has
been selected and applied. The existing configuration options work unchanged.

## What a profile contains

A named profile stores two reviewable, portable files:

```text
.zew/profiles/debug-rtt.profile
.zew/profiles/debug-rtt.conf
```

Example metadata file:

```properties
schema=1
name=Debug RTT
optimization=debug
defines=APP_DIAGNOSTICS=1,APP_TRACE_ENABLED
```

Example `debug-rtt.conf` for a Zephyr build **whose board and Zephyr
configuration actually support the requested settings**:

```ini
CONFIG_LOG=y
CONFIG_LOG_DEFAULT_LEVEL=4
```

The build uses Zephyr's documented `EXTRA_CONF_FILE` feature to merge the
profile Kconfig fragment, and selects Zephyr's optimization Kconfig option
when requested. The profile verifier compares requested Kconfig values
to the actual generated `zephyr/.config`. A requested but unresolved value
**fails verification**; the Workbench does not fabricate its effectiveness.

**Custom application Kconfig:** `CONFIG_RELEASE_PRE` is not an intrinsic
Zephyr setting. Define `config RELEASE_PRE` in the application's Kconfig
first, then request `CONFIG_RELEASE_PRE=y` in the profile. In C, use
`#if defined(CONFIG_RELEASE_PRE)`. An unknown setting is not considered
successfully applied simply because it appears in a fragment.

**C macros:** `APP_DIAGNOSTICS=1` is a C/C++ compiler definition, not a
Zephyr Kconfig setting. V1 passes these in `CMAKE_C_FLAGS` and
`CMAKE_CXX_FLAGS` to the selected independent build (therefore they are
**global to the compilation**, not scoped to the application's `app` target).
The build checks the generated compile command database for each requested
macro before claiming verification. Only plain uppercase identifiers with
optional numeric/hex values are accepted. Use app-defined Kconfig for
application-specific feature switches where possible.

## What is isolated, verified and not yet implemented

- Independent build cache for each combination of exact board and profile ID.
  Built-in configuration paths stay unchanged.
- Kconfig resolution is verified from the real built `zephyr/.config`, and
  requested C macros are checked against `compile_commands.json`.
- Profile names/IDs are validated; profile files must remain inside the project,
  with symlinks and oversized input rejected.
- The existing `prj.conf` is never rewritten by switching profiles.
- The current implementation supplies `EXTRA_CONF_FILE` for the named profile.
  Projects that already manage their own `EXTRA_CONF_FILE` must review the
  resulting CMake configuration; V1 does not merge or adjudicate third-party
  fragment sets. The independent build avoids cross-profile CMake-cache reuse.
- Neither an RTT transport nor live tracing is enabled merely by naming a
  profile `debug-rtt`. Probe, backend, board and Zephyr Kconfig support
  require separate checks. No automatic target resets or flash are performed.
- Profile-specific Devicetree overlays, logging presets, live profile diff,
  runtime debug-launch presets, GUI toolbar selector and firmware signing
  qualification are **future stages**, not V1 claims.
- A release profile does not imply secure boot, removed secrets, review of
  debug access, or production qualification.

The existing evidence-aware build and debug preflight paths remain authoritative.
A successful CLI command line is not proof of a successfully configured firmware.

## Validation

Run `python scripts/test_core.py` with JDK 21 and Python dependencies,
then the normal PR CI checks (Tycho plug-ins, installed Eclipse UI smoke,
Windows firmware matrix). The new `BuildProfileTests` cover metadata
roundtrips, Kconfig and macro verification, directory isolation, malformed
profile input and rejection of missing/deleted profiles.

**Follow-up milestones:** per-profile overlay sets; dedicated app-only C macro
injection without global CMake compiler flags; profile comparison and artifact
hash view; active profile toolbar selector; debug backend capability preflight.
