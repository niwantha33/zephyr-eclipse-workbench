<!-- SPDX-License-Identifier: Apache-2.0 -->
# YAML Visualization Studio — Step 7: Devicetree binding intelligence

**Scope:** selected-file, read-only `edtlib.Binding` inspection in the native
YAML Structure view. **Not:** a new schema interpreter, generated-DTS match,
Kconfig causality claim, or a visual YAML editor.

## Usage

1. Select a managed Zephyr application and its exact checkout under Project
   Properties → Zephyr.
2. Open **Window → Show View → Other… → Zephyr YAML Structure (Read-only)**.
3. In Project Explorer select a YAML binding under
   `<selected-zephyr>/dts/bindings/…` or the selected application's
   `dts/bindings/…`.
4. The view continues to show Step 6's source-linked YAML syntax tree. A new
   read-only panel reports upstream binding resolution, including compatible,
   title, description, included-binding count and resolved property names,
   types, required/deprecated flags and descriptions.
5. Refresh source after editing, switching project checkouts or changing any
   included binding.

The binding panel will say **unavailable** or **not applicable** when the
selected source is not a binding, the exact checkout lacks `edtlib`, includes
are missing or ambiguous, or upstream rejects the binding. It must not show
a positive interpretation on parser errors.

## Evidence boundary

- `scripts/yaml_binding_inspect.py` loads `devicetree.edtlib` from the
  **selected Zephyr checkout only**, then invokes the upstream public
  `edtlib.Binding(path, fname2path)` interface. This is *not* a forked
  binding schema or a guess based on YAML names.
- The source binding and included YAMLs are subject to the existing safe YAML
  preflight, bounded reads, and symlink protections before asking upstream
  for semantics. Included basenames must resolve uniquely; ambiguities are
  explicitly unavailable rather than silently choosing one.
- The selected binding bytes, selected `edtlib.py`, and included binding bytes
  are checked for changes during inspection. Source SHA-256 is also checked by
  the Java bridge and again against the Step 6 YAML snapshot.
- All work runs off the SWT UI thread; the inspector is a bounded subprocess
  with cancellation, 25-second time limit and limited base64 protocol output.
- The UI intentionally displays only selected, bounded upstream property
  specifications. It does not infer a property's physical pin, driver enable
  state, build inclusion, configured status, or eventual compiled DTS meaning.
- Upstream legacy binding tags or complex YAML constructs rejected by the
  existing Step 3 safety preflight are explicitly **unsupported** in this
  preview. A valid Zephyr build may accept files that this initial inspector
  conservatively refuses.

## Testing

```sh
python -m unittest discover -s tests/tools -p test_yaml_binding_inspect.py -v
python scripts/test_core.py
```

The Python tests use a *test-only stub* implementing the public
`edtlib.Binding` interface, to verify exact-checkout loading and the
security/output contract. They do **not** establish real Zephyr binding
semantic compatibility. Separately validate on an actual pinned Zephyr
checkout with binding files containing `include:`, `child-binding:`,
required/deprecated properties, bad bindings, and no accompanying build.
The Java tests verify the wire contract and absence of false-positive status;
the CI workflow must compile/package the plugin and exercise its installed UI
at the exact Step 7 head.

## Next approval gate

Step 8 (Hardware Model v2 board and SoC metadata) has **not** begun. Do not
merge or publish this draft PR without review and release gates.
