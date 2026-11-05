#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Installed native entrypoint to the exact headless Configuration Studio core."""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from zew_evidence.studio_cli import main

if __name__ == "__main__":
    raise SystemExit(main())
