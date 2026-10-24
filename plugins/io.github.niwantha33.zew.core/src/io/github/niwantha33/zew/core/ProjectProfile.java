// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.regex.Pattern;

/** Portable single-profile contract. Local tool paths belong to Eclipse properties. */
public record ProjectProfile(String board) {
    private static final String TARGET = "[A-Za-z0-9_][A-Za-z0-9_.@/+-]{0,255}";
    private static final Pattern DOCUMENT = Pattern.compile("\\s*\\{\\s*\"schema\"\\s*:\\s*1\\s*,\\s*\"board\"\\s*:\\s*\"(" + TARGET + ")\"\\s*,\\s*\"profile\"\\s*:\\s*\"first\"\\s*}\\s*");
    public ProjectProfile {
        if (board == null || !board.matches(TARGET) || board.contains("..") || board.contains("//") || board.endsWith("/"))
            throw new IllegalArgumentException("Invalid exact board target");
    }
    public static ProjectProfile read(Path application) throws IOException {
        Path file = application.resolve(".zew/project.json");
        if (Files.size(file) > 4096) throw new IOException("Project profile exceeds size limit");
        var match = DOCUMENT.matcher(Files.readString(file));
        if (!match.matches()) throw new IOException("Unsupported project profile. Expected schema 1 with board and profile first.");
        try { return new ProjectProfile(match.group(1)); }
        catch (IllegalArgumentException e) { throw new IOException(e.getMessage(), e); }
    }
    public void replace(Path application, String expectedBoard) throws IOException {
        if (!read(application).board().equals(expectedBoard)) throw new IOException("Project target changed; reopen target selection.");
        Path directory = application.resolve(".zew");
        if (Files.isSymbolicLink(directory)) throw new IOException("Project metadata directory must not be a symbolic link");
        Path temporary = Files.createTempFile(directory, ".project-", ".tmp");
        try {
            Files.writeString(temporary, "{\n  \"schema\": 1,\n  \"board\": \"" + board + "\",\n  \"profile\": \"first\"\n}\n");
            Files.move(temporary, directory.resolve("project.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    public void write(Path application) throws IOException {
        Path directory = application.resolve(".zew");
        if (Files.isSymbolicLink(directory)) throw new IOException("Project metadata directory must not be a symbolic link");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("project.json"), "{\n  \"schema\": 1,\n  \"board\": \"" + board + "\",\n  \"profile\": \"first\"\n}\n", StandardOpenOption.CREATE_NEW);
    }
    public static void createApplication(Path application, String board) throws IOException {
        var profile = new ProjectProfile(board);
        if (Files.exists(application)) {
            try (var files = Files.list(application)) {
                if (files.findAny().isPresent()) throw new IOException("Application directory is not empty. Import it instead.");
            }
        }
        Files.createDirectories(application.resolve("src"));
        Files.writeString(application.resolve("CMakeLists.txt"), "cmake_minimum_required(VERSION 3.20.0)\nfind_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})\nproject(hello_zephyr)\ntarget_sources(app PRIVATE src/main.c)\n", StandardOpenOption.CREATE_NEW);
        Files.writeString(application.resolve("prj.conf"), "# Application Kconfig settings\n", StandardOpenOption.CREATE_NEW);
        Files.writeString(application.resolve("src/main.c"), "#include <zephyr/kernel.h>\n#include <zephyr/sys/printk.h>\n\nint main(void)\n{\n    printk(\"Hello Zephyr!\\n\");\n    return 0;\n}\n", StandardOpenOption.CREATE_NEW);
        Files.writeString(application.resolve(".gitignore"), ".zew-build/\n.zew/local*\n", StandardOpenOption.CREATE_NEW);
        Files.writeString(application.resolve("README.md"), "# Zephyr application\n\nTarget: `" + board + "`\n\nBuild in Eclipse with Project > Build Project, or in your configured Zephyr environment:\n\n```sh\nwest build -b " + board + " -d .zew-build/first .\n```\n", StandardOpenOption.CREATE_NEW);
        profile.write(application);
    }
}
