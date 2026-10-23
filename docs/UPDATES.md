# Updates and recovery

Status: proposed product requirements, not implemented. Updated 2026-10-23.

## Objective

Maintain the Eclipse workbench and its supporting components through one update centre, while preserving reproducible firmware projects. Automate compatible maintenance, explain required restarts, and provide tested recovery.

The Eclipse p2 scheduler supports automatic checks and downloads with notification. It does not by itself implement the unattended compatibility, idle detection, project dependency migration or recovery policy described here. Those require implementation and validation.

## Update ownership

| Component | Discovery and staging | Installation policy |
|---|---|---|
| Workbench plug-in and owned features | Trusted versioned p2 repositories; stable and preview channels | Compatible stable patch updates may install automatically under the auto-maintenance preference, at an idle boundary; notify before required restart |
| Eclipse Platform, CDT and required plug-ins | Resolve a tested p2 dependency plan against the installed product | Automatic check/download; compatible maintenance may join the managed plan. Feature-release/Java changes use a staged migration with a visible summary and recoverable previous installation |
| Java runtime | Detect required version and installer ownership | Manage a private runtime only in a future bundled distribution; external/system runtimes remain explicit user-managed dependencies |
| Zephyr source and modules | Compare the project's pinned revision and manifest with available versions | Offer a separate migration copy/branch; never silently change the project's revision or run west update against an unrelated version |
| SDK and compiler | Detect versions compatible with the selected Zephyr workspace | Install side by side where supported; preserve the selected compiler until a deliberate project migration passes checks |
| Python packages and West | Use workspace-specific virtual environments and version-appropriate requirements | Stage a new environment, verify it, then switch; do not upgrade global Python packages |
| CMake, Ninja and other host tools | Identify product-managed versus externally managed tools | Manage product-owned copies; explain external package-manager actions rather than overwriting system tools |
| GDB, OpenOCD, probe software and vendor tools | Version/capability checks and permitted download sources | Manage side-by-side copies where licensing/installation permits; system drivers and board/probe firmware require a separate explicit operation |
| Blobs and vendor binary libraries | Resolve from the selected manifest and vendor instructions | Fetch required versions after applicable terms; never replace with arbitrary latest binaries |
| Templates, examples and documentation | Version them with the workbench or as explicit template packages | Update template sources; never overwrite an existing user's application |
| Application source, prj.conf, overlays and local edits | Detect schema or template migration needs | Preview an explicit diff, preserve edits and back up before migration; source files are not update payloads |

An imported general Eclipse installation and a future managed workbench distribution have different ownership boundaries. Do not promise automatic replacement of arbitrary Eclipse installations.

## Product preferences

- Stable channel by default; preview is explicitly selected.
- Scheduled update checks while the IDE is running, with a due check on next startup.
- Automatic download of compatible stable maintenance packages, subject to network/storage policy.
- Auto-maintenance installs eligible patches at idle when enabled; notify-only, postpone and offline modes are available.
- A user-visible setting enables/disables automatic installation. Expose policy in onboarding and preferences.
- Never automatically restart with unsaved editors or active build, flash, debug, serial programming, or workspace migration jobs.
- External source/build-toolchain changes are project migrations, even if the new version is available through the update centre.
- No update checking service is assumed to run when Eclipse is closed. A desktop agent would be a separately designed feature.

## Update transaction

1. Discover component versions, installation ownership, channel and available disk space.
2. Resolve the full component/dependency plan and verify that it matches a tested compatibility entry.
3. Download to staging, verify source trust, signatures where available, and expected digests. A hash alone is not proof of publisher identity.
4. Save the component inventory and project preference/configuration backups. Keep previous required artefacts available.
5. Wait for an idle boundary and apply only the eligible plan. Coordinate with p2 rather than editing installed JARs directly.
6. Request restart when necessary; never discard unsaved work.
7. Run post-update health checks: plug-in load, environment detection, workspace discovery and a configured smoke test.
8. Mark healthy or offer recovery with logs and the previous component inventory.

If a transaction fails or is interrupted, preserve the working project, report the failure, and resume or recover from a recorded state. Never report success just because downloading completed.

## Recovery boundaries

Eclipse installation history can revert installed-software configurations. It does not restore user application files, external SDKs, Python environments or arbitrary workspace migrations.

Retain previous product installations/environments for managed migrations. Test recovery with old artefacts unavailable online; a p2 history entry without its required artefacts is insufficient.

For a host IDE that cannot restart, document an external recovery path. Project/configuration backups and product version rollback are separate operations.

## Compatibility metadata

A release compatibility entry records workbench/feature versions, Eclipse/CDT/Java versions, host architecture/OS, supported Zephyr ranges and tested versions, Python/West/SDK versions, and runner exceptions. Dependency resolution is necessary but does not prove behavioural compatibility.

Use versioned, immutable release repositories. Keep release channels distinct and provide a known-good version. No public repository or update endpoint is enabled while the project is private.

## Required tests

- Supported upgrade, incompatible plan rejection and channel switching.
- Offline startup, unreachable server, invalid signature/hash, interrupted download and insufficient disk space.
- Cancellation, active jobs, unsaved editors and postponed restart.
- Permission/locked-file failure and concurrent Eclipse instances targeting one installation.
- Failure after installation, healthy restart, installation recovery and backup restoration.
- Existing project source and pinned firmware dependencies remain unchanged after workbench maintenance.
- Dirty-project migration preview preserves local edits and supports cancellation.
- No repository credentials in logs, URLs, update metadata or committed configuration.

## Upstream references

- [Eclipse automatic update preferences](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.user/reference/ref-p2-autoupdate.htm)
- [Scheduling automatic updates](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.user/tasks/tasks-122.htm)
- [Reverting an install configuration](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.user/tasks/tasks-123.htm)
- [p2 trust authorities](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.user/reference/ref-p2-trust-authority.htm)
