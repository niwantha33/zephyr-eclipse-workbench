<!-- SPDX-License-Identifier: Apache-2.0 -->
# Upstream Zephyr SBOM/Build Profile evidence — Phase 0

**Status:** read-only feasibility adapter, not released, not R2 implementation,
not a conformity check or an SPDX schema validator. Gate G0 remains OPEN.

The authoritative upstream generator is **Zephyr west spdx**, not Workbench.
Zephyr 4.5 provides SPDX 3.0.1 JSON-LD Build Profile data from the CMake
File API. It records sources, tools, build inputs and build outputs; this
program must consume it rather than invent an independent SBOM engine.

Official references:
- [Zephyr west spdx](https://docs.zephyrproject.org/latest/develop/west/zephyr-cmds.html)
- [Zephyr 4.5 release notes](https://docs.zephyrproject.org/latest/releases/release-notes-4.5.html)
- [SPDX 3.0.1 Build Profile](https://spdx.github.io/spdx-spec/v3.0.1/model/Build/Build/)

## Generate upstream evidence (explicit user action)

Enable `CONFIG_BUILD_OUTPUT_META=y` in the target configuration and rebuild.
From the Zephyr west workspace:

```sh
west spdx -d /path/to/build --spdx-version 3.0
python zew.py sbom --build /path/to/build
```

For sysbuild, generate the upstream SPDX documents for **each image build**
directory, not at the sysbuild root:

```sh
west spdx -d /path/to/build/my_app --spdx-version 3.0
west spdx -d /path/to/build/mcuboot --spdx-version 3.0
python zew.py sbom --build /path/to/build --image app=my_app --image boot=mcuboot
```

`zew sbom` never invokes west, changes Kconfig, downloads content or
configures the build. It checks `spdx/{app,zephyr,build,modules-deps}.jsonld`
or, where JSON-LD is unavailable, the SPDX 2 tag-value filenames. It hashes
each exact bounded file snapshot and, for JSON-LD, counts observed Build
elements and build-scoped `hasInput`, `hasOutput` and `usesTool` relationships
attached to that Build. It does **not** expose raw source paths, component
identifiers, tool paths or free-form SBOM fields.

**Important limits:** relationship observations do **not** prove the SPDX
document is current for the target ELF or valid under all SPDX constraints.
Only the upstream SPDX serializer and a validating SPDX library can establish
complete spec conformance. No vulnerability is called affected or not
affected; no VEX or licence compliance assertion is generated. No firmware
is flashed, debugged or analysed by this command. The specific JSON-LD
fixtures test documented shapes but are not a real Zephyr 4.5 firmware
build run. Real Zephyr 4.4/4.5 multi-image fixtures and independent validation
are still required before gating R2.
