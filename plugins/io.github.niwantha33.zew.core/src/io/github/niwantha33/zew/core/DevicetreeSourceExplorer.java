// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Build-backed, vendor-independent DTS/DTSI source browser.
 * Only paths reported by the selected Zephyr preprocessor dependency file are
 * called build inputs. Application overlays not in that file are candidates,
 * never silently claimed to participate in the current build.
 */
public final class DevicetreeSourceExplorer {
    private static final int MAX_DEP_BYTES = 2_000_000;
    private static final int MAX_SOURCE_BYTES = 2_000_000;
    private static final int MAX_ENTRIES = 4096;
    private DevicetreeSourceExplorer() {}

    public record Source(Path path, String kind, boolean buildInput, boolean applicationOverlay) {
        public String name() { return path.getFileName().toString(); }
        public String evidence() { return buildInput ? "Build dependency" : "Application candidate — not verified in build"; }
    }

    public record Inventory(List<Source> sources, String notice) {
        public Inventory { sources = List.copyOf(sources); }
    }

    public static Inventory inspect(Path application, Path build) throws IOException {
        if (application == null || !Files.isDirectory(application))
            return new Inventory(List.of(), "No application directory selected.");
        Path app = application.toRealPath();
        Path dependency = build == null ? null : build.resolve("zephyr/zephyr.dts.d");
        LinkedHashMap<Path, Source> listed = new LinkedHashMap<>();
        boolean depsPresent = dependency != null
                && Files.isRegularFile(dependency, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(dependency);
        if (depsPresent) {
            String text = readBounded(dependency, MAX_DEP_BYTES);
            for (String token : dependencyPaths(text)) {
                if (listed.size() >= MAX_ENTRIES) break;
                try {
                    Path path = Path.of(token);
                    if (!path.isAbsolute()) path = app.resolve(path);
                    path = path.toAbsolutePath().normalize();
                    if (!supported(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            || Files.isSymbolicLink(path)) continue;
                    Path canonical = path.toRealPath();
                    boolean overlay = canonical.startsWith(app) && isOverlay(canonical);
                    listed.putIfAbsent(canonical, new Source(canonical,
                            kind(canonical, app), true, overlay));
                } catch (IOException | IllegalArgumentException ignored) {
                    // A stale or malformed build dependency is not evidence.
                }
            }
        }
        // Show the conventional app.overlay for editing even before first build;
        // it is not automatically active merely because the file exists.
        Path conventional = app.resolve("app.overlay");
        if (Files.isRegularFile(conventional, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(conventional)) {
            Path canonical = conventional.toRealPath();
            listed.putIfAbsent(canonical, new Source(canonical, "Application overlay", false, true));
        }
        String notice = !depsPresent
                ? "No selected-build zephyr.dts.d dependency evidence. Build/configure this target to discover its SoC and board inputs; application overlays shown here may be inactive."
                : "Source files are taken from this build's Zephyr preprocessor dependencies. This is not an include-order or property-origin trace; changes require a new configure/build.";
        return new Inventory(new ArrayList<>(listed.values()), notice);
    }

    /** Parse make-style dependencies, including escaped spaces and line wraps. */
    public static List<String> dependencyPaths(String makefile) throws IOException {
        if (makefile == null || makefile.length() > MAX_DEP_BYTES)
            throw new IOException("Devicetree dependencies are too large or missing.");
        String flattened = makefile.replaceAll("\\\\\r?\n", " ");
        int separator = -1;
        for (int i = 0; i < flattened.length() - 1; i++) {
            if (flattened.charAt(i) == ':' && Character.isWhitespace(flattened.charAt(i + 1))
                    && (i == 0 || flattened.charAt(i - 1) != '\\')) {
                separator = i;
                break;
            }
        }
        if (separator < 0) return List.of();
        ArrayList<String> result = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int i = separator + 1; i < flattened.length(); i++) {
            char c = flattened.charAt(i);
            if (c == '\\' && i + 1 < flattened.length()) {
                char next = flattened.charAt(i + 1);
                if (Character.isWhitespace(next) || next == '\\' || next == '#' || next == ':') {
                    word.append(next);
                    i++;
                    continue;
                }
            }
            if (Character.isWhitespace(c)) {
                if (word.length() > 0) {
                    result.add(word.toString());
                    word.setLength(0);
                    if (result.size() >= MAX_ENTRIES) break;
                }
            } else word.append(c);
        }
        if (word.length() > 0 && result.size() < MAX_ENTRIES) result.add(word.toString());
        return List.copyOf(result);
    }

    public static String sourceText(Source source) throws IOException {
        if (source == null || !supported(source.path())
                || Files.isSymbolicLink(source.path())
                || !Files.isRegularFile(source.path(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Source file is missing or unsafe.");
        return readBounded(source.path(), MAX_SOURCE_BYTES);
    }

    private static String readBounded(Path path, int limit) throws IOException {
        if (Files.size(path) > limit) throw new IOException("File exceeds the source-browser size limit: " + path.getFileName());
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static boolean supported(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".dts") || name.endsWith(".dtsi") || name.endsWith(".overlay");
    }

    private static boolean isOverlay(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".overlay");
    }

    private static String kind(Path path, Path application) {
        String value = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (path.startsWith(application)) return isOverlay(path) ? "Application overlay" : "Application DTS";
        if (value.contains("/boards/")) return "Board / shield DTS";
        if (value.contains("/soc/") || value.contains("/dts/")) return "SoC / architecture DTSI";
        if (isOverlay(path)) return "External overlay";
        return "Module / other DTS source";
    }
}
