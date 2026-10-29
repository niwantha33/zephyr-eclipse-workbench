// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.ZephyrApplicationDiscovery;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class ApplicationDiscoveryTests {
    private static int passed;
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
        passed++;
    }
    private static Path app(Path folder) throws IOException {
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("CMakeLists.txt"), "cmake_minimum_required(VERSION 3.20)\nfind_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})\nproject(test)\n");
        return folder;
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("Import projects Ω spaces ");
        try {
            Path wrapped = app(root.resolve("extracted ZIP/Zephyr_RTOS_Analysis_Demo"));
            Path other = app(root.resolve("another project"));
            Files.writeString(wrapped.resolve("prj.conf"), "USER CONFIG\n");
            Files.writeString(wrapped.resolve(".project"), "USER ECLIPSE METADATA\n");
            Files.writeString(wrapped.resolve("main.c"), "USER SOURCE\n");
            var result = ZephyrApplicationDiscovery.scan(root, true, () -> false);
            check(result.applications().containsAll(List.of(wrapped.toRealPath(), other.toRealPath())), "parent folder discovers multiple applications and ZIP wrapper directories");
            check(result.applications().size() == 2, "parent wrapper is not itself an application");
            check(result.warnings().isEmpty(), "ordinary recursive search has no warnings");
            check(ZephyrApplicationDiscovery.scan(wrapped, false, () -> false).applications().equals(List.of(wrapped.toRealPath())), "exact application root works without recursion");
            check(ZephyrApplicationDiscovery.scan(root, false, () -> false).applications().isEmpty(), "nested search can be disabled");
            check(ZephyrApplicationDiscovery.scan(root, false, () -> false).warnings().isEmpty(), "disabled recursion is not reported as a depth error");
            check(Files.readString(wrapped.resolve(".project")).equals("USER ECLIPSE METADATA\n")
                    && Files.readString(wrapped.resolve("main.c")).equals("USER SOURCE\n")
                    && Files.readString(wrapped.resolve("prj.conf")).equals("USER CONFIG\n"), "discovery preserves source, configuration and Eclipse metadata byte-for-byte");

            for (String ignored : List.of(".git", ".zew-build", "build", "build-debug", "SDK", "node_modules")) app(root.resolve(ignored).resolve("fake"));
            check(ZephyrApplicationDiscovery.scan(root, true, () -> false).applications().size() == 2, "generated output, dependencies and tools are excluded");
            Path generic = root.resolve("generic"); Files.createDirectories(generic);
            Files.writeString(generic.resolve("CMakeLists.txt"), "project(not_zephyr)\n# find_package(Zephyr REQUIRED)\nmessage(\"find_package(Zephyr REQUIRED)\")\n#[=[ find_package(Zephyr REQUIRED) ]=]\nset(example [=[find_package(Zephyr REQUIRED)]=])\n");
            check(!ZephyrApplicationDiscovery.isApplication(generic), "comments and quoted/bracket examples do not misidentify generic CMake projects");
            Files.writeString(generic.resolve("CMakeLists.txt"), "# harmless comment\nFIND_PACKAGE(\n \"Zephyr\" REQUIRED\n)\n");
            check(ZephyrApplicationDiscovery.isApplication(generic), "quoted package names and multiline CMake commands are supported");
            Files.delete(generic.resolve("CMakeLists.txt"));

            Path upstream = app(root.resolve("upstream"));
            Files.writeString(upstream.resolve("Kconfig.zephyr"), "# Zephyr\n");
            Files.writeString(upstream.resolve("VERSION"), "VERSION_MAJOR = 4\n");
            app(upstream.resolve("samples/hello_world"));
            var skipped = ZephyrApplicationDiscovery.scan(root, true, () -> false);
            check(skipped.applications().size() == 2 && skipped.warnings().stream().anyMatch(s -> s.contains("upstream Zephyr")), "upstream checkout is not imported or traversed");

            Path oversized = root.resolve("large"); Files.createDirectories(oversized);
            Files.writeString(oversized.resolve("CMakeLists.txt"), "x".repeat(1024 * 1024 + 1));
            var bounded = ZephyrApplicationDiscovery.scan(root, true, () -> false);
            check(bounded.applications().size() == 2 && bounded.warnings().stream().anyMatch(s -> s.contains("size limit")), "oversized CMake input reports a warning without hiding other applications");

            try { ZephyrApplicationDiscovery.scan(root, true, () -> true); throw new AssertionError("cancel ignored"); }
            catch (InterruptedIOException expected) { check(true, "cancellation interrupts discovery"); }
            try { ZephyrApplicationDiscovery.scan(root.resolve("missing"), true, () -> false); throw new AssertionError("missing root accepted"); }
            catch (IOException expected) { check(true, "missing roots are rejected"); }
            Path archive = root.resolve("archive.zip"); Files.writeString(archive, "not a directory");
            try { ZephyrApplicationDiscovery.scan(archive, true, () -> false); throw new AssertionError("archive accepted as directory"); }
            catch (IOException expected) { check(true, "ZIP input receives directory guidance"); }

            try {
                Files.createSymbolicLink(root.resolve("linked"), wrapped);
                check(ZephyrApplicationDiscovery.scan(root, true, () -> false).applications().size() == 2, "directory links do not duplicate projects or create recursion loops");
            } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
                System.out.println("SKIP symbolic-link fixture: " + unsupported.getClass().getSimpleName());
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("Passed " + passed + " application discovery checks.");
    }
}
