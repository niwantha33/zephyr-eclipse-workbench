#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compile and test the dependency-free smart-completion policy with the JDK."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/SmartCompletionTrigger.java"
TEST = ROOT / "tests/java/io/github/niwantha33/zew/ui/SmartCompletionTriggerTest.java"

def main():
    with tempfile.TemporaryDirectory(prefix="zew-smart-completion-") as classes:
        subprocess.run(["javac", "-encoding", "UTF-8", "-d", classes, str(SOURCE), str(TEST)], check=True)
        subprocess.run(["java", "-cp", classes, "io.github.niwantha33.zew.ui.SmartCompletionTriggerTest"], check=True)

if __name__ == "__main__":
    main()
