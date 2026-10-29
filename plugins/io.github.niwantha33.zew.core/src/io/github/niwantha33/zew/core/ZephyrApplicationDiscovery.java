// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Read-only, bounded discovery of application roots, never a source-tree importer. */
public final class ZephyrApplicationDiscovery {
    private static final Set<String> EXCLUDED = Set.of(".git", ".svn", ".metadata", ".zew", ".zew-build",
            ".zew-traces", ".zew-path-aliases", ".venv", "venv", "node_modules", "build", "sdk", "hosttools");
    private static final Pattern DATA = Pattern.compile(
            "(?s)#\\[(=*)\\[.*?\\]\\1\\]|#[^\\r\\n]*|\"(?:\\\\.|[^\"\\\\])*\"|\\[(=*)\\[.*?\\]\\2\\]");
    private static final Pattern ZEPHYR = Pattern.compile("\\b(?i:find_package)\\s*\\(\\s*Zephyr(?:\\s|\\))");
    private static final int MAX_DIRECTORIES = 20000;
    private static final int MAX_RESULTS = 500;
    private static final int MAX_DEPTH = 12;
    private static final int MAX_CMAKE_BYTES = 1024 * 1024;

    public record Result(List<Path> applications, List<String> warnings) {
        public Result { applications = List.copyOf(applications); warnings = List.copyOf(warnings); }
    }

    private ZephyrApplicationDiscovery() {}

    public static boolean isApplication(Path directory) throws IOException {
        Path cmake = directory.resolve("CMakeLists.txt");
        if (!Files.isRegularFile(cmake, LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.size(cmake) > MAX_CMAKE_BYTES) throw new IOException("CMakeLists.txt exceeds the search size limit: " + directory);
        // Ignore comments and literal data, including examples inside message().
        // A quoted package name is valid CMake and is kept as a token.
        var matcher = DATA.matcher(Files.readString(cmake));
        StringBuilder code = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(code, matcher.group().equals("\"Zephyr\"") ? "Zephyr" : " ");
        matcher.appendTail(code);
        return ZEPHYR.matcher(code).find();
    }

    public static Result scan(Path folder, boolean nested, BooleanSupplier cancelled) throws IOException {
        if (folder == null || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Choose an existing source directory, not a ZIP file or filesystem link.");
        Path root = folder.toRealPath();
        var applications = new ArrayList<Path>();
        var warnings = new ArrayList<String>();
        var realDirectories = new HashSet<Path>();
        Files.walkFileTree(root, Set.of(), nested ? MAX_DEPTH + 1 : 1, new SimpleFileVisitor<Path>() {
            int visited;
            void check() throws IOException {
                if (cancelled.getAsBoolean()) throw new InterruptedIOException("Project search cancelled.");
                if (++visited > MAX_DIRECTORIES) throw new IOException("Project search reached 20,000 directories. Select a narrower parent folder.");
            }
            void warn(String message) { if (warnings.size() < 20) warnings.add(message); }
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                check();
                String name = directory.getFileName() == null ? "" : directory.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!directory.equals(root) && (EXCLUDED.contains(name) || name.startsWith("build-")))
                    return FileVisitResult.SKIP_SUBTREE;
                Path real;
                try { real = directory.toRealPath(); }
                catch (IOException error) { warn("Cannot resolve: " + directory); return FileVisitResult.SKIP_SUBTREE; }
                if (!real.startsWith(root) || !realDirectories.add(real)) {
                    warn("Skipped a linked or repeated directory: " + directory);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (Files.isRegularFile(directory.resolve("Kconfig.zephyr")) && Files.isRegularFile(directory.resolve("VERSION"))) {
                    warn("Skipped the upstream Zephyr source tree: " + directory);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                boolean application = false;
                try { application = isApplication(directory); }
                catch (IOException error) { warn("Cannot inspect: " + directory + " (" + error.getMessage() + ")"); }
                if (application) {
                    if (applications.size() == MAX_RESULTS) throw new IOException("More than 500 applications found. Select a narrower parent folder.");
                    applications.add(real);
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (cancelled.getAsBoolean()) throw new InterruptedIOException("Project search cancelled.");
                if (nested && attributes.isDirectory()) warn("Search depth limit reached at: " + file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException error) {
                warn("Cannot search: " + file + " (" + error.getMessage() + ")");
                return FileVisitResult.CONTINUE;
            }
        });
        applications.sort(Comparator.comparing(Path::toString));
        return new Result(applications, warnings);
    }
}
