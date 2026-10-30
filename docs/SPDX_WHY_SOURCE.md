<!-- SPDX-License-Identifier: Apache-2.0 -->
# Why a source or module appears in the upstream Zephyr Build Profile

Status: Phase-0 feasibility; G0 OPEN, G1 and G2 not authorised. This is
**direct SPDX Build-input relationship observation**, not proof that a
particular C file was compiled or linked into the final firmware image.

```sh
# First generate upstream evidence using the exact existing build, explicitly:
west spdx -d /path/to/build --spdx-version 3.0

# Investigate one exact filename from upstream SPDX software_File.name:
python zew.py why --source drivers/adc/sensor.c --build /path/to/build

# Investigate a named software_Package/Zephyr module:
python zew.py why --module hal_ti --build /path/to/build

# For multi-image Zephyr builds:
python zew.py why --source src/main.c --build /path/to/sysbuild --image app=app --image boot=mcuboot

# Existing CONFIG_ analysis is intentionally unchanged:
python zew.py why CONFIG_GPIO --build /path/to/build
```

The command uses existing `zew collect` to hash `spdx/build.jsonld` and
other build artifacts, then loads bounded upstream SPDX 3 JSON-LD documents.
It compares the build document's SHA-256 to the Evidence Graph artifact node,
rejecting a changed build document. Indexes names across `app`, `zephyr`,
`build` and `modules-deps` documents. A matching exact `software_File`
or `software_Package` must be the target of a direct `hasInput`
`LifecycleScopedRelationship` from a known `build_Build`, with
`scope=build`. The output includes the Evidence Graph node anchor and source
document SHA-256. No source names, module names or raw SPDX IRIs are exported.
Multiple distinct SPDX IDs with the same name are **ambiguous**, and no
relationship means **not observed**, not absent from firmware.

Upstream's per-target sub-build `hasInput` can establish which source was
**recorded as input to a target**; accompanying `hasOutput` gives target
outputs as **an observed count only**. Neither direct relationship establishes
that the final ELF includes it. No transitive link or linker contribution is
inferred. An upstream SPDX package `hasInput` especially does not establish
that every file in the module compiled. An absent entry never proves that a
driver was excluded. The validity, freshness and authenticity of the upstream
report relative to all original source inputs is unknown without further
verification. Flash, runtime state, CVE, VEX and licence compliance are not
asserted.

Tests: exact cross-document references, missing output, missing input,
unscoped/unrelated references, ambiguity, malicious JSON, source privacy,
multi-image and changed-document rejection. The synthetic fixtures are not a
replacement for real Zephyr 4.5 cross-vendor firmware and independent
SPDX conformance evaluation.
