// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.ZephyrProjectFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;

public final class ProjectFilesTests {
    private static int passed;
    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
        passed++;
        System.out.println("PASS project: " + why);
    }
    private static void rejects(Throwing code, String why) throws Exception {
        try { code.run(); throw new AssertionError(why); }
        catch (java.io.IOException | IllegalArgumentException expected) { check(true, why); }
    }
    @FunctionalInterface interface Throwing { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("zephyr project Ω spaces ");
        try {
            rejects(() -> ZephyrProjectFiles.requireManagedEnvironment(root), "setup not ready");
            Files.writeString(root.resolve(".zew-managed"), "first-build-win-x64-v1\n");
            Files.writeString(root.resolve(".zew-ready"), "ready\n");
            Files.writeString(root.resolve("environment.properties"), "python=C:\\\\managed\\\\python.exe\n");
            ZephyrProjectFiles.requireManagedEnvironment(root);
            check(true, "verified managed installation accepted");
            rejects(() -> ZephyrProjectFiles.requireAvailableBoard("esp32s3_devkitc/esp32s3/procpu"),
                    "cannot use uninstalled Xtensa compiler");
            rejects(() -> ZephyrProjectFiles.validateName("../bad"), "project name traversal rejected");

            var folder = root.resolve("projects/Hello Ω");
            Files.createDirectories(folder.getParent());
            ZephyrProjectFiles.createNew(folder, "Hello_Zephyr");
            String cmake = Files.readString(folder.resolve("CMakeLists.txt"));
            check(cmake.contains("project(Hello_Zephyr)"), "standalone CMake application");
            String source = Files.readString(folder.resolve("src/main.c"));
            check(source.contains("printk(\"Hello from Hello_Zephyr!\\n\")"), "literal C newline in generated starter");
            check(ZephyrProjectFiles.readBoard(folder).equals("qemu_x86/atom"), "exact target stored in portable project");
            String marker = Files.readString(folder.resolve(".zew/project.properties"), StandardCharsets.UTF_8);
            check(!marker.contains(root.toString()), "machine paths are not committed to project");
            Files.writeString(folder.resolve("src/main.c"), "MY USER EDIT\n");
            rejects(() -> ZephyrProjectFiles.createNew(folder, "Hello_Zephyr"), "nonempty project cannot be overwritten");
            check(Files.readString(folder.resolve("src/main.c")).equals("MY USER EDIT\n"), "user edit preserved");
            ZephyrProjectFiles.importExisting(folder);
            check(Files.readString(folder.resolve("src/main.c")).equals("MY USER EDIT\n"), "repeat import preserves source");

            Path external = root.resolve("External Project"); Files.createDirectories(external);
            Files.writeString(external.resolve("CMakeLists.txt"), "find_package(Zephyr REQUIRED)\n");
            Files.writeString(external.resolve("existing.c"), "KEEP ME\n");
            ZephyrProjectFiles.importExisting(external);
            check(Files.readString(external.resolve("existing.c")).equals("KEEP ME\n"), "import does not change user application files");
            check(ZephyrProjectFiles.readBoard(external).equals("qemu_x86/atom"), "import adds exact toolchain profile");
            Files.writeString(external.resolve(".zew/project.properties"), "schema=1\nboard=other_board\nzephyr=4.4.0\n");
            rejects(() -> ZephyrProjectFiles.importExisting(external), "foreign board profile not overwritten");
            check(Files.readString(external.resolve("existing.c")).equals("KEEP ME\n"), "failed import preserves source");
        } finally {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("Passed " + passed + " project tests.");
    }
}
