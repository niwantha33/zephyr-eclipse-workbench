<!-- SPDX-License-Identifier: Apache-2.0 -->
# YAML Structure — Step 5 manual qualification

**Status:** native read-only tree of a *saved YAML observation*, not yet
an editor for a selected `.yaml`/`.yml` source file.

## Open the view

In the installed Eclipse Workbench choose **Window → Show View → Other…**,
search for **Zephyr YAML Structure (Read-only)** and open it. Choose
**Open YAML observation JSON…** to import a JSON report produced by
`zew_evidence.yaml_observation.observe_yaml`.

The view shows four columns:

- **YAML key / index:** original mapping order and sequence indices;
- **Type:** mapping, sequence or YAML scalar type;
- **Value preview:** bounded and escaped text (not executed);
- **Line:column:** 1-based source location of a mapping key or sequence value.

Invalid, unavailable and empty observations show an explicit state. A
`parsed` report proves neither Zephyr schema validity nor a successful
build.

## Produce a report for a manual UI check

From the repository root, using a Python environment with PyYAML installed,
run the following in a local Python REPL or script. Edit the root and relative
path to point to a YAML file **you have explicitly selected**.

```python
import json
from pathlib import Path
from zew_evidence.yaml_observation import observe_yaml

root = Path(r"C:\Zephyr_Project\zephyr")
relative = "dts/bindings/gpio/gpio-controller.yaml"

observation = observe_yaml(
    (root / relative).read_bytes(),
    path=relative,
    source_root="zephyr",
)
Path("yaml-observation.json").write_text(
    json.dumps(observation, ensure_ascii=False, indent=2),
    encoding="utf-8",
)
```

The example source path must be replaced if that particular binding does not
exist. Keep the generated JSON local if it contains private source text;
reports include scalar values as well as a hash of the input bytes. The
Workbench importer does not invoke Python or read a YAML source automatically
in this step.

## Qualification checklist

1. Import a saved report and expand mapping/sequence nodes.
2. Confirm order, scalar types, indexes, the originating key's line:column
   and escaped multiline string previews.
3. Import a malformed or unsupported JSON document; the prior tree should
   clear and the view should report failure without displaying raw contents.
4. Import a valid Step-3 `invalid` or empty observation; no structure is
   invented.
5. Try a JSON file larger than 4 MiB, reopen the view, and verify that
   importing occurs without freezing the SWT UI.

**Not yet implemented:** selecting original YAML source in Project Explorer,
selected-checkout awareness, source navigation, automatic refresh, schema
checks and YAML editing. Those are subsequent approval-gated steps.
