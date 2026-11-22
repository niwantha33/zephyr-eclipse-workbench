// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Versioned, project-owned workflow definition. No Eclipse or hardware dependencies:
 * validation also applies when files are edited outside the visual studio.
 */
public record WorkflowDefinition(String name, List<Step> steps) {
    public enum Step {
        CLEAN("Clean", "Clean the selected board/profile build directory."),
        BUILD("Build", "Compile and verify the current Zephyr project."),
        FLASH("Flash", "Use the native runner (including its build and explicit confirmation)."),
        FLASH_DEBUG("Flash & Debug", "Flash first, then request the native debugger.");

        private final String label;
        private final String description;

        Step(String label, String description) {
            this.label = label;
            this.description = description;
        }
        public String label() { return label; }
        public String description() { return description; }
        public boolean terminal() { return this == FLASH || this == FLASH_DEBUG; }
    }

    public WorkflowDefinition {
        if (name == null || name.isBlank() || name.length() > 100
                || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Workflow name must be 1–100 characters on one line.");
        if (steps == null) throw new IllegalArgumentException("Workflow has no steps.");
        steps = List.copyOf(steps);
        if (steps.isEmpty() || steps.size() > 20)
            throw new IllegalArgumentException("Use between 1 and 20 workflow blocks.");
        for (int index = 0; index < steps.size(); index++) {
            Step step = steps.get(index);
            if (step == null) throw new IllegalArgumentException("Unknown block at position " + (index + 1));
            if (step.terminal() && index != steps.size() - 1)
                throw new IllegalArgumentException("Flash / Flash & Debug must be the last block.");
        }
    }

    public static WorkflowDefinition starter() {
        // This starter cannot program hardware until the user explicitly adds Flash.
        return new WorkflowDefinition("Clean and Build", List.of(Step.CLEAN, Step.BUILD));
    }

    public static Path file(Path project) {
        return project.resolve(".zew").resolve("workflows").resolve("default.properties");
    }

    public static WorkflowDefinition load(Path project) throws IOException {
        Path path = file(project);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return starter();
        if (Files.isSymbolicLink(project.resolve(".zew"))
                || Files.isSymbolicLink(path.getParent()) || Files.isSymbolicLink(path))
            throw new IOException("Workflow path must not be a symbolic link.");
        Properties values = new Properties();
        try (var input = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            values.load(input);
        }
        if (!"1".equals(values.getProperty("version")))
            throw new IOException("Unsupported workflow format (expected version 1).");
        String raw = values.getProperty("steps", "");
        var parsed = new ArrayList<Step>();
        try {
            for (String token : raw.split(",", -1)) parsed.add(Step.valueOf(token.trim()));
            return new WorkflowDefinition(values.getProperty("name", ""), parsed);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid saved workflow: " + error.getMessage(), error);
        }
    }

    public void save(Path project) throws IOException {
        Path path = file(project);
        Path parent = path.getParent();
        if (Files.isSymbolicLink(project.resolve(".zew"))
                || Files.isSymbolicLink(parent) || Files.isSymbolicLink(path))
            throw new IOException("Refusing to write a workflow through a symbolic link.");
        Files.createDirectories(parent);
        Properties values = new Properties();
        values.setProperty("version", "1");
        values.setProperty("name", name);
        values.setProperty("steps", String.join(",", steps.stream().map(Enum::name).toList()));
        Path temp = Files.createTempFile(parent, "workflow-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                values.store(writer, "Zephyr Workflow Studio v1");
            }
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
