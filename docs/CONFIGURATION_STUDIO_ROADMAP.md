# Configuration Studio implementation and acceptance

This records the recovered CS-01–CS-18 sequence and the user's requested
continuation through 19. CS-19 means integration/qualification here: no earlier
definition was found. These are development increments, not completed release
gates. Preserve explicit unknown states when inputs or hardware are unavailable.

| Milestone | Implemented increment / entry point | Acceptance still requiring evidence or implementation |
| --- | --- | --- |
| CS-01 — evidence receipts | Managed-build SHA-256 receipts, current/changed/unverified/no-build | Complete Kconfig input lineage |
| CS-02 — failed/missing builds | Source-only and retained-failure evidence | Configure-only recovery across all project layouts |
| CS-03 — Kconfig causality | `studio-kconfig`, native **Evaluate transitive Kconfig**; upstream direct/select/imply/default/prompt/choice graph, trace origin types | Complete pristine source-fragment precedence; timestamp/report origin alone is not proof |
| CS-04 — dependency/conflict diagnosis | `studio-kconfig --snippet`; undefined/unmet/invisible/conflicting/valid classes, exact source lines and hashes | Non-boolean/range diagnosis, hypothetical repair planning |
| CS-05 — Devicetree source/result | `studio-dt`; selected upstream dtlib property locations from CPP line markers, final/preprocessed value and reference identity | Complete assignment history; old dtlib without property locations stays unknown |
| CS-06 — driver inclusion | `studio-driver`; candidate/configured/compile-command/object/map/initialization separated | Authenticated source→object→final-ELF causality and runtime initialization evidence |
| CS-07 — resources | `studio-resources`; upstream GPIO typed cells, enabled-parent checks, pinctrl reference inventory | Vendor/package mux ownership, electrical validation; shared references are only possible conflicts |
| CS-08 — diagnosis contract | `studio-diagnose`; stable IDs, confidence/provenance validation, deterministic merging | Full rule corpus and semantic rule coverage from the wider Evidence Engine program |
| CS-09 — safe changes | Existing scratch/diff/confirmation/backup flow hardened for dirty editors and missing scratch hash | Installed UI race/cancel acceptance across platforms |
| CS-10 — three-section tasks | Existing Overview/Hardware/Build & System; source diagnosis and upstream evaluation actions | All CLI capabilities as focused native controls, beginner usability qualification |
| CS-11 — sysbuild | `studio-images`; generated domains metadata, isolated image declarations and topology recheck | Real MCUboot/multi-core configuration matrix |
| CS-12 — preflight | `studio-preflight`; exact configured target/checkout/tool presence and recorded-version checks | Current executable compatibility (cache versions do not prove it) |
| CS-13 — comparison | `studio-compare`; image-scoped Kconfig, version and optional upstream DT differences | Measured real regression causality; differences are observations |
| CS-14 — Problems/navigation | Dedicated hash-checked source markers, exact verified source lines, own-marker cleanup | Installed UI/source navigation on Windows |
| CS-15 — grounded answers | `studio-answer`; exact finding lookup, approved evidence roots, rehashes, fixed explanations or refusal | General AI reasoning/client integration; report authenticity is not established |
| CS-16 — responsiveness/accessibility | Background jobs, cancellation/generation checks, disposed-display guard, accessible labels and Enter actions | Screen reader, high DPI, theme and measured responsiveness matrix |
| CS-17 — focused views | `studio-view`; escaped bounded Markdown tables or DOT findings/dependency graphs | Native interactive rendering and UI usability qualification |
| CS-18 — extensions | `studio-rules`; inert versioned JSON packs, scope, required evidence, passing/failing fixtures | Signed vendor packs and broad shipped diagnostic corpus |
| CS-19 — integration/qualification | Packaged/native evaluator byte parity, isolated CLI smoke, `qualify_configuration_studio.py` | Exact-head p2/UI/Windows/hardware gates; G0 remains open |

## Local verification

Use an open-source JDK 21 and the selected trusted Zephyr checkout's upstream
libraries. To exercise modern DT property locations, set `ZEW_TEST_DTLIB` to that
checkout's `scripts/dts/python-devicetree/src/devicetree/dtlib.py` before testing.
Optional upstream fixtures explicitly skip without their library; a skip is not
semantic qualification. Nothing from the upstream test checkout is redistributed.

```bash
python scripts/qualify_configuration_studio.py --core
python scripts/sync_studio_resources.py --check
python scripts/package_cli.py --output /new/path/zew.pyz
```

Native p2 resources are staged from the actual headless dependency closure by
`sync_studio_resources.py`. Tests compare byte identity, including an isolated
installed-resource invocation. Re-run staging after headless module changes.

All CLI inspection commands are read-only unless explicitly using an existing
separate scratch configure/save workflow. Kconfig evaluation imports the selected
trusted checkout's Python code; it is not a security sandbox. No CLI observation
here proves that firmware was flashed, a device initialized or a pin is electrically
available. Release gates and exact-head remote checks are recorded separately.
