# Dependency register and submodule rules

Status: first build versions recorded below; complete dependency licence audit remains pending. No upstream Eclipse/Zephyr code, SDKs or submodules are bundled in the development update site. Root LICENSE covers original repository material, not external build/runtime dependencies.

## First build inventory

| Item | Version/source | Packaging status |
|---|---|---|
| JDK | Ubuntu OpenJDK 21.0.12+8-1~24.04 | Build host only; not bundled |
| Maven | Apache Maven 3.9.11 | Build host only; not bundled |
| Tycho | 5.0.3 from Maven Central | Build dependency; not bundled |
| Eclipse target | Platform 4.41, R-4.41-202608281142 | Referenced target/runtime; not bundled |
| Eclipse compiler | ECJ 3.45.0.v20260224-0835, resolved by Tycho | Build dependency; not bundled |
| Original feature and plug-ins | 0.1.0 development build | Apache-2.0; full LICENSE included in each JAR |
| GitHub checkout/upload actions | Exact commits in .github/workflows/build.yml | CI tooling only; workflow not yet executed |

The developer build resolved transitive dependencies. This inventory is not a complete SBOM or licence approval; release review remains required. No actual submodule has been selected.

## Candidate register

The 0.1.6 first-build profile records exact package/artifact sizes, hashes,
licence-file paths/digests and external-build-use decisions in
`plugins/io.github.niwantha33.zew.ui/tools/setup-lock.json`. Its readable review is
`tools/setup-licences.txt` in the same bundle. Scope is restricted to this pinned
Windows x64 profile; general tool/firmware redistribution and other board profiles
remain subject to review. No Python/Git/SDK executables or wheels are bundled in
the p2 site. Windows OS/runtime requirements remain a platform boundary.

| Component | Role | Intended integration | Review state |
|---|---|---|---|
| Eclipse Platform, CDT, Embedded CDT | Host IDE and C/C++ tooling | Pinned p2 target and feature dependencies | Pending exact component/version and licence review |
| OpenJDK distribution | Java runtime/build | Existing runtime or approved product-owned runtime | Pending distribution, version and exception review |
| Maven and Tycho | Plug-in/feature/update-site build | Versioned build dependencies | Pending direct/transitive review |
| Zephyr | RTOS, hardware definitions and build integration | West manifest pinned to tested revision | Pending exact tree and included-feature review |
| West and Python | Workspace/process tooling | Versioned workspace virtual environment | Pending exact versions and package review |
| CMake and Ninja | Firmware build orchestration | Existing or product-owned versions | Pending exact distribution review |
| Zephyr SDK, GCC, binutils and GDB | Compilation, linking and debugging | Architecture-specific installed tools | Pending package contents, runtime libraries, exceptions and redistribution review |
| OpenOCD and other open-source runners | Programming/debug servers | External process with explicit path/version | Pending exact build, dependencies and target support |
| Vendor HALs and selected firmware libraries | Hardware access | Through the pinned West manifest | Pending per-file licences, feature dependencies and runtime review |
| Vendor blobs/closed utilities | Some hardware-specific features | Excluded from strict open-source workflow | Not approved; any future exception requires explicit owner authorisation |

Inventory roles and names do not assert that every package/version is approved. Resolve actual dependency graphs and licences before installation/bundling.

For each approved version, expand the record to include the evidence fields required by LICENSING.md. Generate a release SBOM and bundled notices from the resolved distribution, including all transitives and native executables.

## Use the upstream dependency mechanism first

| Dependency | Preferred mechanism |
|---|---|
| Zephyr and its modules/HALs | West manifest with tested revisions |
| Eclipse features and bundles | Pinned target platform/p2 metadata |
| Maven/Tycho and Java build dependencies | Versioned Maven dependencies |
| Python requirements | Workspace requirements with resolved version inventory |
| Separate maintained source dependency lacking suitable package integration | Git submodule when justified |

Avoid managing the same source through both West and a Git submodule. A submodule is optional, not a requirement for every external component.

## Git submodule policy

1. Choose an upstream maintained project whose exact revision passes licence and compatibility review.
2. Use a documented directory such as third_party/<name>, with a credential-free HTTPS URL in .gitmodules.
3. Commit the exact gitlink SHA. A branch name or floating latest reference is not a reproducible version.
4. Preserve upstream licence/copyright/NOTICE files; record the source and local modifications.
5. Document recursive clone/update commands and any nested submodule licences.
6. Update through a reviewable PR with commit range, licence changes, compatibility results and notices.
7. Never use submodule placement as a way to bypass copyleft or redistribution requirements.
8. Keep user firmware repositories separate from IDE dependencies and never overwrite local changes automatically.

Do not create a placeholder .gitmodules file. Add it with the first actual reviewed submodule.

## Original repository licence

Original code/documentation/templates are Apache-2.0 unless specifically marked otherwise. Upstream code copied into this repository retains its own notices and licence. Record provenance when copying or adapting material.

