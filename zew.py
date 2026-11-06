#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Backward-compatible entry point for the Zephyr Evidence CLI."""
from __future__ import annotations

import sys
from pathlib import Path

# Managed Windows Python uses python313._pth, which isolates sys.path and
# omits the script directory. Import only this checked-out CLI's sibling
# package; do not depend on PYTHONPATH or search the working directory.
_CLI_ROOT = str(Path(__file__).resolve().parent)
if _CLI_ROOT not in sys.path:
    sys.path.insert(0, _CLI_ROOT)

from zew_evidence.cli import main


if __name__ == "__main__":
    raise SystemExit(main())
