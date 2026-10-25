# MCUboot signing in the managed Python runtime

The Windows CPython embedded distribution does not automatically add the
launched script's directory to its import path. MCUboot's `scripts/imgtool.py`
imports the adjacent `scripts/imgtool` package. Installing only its third-party
wheels therefore leaves the entry point unable to import its own package.

The signing installer now maps both the pinned wheel directory and the pinned
West module's scripts directory into the managed runtime. Existing one-line
Workbench mappings migrate automatically; unrelated mappings remain protected.
The mapping is replaced atomically. Validation errors now include Python's
stderr so a failed dependency check retains its diagnostic traceback.

This repair applies to every board requiring IMGTOOL, through the existing
vendor-independent configure preflight. It does not change target selection,
application configuration, compiler pins, or West revisions.

Validation: 80 Python tests pass (one Windows-only test skipped on Linux).
The new integration test launches a real isolated Python interpreter, reproduces
the sibling import failure with the old mapping, verifies migration, and repeats
the installer to check reuse. A separate test checks retained failure diagnostics.
Existing compiler, HAL, CMSIS, transitive module and activation tests also pass.
Full Windows firmware builds, physical flashing, and Eclipse package validation
have not been performed for this patch. The local Java harness is blocked by
missing JDK 21; Maven is also unavailable.
