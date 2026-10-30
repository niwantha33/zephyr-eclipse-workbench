<!-- SPDX-License-Identifier: Apache-2.0 -->
# Preliminary licence and redistribution audit — 2026-10-30

**BLOCKED for redistribution approval.** This is a technical register, not legal advice.
Refer to existing [LICENSING.md](LICENSING.md) and [DEPENDENCIES.md](DEPENDENCIES.md).

| Component | Current boundary | Review needed |
| --- | --- | --- |
| Original Java/Python and examples | Apache-2.0 as repo LICENSE except individual notices | Inspect bundled exceptions and third-party extracts |
| Eclipse 4.41/CDT/Equinox/p2/SWT | Host feature dependencies | Exact installed-unit BOM, EPL notices, transitive versions |
| JDK 21, Maven/Tycho 5.0.3, ECJ | Build/test tools, not p2 bundled | Exact versions and distribution permissions |
| Python, west 1.5.0, kconfiglib, edtlib | Existing/managed workspace | Wheel/lockfile licences, dependency hashes and versions |
| Zephyr 4.4/4.5, SDK, HALs and blobs | Firmware workspace | Manifest-level per-component licences, exceptions, prohibited blob distribution |
| GNU GDB and OpenOCD | Separate processes | Preserve GPL obligations, no automatic bundling |
| Vendor SVD, packages and pin data | SVD inspect | Per-asset redistribution permissions |
| [mcu_register_viewer](https://github.com/niwantha33/mcu_register_viewer) | Potential reuse | Prior licence audit found all-rights-reserved; no copying without grant |

No blanket commercial approval. Before G0 pass: inspect exact p2 archive contents, resolved Python/SBOM dependencies, compiler runtime linkage, SVD origins, NOTICE and separate internal-use/redistribution decisions. Do not redistribute vendor binaries without explicit rights.
