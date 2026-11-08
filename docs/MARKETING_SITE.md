<!-- SPDX-License-Identifier: Apache-2.0 -->
# Public marketing website

Entry point: [index.html](index.html). Styles, client-side filters and icon are
[marketing.css](marketing.css), [marketing.js](marketing.js) and
[marketing-icon.svg](marketing-icon.svg). The site is static and has no runtime
dependencies, cookies, analytics, telemetry, tracking scripts or remote fonts.

## Feature-claim contract

The 50-row table is a curated catalogue reflecting repository evidence on
2026-11-08, **not** a claim that 50 capabilities are production-ready.

- **Preview:** source-backed development feature; availability still depends on
  selected board/profile/host/setup and release acceptance.
- **Limited:** partial, bounded or conditional workflow; consult linked source.
- **Roadmap:** not established as shipping/qualified behaviour.

Trace-specific claims defer to
[TRACE_ANALYSIS_CAPABILITIES.md](TRACE_ANALYSIS_CAPABILITIES.md), and the
global release gate to [../RELEASE_STATUS.md](../RELEASE_STATUS.md). When
either changes, the marketing site must be reviewed for copy and status drift.
Never change an entry's status to suggest physical validation without
corresponding evidence. The hero workbench is a CSS **illustration**, explicitly
labelled as such; it is not an installed-UI screenshot.

## Publish to GitHub Pages

The workflow [pages.yml](../.github/workflows/pages.yml) deploys the full
`docs/` folder on relevant changes to `main`, or via a manual dispatch.

One-time owner action **if GitHub Pages is not enabled**:
open repository **Settings → Pages → Build and deployment → Source →
GitHub Actions**, then run the **Publish marketing website** workflow.
The intended public URL is:
https://niwantha33.github.io/zephyr-eclipse-workbench/

If the repository already uses **Deploy from a branch → main /docs**, this
page can also be served by that source; keep one deployment mode configured.
The connector available during authoring cannot read or change Pages settings,
so the live site must not be claimed as verified until a successful Pages run
and public URL check.

## Maintenance

Edit `docs/index.html` to add, revise or remove feature rows. Keep each
row's evidence link and status aligned to a current source document. The
search/filter script works with the static table (it does not generate feature
claims dynamically). Avoid fake metrics, simulated vendor approval, fabricated
testimonials and screenshots. For actual screenshots follow
[CONTRIBUTING](../CONTRIBUTING.md#readme-screenshots).

No build tool is needed for local inspection: open `docs/index.html` in
a browser, or run `python -m http.server --directory docs 8000` and browse
http://localhost:8000/.
