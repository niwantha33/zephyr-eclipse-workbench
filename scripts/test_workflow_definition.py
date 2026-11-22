#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Headless regression tests for the pure Java Workflow Studio file contract."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
model = (root / "plugins/io.github.niwantha33.zew.ui/src/io/github/"
         "niwantha33/zew/ui/WorkflowDefinition.java")

fixture = r"""
import java.nio.file.*;
import java.util.*;
import io.github.niwantha33.zew.ui.WorkflowDefinition;
import io.github.niwantha33.zew.ui.WorkflowDefinition.Step;

public class WorkflowDefinitionSmoke {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path project = Files.createTempDirectory("zew-workflow-");
        WorkflowDefinition starter = WorkflowDefinition.load(project);
        check(starter.steps().equals(List.of(Step.CLEAN, Step.BUILD)),
              "Fresh projects must never flash automatically");
        check(starter.name().equals("Clean and Build"), "Starter name");
        var saved = new WorkflowDefinition("My board flow",
                List.of(Step.CLEAN, Step.BUILD, Step.FLASH_DEBUG));
        saved.save(project);
        check(WorkflowDefinition.load(project).equals(saved), "Save/load roundtrip");
        check(Files.exists(WorkflowDefinition.file(project)), "Expected project path");
        boolean rejected = false;
        try {
            new WorkflowDefinition("Unsafe", List.of(Step.FLASH, Step.BUILD));
        } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Flashing cannot precede another step");
        rejected = false;
        try {
            new WorkflowDefinition("Unsafe", List.of(Step.FLASH, Step.FLASH_DEBUG));
        } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Only one terminal hardware operation");
        rejected = false;
        try {
            new WorkflowDefinition("", List.of(Step.CLEAN));
        } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Empty name rejected");
        Files.writeString(WorkflowDefinition.file(project), "version=1\nname=Oops\nsteps=CLEAN,UNKNOWN\n");
        rejected = false;
        try {
            WorkflowDefinition.load(project);
        } catch (java.io.IOException expected) { rejected = true; }
        check(rejected, "Corrupt workflow must not run");
        System.out.println("WorkflowDefinition smoke: PASS");
    }
}
"""

with tempfile.TemporaryDirectory(prefix="zew-workflow-test-") as tmp:
    output = Path(tmp)
    test = output / "WorkflowDefinitionSmoke.java"
    test.write_text(fixture, encoding="utf-8")
    subprocess.run(["javac", "--release", "21", "-encoding", "UTF-8", "-d", str(output),
                    str(model), str(test)], check=True)
    subprocess.run(["java", "-cp", str(output), "WorkflowDefinitionSmoke"], check=True)
