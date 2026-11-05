# Configuration Studio evidence commands

These commands are available in `python zew.py`, the standalone `zew.pyz`, and
the installed Eclipse `scripts/studio.py` resource. Use `COMMAND --help` for
exact arguments. JSON is the default; `studio-view --text` emits bounded
Markdown or DOT. Exit 2 means invalid/unsafe inputs, 1 means an explicit
unavailable/refused/blocking diagnostic result, and 0 means the report was
produced without that command's blocking conditions. Exit 0 never qualifies
a release or proves working hardware. Comparison differences alone return 0.

| Command | Purpose | Required selection |
| --- | --- | --- |
| `studio-kconfig` | Upstream transitive dependencies and request diagnosis | `--build`, `--snippet` and/or `--symbol`; optional `--ninja` |
| `studio-dt` | Upstream final property source location | `--build --zephyr --node --property --source-root` |
| `studio-driver` | Separate configured/command/object/map/runtime states | `--build --source --source-root`; optional `--symbol` |
| `studio-resources` | Decoded GPIO references and pinctrl inventory | `--build --zephyr`; actual module `--bindings` if needed |
| `studio-images` | Upstream sysbuild image topology | `--build` |
| `studio-preflight` | Configured environment prerequisite evidence | `--build`; optional expected board/checkout/tool versions |
| `studio-compare` | Per-image configuration, version and DT changes | `--left --right`; optional each side's explicit checkout |
| `studio-diagnose` | Deterministic normalization of finding reports | One or more `--input` JSON reports |
| `studio-answer` | Exact finding explanation or refusal | `--input --finding --evidence-root` |
| `studio-view` | Bounded table or focused dependency graph | `--input`; optional `--focus CONFIG_X --image app` |
| `studio-rules` | Explicit versioned local rule pack evaluation | `--pack --observations`; version if rules are scoped |

Build commands accept repeated `--image NAME=relative/build` declarations or
`--auto-images`, which requires generated `domains.yaml`. They are mutually
exclusive. Inspection does not search arbitrary image directories. External
builds and old checkouts can be inspected but missing evidence remains unknown.

## Kconfig example

```bash
python zew.py studio-kconfig --build /workspace/app/build --snippet /workspace/app/prj.conf
```

The selected build's Ninja `guiconfig` command is inspected, not executed.
The selected checkout's Kconfiglib parses its real Kconfig tree. Input hashes
are checked across evaluation. Arbitrary string/numeric values and string
constants are redacted; boolean/tristate dependency expressions remain useful.
The tree includes reverse select/imply, defaults, prompt conditions and choices.
Matching values, undefined symbols, invisible requests, direct dependency bounds
and conflicting fragment requests are separate findings. A referenced OR or NOT
term is not an automatically proposed repair. Duplicate precedence across all
active fragments still needs pristine upstream origin evidence.

The native **Build & System → advanced → Evaluate transitive Kconfig** action
runs this same packaged evaluator off the SWT thread and displays its JSON
alongside the equivalent CLI. **Diagnose source requests** is a separate,
lightweight source/snapshot check with navigable Problems markers; it does not
pretend to evaluate Kconfig dependencies.

## Devicetree and driver examples

```bash
python zew.py studio-dt --build /workspace/app/build --zephyr /workspace/zephyr --node /soc/adc@40012000 --property status --source-root /workspace
python zew.py studio-driver --build /workspace/app/build --source drivers/adc/adc_example.c --source-root /workspace/zephyr --symbol CONFIG_ADC
```

Use actual paths and sources from the selected target; these names are examples.
DT origin tracing requires generated `zephyr.dts.pre`, final `zephyr.dts`, and a
modern selected dtlib exposing source locations. Equal phandle numbers are
insufficient: references must resolve to the same node paths. Mixed reference
cell origins stay unknown when binding semantics are needed. No assignment
history or original source freshness is inferred from line markers alone.

The driver source and Kconfig symbol are explicitly independent selections.
Object bytes, compiler commands and GNU-map contributions are observations;
initialization remains unknown without matching runtime evidence.

## Grounded report use

Save CLI JSON by normal shell redirection only when you deliberately want a
local report. Reports containing explicit source paths are private local data.
`studio-answer` never calls an AI service or executes instructions in a report:
it selects a supported finding, verifies cited files inside explicitly approved
roots, and emits a fixed explanation or refuses. A hash does not authenticate
the report's producer. Rendering also preserves unknown/stale states.

## Versioned rule packs

Rule packs use `schema_version: "zew.studio-rulepack/1"`, a unique lowercase
`pack_id`, exact semantic `version`, and a bounded `rules` array. Each rule must
provide `id`, `title`, `when`, `required_evidence`, `severity`, `confidence`,
`message`, `next_action`, `documentation`, `scope`, and `fixtures`.

`when` is an all-of list of `{"field":"status","equals":"disabled"}`
predicates over already extracted scalar facts. Required evidence kinds must
be present with valid paths and SHA-256 before any finding is emitted.
`fixtures.passing` must not match; `fixtures.failing` must match. Missing,
contradictory or malformed fixtures reject the pack. Scope supports exact
`zephyr_min`, `zephyr_max_exclusive`, `vendors`, and `socs`. Missing version
evidence skips scoped rules explicitly. Extensions cannot claim `confirmed`
root cause, execute Python, edit files, fetch documentation, or bypass source
review. Unknown schema versions and duplicate keys are rejected.

See [implementation and acceptance](CONFIGURATION_STUDIO_ROADMAP.md) for the
remaining milestone limits and qualification commands.
