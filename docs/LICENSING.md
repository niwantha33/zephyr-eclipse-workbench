# Open-source and commercial-use policy

Effective: 2026-10-23. Status: binding dependency requirements; dependency audit and enforcement are not yet implemented.

## Requirements

The core development workflow must use open-source software that permits commercial use without mandatory licence, subscription or runtime royalty fees. It must not depend on a non-commercial-only licence, trial, evaluation key, proprietary mandatory service or paid activation.

"Licence-free" means no licence fee here, not absence of copyright conditions. Preserve applicable copyright, attribution, licence, source-availability and redistribution obligations.

Original workbench material is licensed under Apache-2.0, as provided in the root LICENSE, unless a more specific file notice states otherwise. This does not relicense Eclipse, Zephyr, external tools, copied files or submodules.

The project remains private until separately authorised for publication.

## Dependency decisions

| Category | Project rule |
|---|---|
| Original plug-in code, helpers, owned templates and documentation | Apache-2.0; add SPDX headers where practical |
| Permissive third-party components | Prefer established OSI-approved licences; inspect the exact files/version and retain notices |
| Eclipse Platform/CDT components | Preserve their actual licences, including EPL conditions where applicable; review changes, linking and distribution boundaries |
| GPL tools used as external programs | Commercial use is not automatically prohibited; review exact licence and tool redistribution obligations |
| Libraries or code linked into distributed products | Review separately from executable tools; include runtime libraries, generated code and any applicable exceptions |
| Proprietary tools available at no charge | Do not count as open source or use as mandatory core dependencies |
| Non-commercial-only, evaluation, trial or paid-only dependencies | Exclude from the compliant workflow |
| Missing, unclear or incompatible licence | Mark pending/blocked; do not distribute or auto-install |
| Vendor firmware, radio blobs and closed probe utilities | Outside the strict open-source workflow; do not bundle, fetch, auto-update or require them in that workflow |

Open-source licences are not all permissive. Copyleft software can permit commercial use while imposing distribution requirements. Do not reject it merely because it is GPL, and do not claim that placing it in a submodule removes its obligations.

Using a compiler does not generally impose the compiler's licence on independently written source. Inspect linked runtime code, generated snippets, templates, libraries and exceptions before making claims about resulting firmware.

Hardware purchase costs are separate from software licence fees. Prefer open-source programming/debug software; a board that only works with a closed utility must be labelled accordingly.

## Board and feature capability

Evaluate licences per workflow and feature, not per vendor name. Distinguish:

1. IDE/core dependency status.
2. Build/flash/debug tool dependency status.
3. Firmware/runtime and peripheral-feature dependency status.

A board may support open-source CPU/GPIO work but need a closed radio binary for another feature. Do not call a complete radio-enabled application fully open source based only on the licence of Zephyr.

Zephyr's upstream blob documentation defines blobs as proprietary binary components and describes their separate terms. Earlier Espressif blob downloads in the user's learning environment do not establish compliance with this new policy.

A future optional external proprietary-tool adapter would require an explicit owner-approved policy exception. It must be disabled/separate, disclose restrictions, and never change the status of the strict open-source workflow. No exception is currently authorised.

## Review record

Before approving a dependency, record exact upstream URL, revision/version, SPDX expression, licence-file path and digest, role, inclusion mode, commercial-use permission, mandatory fees, notices/source obligations, applicable runtime exceptions, transitive components, reviewer/date and distribution decision.

Use separate approval fields for internal build use and redistribution. A successful licence scan is supporting evidence, not proof that every obligation has been fulfilled.

Maintain [the dependency register](DEPENDENCIES.md), a machine-readable SBOM when implementation begins, and release notices. Review direct, transitive, p2, Python, toolchain, native, template and submodule dependencies.

## Updates

Every dependency upgrade triggers a fresh licence/notice comparison against the pinned approved version. New restrictions, missing metadata, changed source-availability terms or incompatible licences block automatic installation in the compliant workflow.

Existing user-managed installations are not implicitly approved for redistribution or for the strict workflow.

## Sources

- [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
- [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0/)
- [GNU FAQ: using GPL development tools](https://www.gnu.org/licenses/gpl-faq.en.html#CanIUseGPLToolsForNF)
- [Zephyr component licensing](https://docs.zephyrproject.org/latest/LICENSING.html)
- [Zephyr proprietary binary blobs](https://docs.zephyrproject.org/latest/contribute/bin_blobs.html)
