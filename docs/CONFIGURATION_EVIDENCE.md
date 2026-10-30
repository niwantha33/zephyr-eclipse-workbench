<!-- SPDX-License-Identifier: Apache-2.0 -->
# Share and compare configuration evidence

Open **Zephyr > Configuration Studio > Insights**.

- **Export evidence…** captures a preview of the current `prj.conf`, generated `.config`, generated `zephyr.dts`, and the read-only diagnosis report when available. Review the list and contents before saving to a new `.zew-evidence.zip` file. Existing packages are never overwritten.
- **Open evidence…** verifies and displays a saved package without requiring a workspace, SDK or board. It does not extract archive paths or run archived content.
- **Compare evidence…** selects two packages in order. It compares effective/requested Kconfig settings by symbol and identifies other changed files. Open both packages to inspect Devicetree changes in detail.

Only the listed files are collected. Logs, source trees, binaries, environment variables and credentials files are not collected automatically. However configuration strings can contain passwords and diagnosis reports can contain local paths: inspect the contents and redact source inputs before exporting externally. This is an explicit inclusion preview, not an automatic secret-redaction service.

Exports use a versioned manifest and SHA-256 hashes to detect accidental corruption. These hashes do not authenticate the author or make an untrusted package trustworthy. Reads enforce allowed entry names, duplicate rejection and per-entry decompression bounds. No archive content is written to the filesystem while opening.

This first format captures configuration evidence, not an entire reproducible build, firmware identity, crash memory or physical measurements. Different settings do not establish the cause of a failure. Missing files are missing evidence, not proof the corresponding feature is disabled.

## Try the comparison without hardware

1. Open/import an application and export a source-only package.
2. Change an existing Kconfig request in `prj.conf` and export a second package.
3. Compare them and check the reported symbol/value difference.
4. Build the exact selected board and export again to include resolved settings and Devicetree.
5. Compare requested and resolved settings using **Diagnose**; rebuild after changing configuration inputs.

Automated `InvestigationBundleTests` exercises round trips, immutable previews, source preservation, no-overwrite behaviour, setting comparison, path rejection, decompression limits and checksum failures. Physical hardware is not needed for these checks.
