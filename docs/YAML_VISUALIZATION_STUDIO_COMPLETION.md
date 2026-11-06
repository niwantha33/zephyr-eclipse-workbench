<!-- SPDX-License-Identifier: Apache-2.0 -->
# YAML Visualization Studio — consolidated completion

**Scope:** one reviewable PR against current main for unmerged Steps 9–10,
guarded editing (Step 11), and final CI/packaging qualification (Step 12).
Steps 4–8 were already merged. The old draft Step 9 and 10 branches are
superseded only once this combined PR has been verified.

## Inspection and interpretation

Open **Window → Show View → Other → Zephyr YAML Structure**, select a
saved YAML file in Project Explorer, and inspect its original syntax tree.
All four tabs have distinct evidence boundaries:

| Tab | Evidence | Does not establish |
| --- | --- | --- |
| Devicetree binding | Selected checkout edtlib property specifications | Compiled DTS / enabled driver |
| Hardware Model v2 | Board, qualifier and SoC definitions | Active build target / physical chip |
| Twister tests | Selected scl/schema-checked scenario declarations | Selected or passed tests |
| Diagnostics | Saved YAML syntax errors and selected Twister schema errors | Binding/HWMv2/build validity |

Unsupported or missing validators are explicitly unavailable, never silently
treated as a valid Zephyr binding, build or Twister test.

## Guarded editing: application-owned scalar only

1. Select a verified *saved* YAML file inside a managed application project.
   Checkout-owned, generated, linked, derived and imported-JSON sources are
   read-only, as are mappings, sequences, block/multiline values and unsupported
   scalar types. Save or discard any dirty Eclipse editor first.
2. Select an individual scalar value and choose **Edit selected scalar…**.
   The supported types are string, canonical decimal integer, boolean,
   finite decimal float, and null. Type changes are rejected.
3. The workbench hashes the original bytes and prepares an in-memory replacement
   of exactly the selected lexical token from the upstream YAML source range.
   It does not reserialize YAML. Existing ordering, CRLF, comments, whitespace
   and untouched Unicode are byte-preserved.
4. The patched candidate is parsed by the project's managed Python and
   restricted YAML observation module, with its own SHA-256 and scalar-type
   checks. The preflight verifies *syntax and type only*, not EDTlib/HWMv2/
   Twister semantic validity. A rejected candidate is not written.
5. The UI displays a complete old/new scalar-token diff and asks for explicit
   confirmation. Cancelling leaves the source unchanged.
6. On confirmation the app source bytes and root are verified again. An
   original-file backup is written in the same directory, the candidate
   staged in that directory and committed using an **atomic replace**.
   An unsupported atomic rename fails closed; it does not fall back to a
   destructive non-atomic copy. An Eclipse refresh and reinspection follow.
7. Original backups are retained with a dot-prefixed zew-backup filename.
   Review domain-specific validation and test/build results separately.

The tool does not implement a free-form YAML rewriter, automatic fix-all,
source mapping bulk edits, or edits to unsupported/invalid YAML.

## Source and process protections

- Selected YAML and in-memory candidate size limit: 256 KiB.
- Verified source-root containment, safe relative paths, no symlinked source
  paths, exact SHA-256 checks and stale-file rejection.
- Bounded depth/nodes; restricted tags, aliases/anchors and duplicate keys.
- Managed Python only, no shell, no network, bounded local observation reports,
  cancellable 25-second subprocesses, cleaned temporary input/output.
- Distinct, nonpersistent YAML Eclipse problem marker type; other CDT/build/
  Configuration Studio markers are never intentionally deleted.
- Imported saved JSON has no authenticated writable workspace source and
  cannot publish source markers. Unknown diagnostic positions stay unmarked.
- External writers do not share an advisory source lock. As with other atomic
  file-replacement workflows, a concurrent external writer is a residual
  filesystem race even though the original digest is rechecked immediately
  before replacement. Do not use this as a multi-writer transaction.

## Automated verification

Run a focused preflight using Python unittest discovery for test_yaml_* in
tests/tools, followed by the full command:

    python scripts/test_core.py

That command runs Python fixtures, Java 21 immutable models, actual managed
Python parsing, one-scalar previews, cancellation, Unicode/CRLF/comment
preservation, original backups and stale/unsafe root rejection.

The CI job also runs Tycho/Maven p2 packaging, installed-site verification,
native Eclipse UI smoke, Windows unit tests and the seven-target managed
firmware matrix, and independent Evidence CLI Python version tests. The
installed-site gate checks all packaged YAML scripts, model classes, view
registration and nonpersistent marker type. The installed native SWT smoke
opens the real YAML Structure view, verifies all four tabs and imported
JSON's read-only boundary.

## Manual release acceptance still required

- Select a pinned real Zephyr checkout with Devicetree binding includes,
  HWMv2 board/SoC revisions and Twister testcase/test-config sources.
- Check schema provenance and explicit unavailable states. Never infer an
  actual firmware build, pin wiring or test execution from YAML inspection.
- Break and repair YAML in a disposable project fixture. Confirm source
  line/column navigation, marker creation and removal, and that unrelated
  build markers remain intact.
- Test dirty editors, source switching, saved JSON import, broken checkout,
  symlinks and generated sources. Confirm there are no unauthorized edits.
- Preview string, numeric and boolean changes; cancel one and apply one.
  Compare exact before/after bytes and the backup, including CRLF and Unicode.
- Review installed SWT behavior on Windows and Linux, not just fixture tests.

**Release gate:** keep the consolidated PR draft until exact-head CI is green
and manual real-checkout acceptance is explicitly confirmed or noted as
outstanding. Do not merge or publish automatically.
