# Security Policy

Zephyr Eclipse Workbench is an open-source development preview. Firmware build,
debug, flash, downloads, and project CMake scripts can execute code with the
current user's permissions. Only build and run projects and SDKs you trust.

## Reporting a vulnerability

Please **do not** publish exploit details, tokens, credentials or private files
in a public issue or pull request. Use GitHub's **Report a vulnerability**
facility (Security → Advisories → Report a vulnerability), if enabled for this
repository, to contact the maintainer privately. If it is not offered, open a
public issue requesting a private reporting channel **without including the
sensitive vulnerability details**.

Include affected version/commit, platform, reproducible steps, security impact,
and mitigations where possible. Maintainers will triage privately, coordinate a
fix, and publish an advisory when appropriate. No guaranteed SLA is claimed.

## Supported versions

The latest published development update is the only actively maintained line.
Older development builds and feature branches are not guaranteed security fixes.
The most recent tested update is not automatically a production-qualified
release: review workflow status and the release notes before installing.

## Safe operation

- Treat Zephyr workspaces, modules, project CMake, and external toolchains as
  executable inputs. Do not run untrusted projects under an administrator account.
- Use least-privilege GitHub tokens; do not paste tokens into issue reports or
  commit them to project files. Keep update credentials in Eclipse secure storage.
- Vendor SVD and CMSIS files describe target register access and must come from
  trusted sources. Do not bypass device identity or register-read safeguards.
- Git hashes and unauthenticated SHA-256 manifest fields do **not** by
  themselves establish publisher identity. Signed update metadata, vendor
  checksums and provenance remain separate trust controls.
- If you suspect an update-channel compromise, stop installing updates and
  contact maintainers through the private reporting channel above.

## Build / publishing security

The PR workflow runs with read-only contents permissions. On the main branch,
the site is built and verified in a read-only job, transferred with a recorded
site-tree digest, then published by a separate job. Only its final push step
receives a temporary repository write token. Branch rules and key management
must be configured and reviewed separately in GitHub repository settings.
