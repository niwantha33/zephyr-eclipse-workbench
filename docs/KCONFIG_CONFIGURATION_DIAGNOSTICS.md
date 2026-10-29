# Kconfig configuration failure diagnostics (Zephyr 4.4)

## Example: DEBUG_INFO is not defined

When a managed project contains `CONFIG_DEBUG_INFO=y` in its `prj.conf`
or another active Kconfig fragment, the pinned Zephyr 4.4 Kconfig parser
reports an undefined-symbol warning and aborts configuration. This is an
application configuration issue, not an SDK, West-module, TI HAL, CMSIS,
OpenOCD, or compiler installation failure. The Workbench must not reinstall
tools or suppress Kconfig warnings in response.

**Immediate user repair:** remove `CONFIG_DEBUG_INFO=y` from the file and line
reported by Zephyr. To request debug-friendly optimization, select
**Project Properties > Zephyr > Debug (-Og)**. The managed Debug configuration
uses an independent output folder and requests `CONFIG_DEBUG_OPTIMIZATIONS=y`;
never assume that option is equivalent to enabling every debug feature or
all debugger metadata. `CONFIG_DEBUG_THREAD_INFO=y` is for RTOS thread
awareness where supported, and is not a replacement for `DEBUG_INFO`.

## Workbench failure behavior

- Stream the entire Zephyr configure output to the project console.
- Parse actual undefined-symbol diagnostics from the selected Zephyr version,
  including Windows drive paths, spaces, file names and line numbers.
- Mark the corresponding project file and line as an **error** in Eclipse
  Problems. Zephyr treats these warnings as fatal during configuration.
- Report a targeted migration suggestion for `DEBUG_INFO`. For other
  unsupported symbols, suggest checking spelling, version, board, and active
  West modules without pretending to know a safe replacement.
- Never edit, delete, normalize or regenerate an imported `prj.conf`,
  overlays or other user source. No retries or automatic module downloads
  are triggered by an undefined Kconfig setting.
- Preserve the original exception and console diagnostics for investigation.

## Validation and limitations

The portable Java regression suite covers Windows/POSIX fragment paths,
duplicate warnings, malformed diagnostics, and unrelated compiler failures.
CI compilation and p2 packaging remain distinct from hardware validation.
Only undefined-symbol diagnostics have curated guidance in this change;
unsatisfied dependencies, conflicting selections, deprecated symbols and
Kconfig choice failures remain upstream diagnostics for now.

The fix does not change the pinned Zephyr version or app source on a user's
computer. Existing applications must have their invalid setting removed
before they will configure successfully.
