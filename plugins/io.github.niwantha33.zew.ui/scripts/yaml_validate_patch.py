#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Step 11: preview-only candidate YAML syntax observation from stdin.

Input is an in-memory patch snapshot sent over a local redirected stream.
No path reading, network, imports from user sources, file writes or execution.
"""
from __future__ import annotations

import argparse
import json
import sys

from zew_evidence.yaml_observation import MAX_SOURCE_BYTES, observe_yaml


def main(argv=None):
    parser = argparse.ArgumentParser(allow_abbrev=False)
    parser.add_argument("--relative", required=True)
    parser.add_argument("--source-root", choices=("application",), required=True)
    options = parser.parse_args(argv)
    raw = sys.stdin.buffer.read(MAX_SOURCE_BYTES + 1)
    observation = observe_yaml(raw, path=options.relative, source_root=options.source_root)
    sys.stdout.write(json.dumps(observation, ensure_ascii=True, separators=(",", ":")) + "\n")


if __name__ == "__main__":
    main()
