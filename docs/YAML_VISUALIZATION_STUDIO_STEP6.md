<!-- SPDX-License-Identifier: Apache-2.0 -->
# YAML Visualization Studio — Step 6: selected-file integration

**Status:** read-only inspection and navigation of a *saved* workspace YAML
file from a managed Zephyr project, using the Step 3 producer and Step 4 model.
This is not a YAML editing UI, schema validator or hardware state measurement.

## Use the view with a project YAML

1. Open a managed Zephyr application in Eclipse, with its Zephyr environment
   and managed Python configured under **Project Properties → Zephyr**.
2. Open **Window → Show View → Other → Zephyr YAML Structure (Read-only)**.
3. Select a saved `.yaml` or `.yml` file from **Project Explorer**. The
   view follows that file automatically, or use **Inspect selected YAML**.
4. Expand mapping keys and sequence items; the columns show YAML key/index,
   syntax type, bounded value preview and original 1-based line:column.
5. Double-click a row to open that workspace file at its source key or scalar
   position. If the file's bytes changed since capture, **Refresh source**
   before trying again.

To inspect a linked Zephyr checkout file from another Eclipse project, first
select your managed Zephyr application project to establish the checkout
context. Then select the linked YAML file. Only files physically inside the
application root or **exact selected Zephyr checkout** are eligible.
Unrecognized external module roots are not guessed. The selected file must be
locally accessible as an Eclipse `IFile`; arbitrary filesystem paths are not
automatically accepted.

The **Open YAML observation JSON…** button from Step 5 remains available.
Imported JSON does not imply an authenticated workspace source and cannot
trigger source navigation.

## How the integration works

- Java resolves the exact root using `ProjectContext.settings`: managed
  `python`, application location and `workspace/zephyr`.
- `YamlSourceLoader` rejects paths outside those roots, missing files,
  symlinks, unsupported extensions and oversized sources (256 KiB limit).
- The installed `scripts/yaml_observe_file.py` runs **only** under the
  managed Python interpreter, with an explicit root-relative path; it repeats
  root, symlink and size checks, and uses the **unchanged packaged copies**
  of `zew_evidence.yaml_classification` and `yaml_observation`.
- Java runs Python as a bounded, cancellable background process; it validates
  the versioned JSON, source root/path, input byte count and SHA-256 both
  before and after parsing.
- SWT only displays the verified immutable Java model. Any outstanding load
  result is discarded when the selection changes.
- Navigating to a row rechecks source identity/hash and uses the originating
  Eclipse workspace `IFile`, not a path chosen from imported JSON.

**Interpretation:** this is a *saved-on-disk YAML syntax snapshot*, not an
in-memory unsaved editor buffer. Save your editor changes and choose **Refresh
source** to see the new YAML. Successful parsing does not prove that Zephyr
selected or built a binding, board or Twister scenario.

## Verification

```bash
python -m unittest discover -s tests/tools -p "test_yaml_selected_file.py" -v
python scripts/test_core.py
```

The first command tests the packaged Python launcher, classifications, source
integrity, malformed input, traversal and symlink/size rejection. The core
runner invokes `YamlSourceLoaderTests` using an actual managed Python process
and the packaged entry point. Full p2 compilation and installed native Eclipse
smoke must be checked at the **exact Step 6 PR head** before qualification.

## Intentionally out of scope

Step 7 and later: Devicetree binding schema intelligence; HWMv2 and Twister
semantic validation; marker diagnostics; guided edits and source write-back.
This step never builds, rewrites, flashes or mutates source files.
