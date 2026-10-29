// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Optional source-level help text. NOT a Kconfig evaluator: visibility,
 * dependency resolution and defaults remain owned by Zephyr's Kconfiglib.
 */
public final class KconfigSourceHelp {
    private KconfigSourceHelp() {}
    private static final Set<String> SKIP = Set.of(".git", "tests", "test", "samples",
            "doc", "docs", "build", ".zew-build", "twister-out", "__pycache__");
    public record Definition(Path file, int line, String sourceExcerpt) {}

    public static Definition firstDefinition(Path workspace, String symbol) throws IOException {
        return firstDefinition(workspace, symbol, () -> false);
    }

    public static Definition firstDefinition(Path workspace, String symbol,
            java.util.function.BooleanSupplier cancelled) throws IOException {
        if (symbol == null || !symbol.matches("[A-Z0-9_]{1,160}"))
            throw new IOException("Invalid Kconfig symbol.");
        if (!Files.isDirectory(workspace)) return null;
        var pattern = Pattern.compile("^\\s*(?:config|menuconfig)\\s+" +
                Pattern.quote(symbol) + "(?:\\s*(?:#.*)?)?$");
        final Definition[] found = {null};
        final int[] visited = {0};
        Files.walkFileTree(workspace, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (cancelled.getAsBoolean()) return FileVisitResult.TERMINATE;
                if (!dir.equals(workspace) && (SKIP.contains(dir.getFileName().toString())
                        || Files.isSymbolicLink(dir))) return FileVisitResult.SKIP_SUBTREE;
                return visited[0] > 12000 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (cancelled.getAsBoolean()) return FileVisitResult.TERMINATE;
                String name = file.getFileName().toString();
                if (!name.equals("Kconfig") && !name.startsWith("Kconfig.")) return FileVisitResult.CONTINUE;
                if (++visited[0] > 12000) return FileVisitResult.TERMINATE;
                if (attrs.size() > 256_000 || !attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                try {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        if (!pattern.matcher(lines.get(i)).matches()) continue;
                        var excerpt = new StringBuilder();
                        for (int n = i; n < lines.size() && n < i + 45; n++) {
                            String line = lines.get(n);
                            if (n > i && (line.matches("^\\s*(?:config|menuconfig|choice|endchoice|menu|endmenu)\\b.*")
                                    && !line.isBlank())) break;
                            excerpt.append(line).append('\n');
                            if (excerpt.length() > 2500) break;
                        }
                        found[0] = new Definition(file, i + 1, excerpt.toString().strip());
                        return FileVisitResult.TERMINATE;
                    }
                } catch (IOException | SecurityException unreadable) {
                    // Unreadable optional source metadata never blocks the editor.
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException error) {
                return FileVisitResult.CONTINUE;
            }
        });
        return found[0];
    }
}
