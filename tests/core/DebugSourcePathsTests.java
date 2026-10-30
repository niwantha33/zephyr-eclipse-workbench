// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.DebugSourcePaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class DebugSourcePathsTests {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("zew-debug paths Ω ");
        try {
            Path app = Files.createDirectory(root.resolve("application"));
            Path zephyr = Files.createDirectory(root.resolve("zephyr"));
            Path build = Files.createDirectory(root.resolve("build"));
            check(DebugSourcePaths.inspect(build, app, zephyr).isEmpty(), "missing cache gives no guessed mapping");
            Path foreign = Files.createDirectory(root.resolve("different-app"));
            Path cache = build.resolve("CMakeCache.txt");
            Files.writeString(cache, "APPLICATION_SOURCE_DIR:PATH=" + foreign + "\nZEPHYR_BASE:PATH=/missing/zephyr\n");
            check(DebugSourcePaths.inspect(build, app, zephyr).isEmpty(), "unrelated source directories are never substituted");
            Files.writeString(cache, "APPLICATION_SOURCE_DIR:PATH=" + app.toRealPath() + "\n");
            check(DebugSourcePaths.inspect(build, app, zephyr).isEmpty(), "identical paths need no substitution");
            Path alias = root.resolve("compile-alias");
            try {
                Files.createSymbolicLink(alias, app);
                Path zephyrAlias = root.resolve("zephyr-alias");
                Files.createSymbolicLink(zephyrAlias, zephyr);
                Files.writeString(cache, "APPLICATION_SOURCE_DIR:PATH=" + alias + "\nCMAKE_HOME_DIRECTORY:INTERNAL=" + alias
                        + "\nZEPHYR_BASE:PATH=" + zephyrAlias + "\n");
                var mappings = DebugSourcePaths.inspect(build, app, zephyr);
                check(mappings.size() == 2, "same-file aliases produce deduplicated source and Zephyr substitutions");
                check(mappings.getFirst().compilationPath().equals(alias.toString().replace('\\', '/'))
                        && mappings.getFirst().localPath().equals(app.toRealPath().toString().replace('\\', '/')),
                        "mapping preserves compiler spelling and resolves local source identity");
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                System.out.println("SKIP native source alias fixture: " + unavailable.getMessage());
            }
            System.out.println("DebugSourcePathsTests PASS");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
