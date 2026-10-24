// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/** Project files contain no absolute machine paths; the managed toolchain stays in Eclipse metadata. */
public final class ZephyrProjectFiles {
    public static final String FIRST_PROFILE_BOARD = "qemu_x86/atom";
    public static final String FIRST_PROFILE_ZEPHYR = "4.4.0";
    private static final String METADATA = ".zew/project.properties";

    private ZephyrProjectFiles() {}

    public static void validateName(String name) {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_-]{0,79}")) {
            throw new IllegalArgumentException("Use a project name starting with a letter, containing only letters, digits, '-' or '_'.");
        }
    }

    public static void requireManagedEnvironment(Path root) throws IOException {
        if (root == null || !Files.isDirectory(root)
                || !Files.isRegularFile(root.resolve(".zew-managed"))
                || !Files.readString(root.resolve(".zew-managed")).equals("first-build-win-x64-v1\n")
                || !Files.isRegularFile(root.resolve(".zew-ready"))
                || !Files.isRegularFile(root.resolve("environment.properties"))) {
            throw new IOException("Choose a completed Set Up Zephyr installation (with .zew-ready and environment.properties).");
        }
    }

    public static void requireAvailableBoard(String board) {
        if (!FIRST_PROFILE_BOARD.equals(board)) {
            throw new IllegalArgumentException("The current managed SDK contains only the qemu_x86/atom toolchain. Install and verify a matching compiler profile before selecting another board.");
        }
    }

    public static Path createNew(Path folder, String name) throws IOException {
        validateName(name);
        Path target = folder.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(target) || Files.isSymbolicLink(target.getParent())) {
            throw new IOException("Cannot create an application through a filesystem link.");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Project path is not a directory.");
            try (var entries = Files.list(target)) {
                if (entries.findAny().isPresent()) throw new IOException("The project directory is not empty. Choose Import Existing instead; nothing was overwritten.");
            }
        } else {
            Files.createDirectories(target);
        }

        // Never replace an existing user's source file, even on a setup retry.
        Files.createDirectories(target.resolve("src"));
        Files.writeString(target.resolve("CMakeLists.txt"),
                "cmake_minimum_required(VERSION 3.20.5)\nfind_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})\nproject(" + name + ")\ntarget_sources(app PRIVATE src/main.c)\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(target.resolve("prj.conf"), "# Project Kconfig overrides\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(target.resolve("src/main.c"),
                "#include <zephyr/kernel.h>\n#include <zephyr/sys/printk.h>\n\nint main(void)\n{\n    printk(\"Hello from " + name + "!\\n\");\n    return 0;\n}\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(target.resolve(".gitignore"), ".zew-build/\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(target.resolve("README.md"),
                "# " + name + "\n\nZephyr application for `" + FIRST_PROFILE_BOARD
                + "`. The first profile builds without a physical board.\n\n"
                + "Use Eclipse Project > Build Project, or from a configured Zephyr environment run "
                + "`west build -b " + FIRST_PROFILE_BOARD + " .`.\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        writeMarker(target);
        return target;
    }

    /** Existing application contents are never modified except an additive portable .zew marker. */
    public static Path importExisting(Path folder) throws IOException {
        Path target = folder.toRealPath();
        if (!Files.isRegularFile(target.resolve("CMakeLists.txt"))) {
            throw new IOException("Existing Zephyr application must contain CMakeLists.txt; no user files changed.");
        }
        Path metadata = target.resolve(METADATA);
        if (Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS) || !FIRST_PROFILE_BOARD.equals(readBoard(target))) {
                throw new IOException("Existing .zew metadata uses another board/profile. Project was preserved.");
            }
        } else {
            writeMarker(target);
        }
        return target;
    }

    public static String readBoard(Path project) throws IOException {
        Path metadata = project.resolve(METADATA);
        if (!Files.isRegularFile(metadata)) throw new IOException("Missing .zew/project.properties; import the application first.");
        var props = new Properties();
        try (var input = Files.newBufferedReader(metadata, StandardCharsets.UTF_8)) { props.load(input); }
        if (!"1".equals(props.getProperty("schema")) || !FIRST_PROFILE_ZEPHYR.equals(props.getProperty("zephyr"))) {
            throw new IOException("Unsupported .zew metadata schema or Zephyr version; project was preserved.");
        }
        String board = props.getProperty("board", "");
        requireAvailableBoard(board);
        return board;
    }

    private static void writeMarker(Path target) throws IOException {
        Path directory = target.resolve(".zew");
        if (Files.isSymbolicLink(directory)) throw new IOException("Refusing to write through .zew symlink.");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("project.properties"),
                "schema=1\nboard=" + FIRST_PROFILE_BOARD + "\nzephyr=" + FIRST_PROFILE_ZEPHYR + "\nmodules=none\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
}
