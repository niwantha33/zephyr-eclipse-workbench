<!-- SPDX-License-Identifier: Apache-2.0 -->
# Private managed environment archive (experimental)

This offline CLI preserves the regular files of a self-contained managed
setup and their SHA-256 digests in a ZIP64 archive. It is a first recovery
milestone, not a portable installer or a clean-machine qualification.

## Prepare while the original environment still works

1. Preserve the actual application source, lock and frozen manifest in
   version control or a separate verified archive. Preserve the production
   ELF/BIN/HEX for each image, build profile, configuration and test evidence.
2. Stop builds, debug sessions, package installation and other writes to the
   managed setup. Review the entire folder for proprietary blobs, credentials,
   Git remote configuration, private sources and licensing restrictions.
   The archive is not sanitised, signed or encrypted. Keep it in an approved
   access-controlled internal retention store; do not publish it.
3. All six recorded core paths must be inside the managed setup. A setup
   containing external tools, links, junctions or special files is rejected;
   external requirements (OS libraries, device drivers, services, environment
   variables, signing keys, uninstalled modules) are not captured.
4. Use a separate backup location outside the managed root:

    python environment_archive.py export --root C:\Zephyr\Original --archive D:\Retention\release.zewenv --private-copy

Save the emitted archive SHA-256 in a separately trusted record. Keep the
archive format and recovery utility source with the retention documentation.
Run verify after creating the archive, periodically and before restore.

    python environment_archive.py verify --archive D:\Retention\release.zewenv --expected-sha256 HASH

## Recover years later

Use a compatible OS and architecture with the same original absolute path
available. The parent directory must exist and the destination must NOT
exist. Do not restore over a current installation. Absolute paths in Python
environments, tool configuration and cached metadata make relocation unsafe;
this milestone deliberately requires the original location. If the original
path or a compatible host cannot be recreated, recovery is not supported.

    python environment_archive.py inspect --archive D:\Retention\release.zewenv
    python environment_archive.py restore --archive D:\Retention\release.zewenv --destination C:\Zephyr\Original --expected-sha256 HASH --trusted-archive

Inspect only validates metadata; verify reads every payload. Restore first
verifies all bytes and then extracts to an exclusively created directory,
with no downloads or archived code execution. Each file is rehashed on
extraction. An interrupted restore leaves an incomplete directory without
the ready marker; inspect and remove it manually before retrying.

After restoration, select the trusted original setup for the project, run
the existing project environment verification (schema 1), and explicitly
run a pristine build. The historic ready marker is not proof of a successful
build on the new host. Compare original and rebuilt firmware per image:

    python environment_archive.py compare-firmware --original original.bin --rebuilt rebuilt.bin

A matching binary proves those compared bytes match; it does not prove
flashed identity or safety qualification. Exit codes: 0 completed operation,
1 firmware byte difference, 2 invalid/failed operation. JSON output.

## Remaining qualification

Native Windows and Linux exact-head CI; multi-gigabyte interrupted and
disk-full recovery; clean-machine offline build using a retained real
environment; unavailable upstream simulation; Python/SDK external dependency
audit; multi-image artifact identity; host/VM retention and relocation
support. No automatic historical download, cross-host migration or release
gate pass is claimed. No automatic archive contents are redistributed.

## Retention verification and restore safety

Use **Project Environment Manager > Archive / Restore > Verify Retained Archive** with the separately stored SHA-256 at periodic retention reviews (for example annually) and before migration or recovery. No extracted program runs. The CLI now requires the retained digest for restoration; digest, ZIP bounds and payloads are inspected from the same open file. Insufficient destination disk space is rejected before creation; restored files are flushed before publishing the historic ready marker. Keep the archive immutable and access-controlled during verification and restore. This does not replace independent clean-machine offline build qualification, licence and Git object completeness review, or compatible host retention. No five-year availability guarantee is implied.
