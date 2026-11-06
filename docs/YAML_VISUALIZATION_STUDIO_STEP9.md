<!-- SPDX-License-Identifier: Apache-2.0 -->
# YAML Visualization Studio — Step 9: Twister metadata

**Scope:** read-only visualization of selected Zephyr Twister YAML test
descriptors and test configuration. This is a schema-backed observation,
**not** evidence of a selected test plan, compatible hardware, execution
status, successful build or passing test.

## In Eclipse

1. Select a managed Zephyr application with its pinned checkout and managed
   Python configured in **Project Properties → Zephyr**.
2. Open **Window → Show View → Other → Zephyr YAML Structure (Read-only)**.
3. In Project Explorer, select a locally accessible YAML source:
   - `tests/.../testcase.yaml` or `samples/.../sample.yaml`;
   - `tests/.../test_config.yaml`;
   - `testcase.yaml`, `sample.yaml`, or `test_config.yaml` directly
     under the managed application root.
4. Open the **Twister tests** tab below the raw YAML syntax tree:
   - Testsuite files: **Sample metadata**, **Common defaults (declared)**,
     individual **Scenario** names and their declared parameters such as
     `harness`, `timeout`, `tags`, `platform_allow`, `sysbuild`,
     `testcases`, and other keys accepted by the selected schema.
   - `test_config.yaml`: **Config options**, **Default platforms
     (declared)** and **Levels (declared)**, including each level's
     description, `adds` and `inherits` attributes.
5. Save YAML changes and choose **Refresh source**. The existing source
   YAML tree still provides source positions for navigation.

Unsupported YAML files (including `tests.yaml`, `twister.yaml`, and arbitrary
`*.yaml`) are **not** automatically considered valid Twister scenarios just
because they have a YAML extension. Imported Step 5 JSON reports have no
verified checkout and cannot claim Twister metadata validation.

## What is actually checked

- The packaged `scripts/yaml_twister_inspect.py` reads a bounded, saved
  YAML snapshot using Step 6's checked source path/root and Step 3's safe
  syntax observation; it rejects unsafe aliases, duplicate keys, oversized
  sources, symlinks, and path traversal.
- It imports **the selected checkout's own**
  `scripts/pylib/twister/scl.py` and validates the snapshot using
  `scl._yaml_validate` against the selected checkout's
  `scripts/schemas/twister/testsuite-schema.yaml` or
  `test-config-schema.yaml`. No local, forked schema is applied.
- Source YAML, selected Twister `scl.py` and schema bytes are all
  SHA-256 checked before and after inspection. The Java bridge independently
  checks the saved source SHA-256.
- A finite, versioned `ZEW-TWISTER-1` protocol exposes only bounded,
  escaped previews of source metadata, with states `resolved`,
  `unavailable`, and `not-twister`. The immutable Java model projects
  these into a separate SWT/JFace tree. The parser never propagates upstream
  exception strings containing paths or literal YAML.
- Managed Python inspection runs off the SWT thread, with cancellation,
  a 25-second timeout and a 128-KiB output limit.

**Schema-valid is not Twister-run-valid.** This step does *not* call
`TwisterConfigParser.get_scenario` to produce effective default values,
scan C ztest registrations, resolve modules/architectures/platform filters,
build a Twister test plan, launch any test, infer `BOARD` from CMake, or
read run results. A schema-valid scenario can still be filtered or fail in
a real test plan. Common values are shown strictly as *declared*.

## Tests and qualification

```sh
python -m unittest discover -s tests/tools -p test_yaml_twister_inspect.py -v
python scripts/test_core.py
```

The Python tests use a **test-only checkout fixture** implementing the
selected `scl._yaml_validate` contract with Draft 2020-12 JSON Schema,
not the actual pinned Zephyr distribution. They verify root isolation,
schema accept/reject, scenarios, common/sample metadata, configuration
levels/platforms, file integrity, and sanitized errors. Java headless
tests verify strict wire parsing, type safety, immutable tree projection
and fail-closed states.

Before merging, validate with a **real pinned Zephyr 4.4.0 checkout** and
real `scripts/schemas/twister` files using multiple actual testcase and
sample descriptors; test-config levels; unsupported/invalid scenarios;
and a missing validator dependency. The exact-head GitHub CI must pass
Windows unit qualification, Tycho/p2 packaging and installed Eclipse UI
smoke. The Step 9 tests cannot be used as proof of test execution.

## Stop gate

Step 10 validation diagnostics, Step 11 controlled YAML editing and
Step 12 final qualification have **not** begun. Do not merge this draft
until reviewed and verified.
