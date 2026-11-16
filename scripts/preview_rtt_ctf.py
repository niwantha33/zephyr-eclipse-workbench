#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Opt-in experimental capture from a pre-existing dedicated local RTT TCP port.

No OpenOCD commands, probe setup, flashing, target halt/reset, or ownership
claims. Does not enable the Workbench Start Live Trace menu. This is a preview.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis"))
from rtt_live_preview import capture_existing_rtt_preview


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, required=True,
                        help="Existing, manually verified binary RTT channel-1 localhost TCP port")
    parser.add_argument("--config", type=Path, required=True,
                        help="Exact built zephyr/.config, not prj.conf")
    parser.add_argument("--metadata", type=Path, required=True,
                        help="Matching pinned Zephyr CTF TSDL metadata")
    parser.add_argument("--output", type=Path, required=True,
                        help="Directory for NEW raw/CTF/event/receipt evidence")
    parser.add_argument("--seconds", type=float, default=5.0)
    parser.add_argument("--experimental-unverified", action="store_true",
                        help="Explicitly accept NO flashed-image/probe/production qualification")
    args = parser.parse_args()
    if not args.experimental_unverified:
        parser.error("Use --experimental-unverified only after reviewing the limitations")
    folder = capture_existing_rtt_preview(
        args.port, args.config, args.metadata, args.output,
        explicit_opt_in=True, seconds=args.seconds)
    receipt = json.loads((folder / "receipt.json").read_text(encoding="utf-8"))
    print(receipt["status"], folder)
    print(receipt["reason"])
    print("No production/live hardware qualification or flashed image attestation")
    return 0 if receipt["status"] == "FORMAT_COMPLETE_UNVERIFIED" else 2


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, PermissionError, ValueError, UnicodeError) as error:
        print("RTT PREVIEW REFUSED:", str(error)[:280], file=sys.stderr)
        sys.exit(2)
