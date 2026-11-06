// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A build-scoped, fail-closed Devicetree context. Never treat an overlay that
 * merely exists on disk as included in the selected build. The C preprocessor
 * dependency file is the authority for the actual DTS/DTSI/overlay inputs;
 * build_info.yml is optional contextual metadata, not participation proof.
 */
public final class DevicetreeOverlaySupport {
    private DevicetreeOverlaySupport() {}

    public record Context(String state, String message, Path build,
            Path baseDts, List<Path> activeOverlays, List<Path> sourceFiles,
            List<Path> includeDirs, List<Path> bindingDirs) {
        public Context {
            activeOverlays = List.copyOf(activeOverlays);
            sourceFiles = List.copyOf(sourceFiles);
            includeDirs = List.copyOf(includeDirs);
            bindingDirs = List.copyOf(bindingDirs);
        }
        public boolean verified() { return "confirmed".equals(state); }
        public boolean active(Path file) {
            if (!verified() || file == null) return false;
            try { return activeOverlays.contains(file.toRealPath()); }
            catch (IOException error) { return false; }
        }
        public String explanation(Path overlay) {
            if (!verified()) return message;
            if (active(overlay)) return "Active in the selected Zephyr build (preprocessor dependency evidence).";
            return "This overlay is NOT in the selected build's Devicetree dependencies. "
                + "Zephyr may have selected a board/SoC overlay instead of app.overlay, "
                + "or DTC_OVERLAY_FILE may override automatic discovery. "
                + "Add it to the reviewed extra-overlay selection or build arguments, "
                + "then reconfigure and inspect the resulting zephyr.dts.d.";
        }
    }

    private record Cached(String key, Context context) {}
    private static final int MAX_METADATA = 2_000_000;
    private static final int MAX_CONTEXTS = 32;
    private static final Pattern CACHE_VALUE =
            Pattern.compile("(?m)^([A-Za-z_][A-Za-z0-9_]*):[^=\\r\\n]*=(.*)$");
    private static final Pattern YAML_ITEM = Pattern.compile("^\\s+-\\s+(.+?)\\s*$");
    private static final Map<String, Cached> CACHE = new LinkedHashMap<>();

    public static Context inspect(Path application, Path build, Path zephyr) throws IOException {
        if (application == null || build == null || zephyr == null)
            return empty("no-build", "Select the exact application, build profile and Zephyr checkout.", build);
        Path app = application.toAbsolutePath().normalize();
        Path selectedBuild = build.toAbsolutePath().normalize();
        Path checkout = zephyr.toAbsolutePath().normalize();
        String identity = app + "|" + selectedBuild + "|" + checkout;
        String key = signature(selectedBuild.resolve("CMakeCache.txt")) + "|"
                + signature(selectedBuild.resolve("zephyr/zephyr.dts.d")) + "|"
                + signature(selectedBuild.resolve("build_info.yml"));
        synchronized (CACHE) {
            Cached existing = CACHE.get(identity);
            if (existing != null && existing.key().equals(key)) return existing.context();
        }
        Context resolved = readContext(app, selectedBuild, checkout);
        synchronized (CACHE) {
            CACHE.put(identity, new Cached(key, resolved));
            while (CACHE.size() > MAX_CONTEXTS) CACHE.remove(CACHE.keySet().iterator().next());
        }
        return resolved;
    }

    private static Context readContext(Path app, Path build, Path zephyr) throws IOException {
        Path cacheFile = build.resolve("CMakeCache.txt");
        if (!Files.isRegularFile(cacheFile, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(cacheFile))
            return empty("no-build", "No selected-build CMakeCache.txt. Configure this board first; overlay participation is unknown.", build);
        String cache = bounded(cacheFile);
        String actual = cacheValue(cache, "ZEPHYR_BASE");
        if (actual == null || !Path.of(actual).toAbsolutePath().normalize().equals(zephyr))
            return empty("mismatch", "The CMake cache does not identify the selected Zephyr checkout. Choose the matching build profile.", build);
        Path deps = build.resolve("zephyr/zephyr.dts.d");
        if (!Files.isRegularFile(deps, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(deps))
            return empty("unverified", "No zephyr/zephyr.dts.d dependency evidence. Reconfigure/build before claiming an overlay is active.", build);
        var inventory = DevicetreeSourceExplorer.inspect(app, build);
        List<Path> sources = inventory.sources().stream().filter(DevicetreeSourceExplorer.Source::buildInput)
                .map(DevicetreeSourceExplorer.Source::path).toList();
        if (sources.isEmpty())
            return empty("unverified",
                    "Dependency file has no readable DTS inputs. Reconfigure this exact build before verifying overlays.", build);
        List<Path> overlays = inventory.sources().stream()
                .filter(s -> s.buildInput() && s.path().getFileName().toString().endsWith(".overlay"))
                .map(DevicetreeSourceExplorer.Source::path).toList();
        Path base = inventory.sources().stream().filter(s -> s.buildInput()
                && s.kind().equals("Board / shield DTS")).map(DevicetreeSourceExplorer.Source::path)
                .findFirst().orElse(null);
        Path info = build.resolve("build_info.yml");
        Metadata metadata = metadata(info);
        String suffix = metadata.notice().isEmpty() ? ""
                : " " + metadata.notice();
        return new Context("confirmed",
                "Source participation is taken from zephyr.dts.d, not a standalone overlay parser."
                    + suffix,
                build, base, overlays, sources, metadata.includes(), metadata.bindings());
    }

    private record Metadata(List<Path> includes, List<Path> bindings, String notice) {}
    private static Metadata metadata(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file))
            return new Metadata(List.of(), List.of(), "build_info.yml is missing; dependency evidence remains authoritative.");
        try {
            String text = bounded(file);
            if (!text.contains("devicetree:"))
                throw new IOException("Missing devicetree metadata section");
            List<Path> includes = new ArrayList<>(), bindings = new ArrayList<>();
            String key = "";
            int items = 0;
            for (String line : text.split("\\R")) {
                if (line.strip().startsWith("#") || line.isBlank()) continue;
                String stripped = line.strip();
                if (stripped.matches("(?:include-dirs|bindings-dirs):\\s*")) {
                    key = stripped.substring(0, stripped.indexOf(':'));
                    continue;
                }
                if (!Character.isWhitespace(line.charAt(0))) { key = ""; continue; }
                if (stripped.endsWith(":") && !stripped.startsWith("-")) {
                    if (!stripped.startsWith("include-dirs:") && !stripped.startsWith("bindings-dirs:")) key = "";
                    continue;
                }
                Matcher item = YAML_ITEM.matcher(line);
                if (!item.matches() || key.isEmpty()) continue;
                if (++items > 256) throw new IOException("Too many metadata entries");
                String raw = item.group(1).strip();
                if (raw.length() >= 2 && (raw.startsWith("\"") && raw.endsWith("\"")
                        || raw.startsWith("'") && raw.endsWith("'")))
                    raw = raw.substring(1, raw.length() - 1);
                if (raw.isBlank() || raw.contains("\0") || raw.contains("${"))
                    continue;
                Path value = Path.of(raw);
                if (!value.isAbsolute()) continue;
                Path normalized = value.normalize();
                if ("include-dirs".equals(key)) includes.add(normalized);
                else if ("bindings-dirs".equals(key)) bindings.add(normalized);
            }
            return new Metadata(List.copyOf(new LinkedHashSet<>(includes)),
                    List.copyOf(new LinkedHashSet<>(bindings)),
                    "Optional build_info.yml metadata was read conservatively; unsupported YAML constructs are not inferred.");
        } catch (Exception error) {
            return new Metadata(List.of(), List.of(),
                    "build_info.yml could not be interpreted (" + error.getClass().getSimpleName()
                    + "); rebuild the selected target to regenerate metadata.");
        }
    }

    /** Optional, per-application extra overlays. Without this file the build is unchanged. */
    public static List<Path> selectedExtras(Path application) throws IOException {
        Path app = application.toRealPath();
        Path selection = app.resolve(".zew/overlay-selection.properties");
        if (!Files.exists(selection)) return List.of();
        if (!Files.isRegularFile(selection, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(selection)) throw new IOException("Overlay selection is not a safe regular file.");
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(selection, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        String value = properties.getProperty("extra", "").strip();
        if (value.isEmpty()) return List.of();
        String[] entries = value.split(";", -1);
        if (entries.length > 24) throw new IOException("Too many extra overlays (maximum 24).");
        LinkedHashSet<Path> resolved = new LinkedHashSet<>();
        for (String entry : entries) {
            String name = entry.strip();
            if (name.isEmpty() || name.length() > 300 || name.indexOf('\\') >= 0)
                throw new IOException("Invalid extra overlay entry. Use a relative path under the project.");
            Path relative = Path.of(name);
            if (relative.isAbsolute() || relative.normalize().startsWith("..")
                    || !name.endsWith(".overlay"))
                throw new IOException("Extra overlays must be relative .overlay files inside the application.");
            Path candidate = app.resolve(relative).normalize();
            if (!candidate.startsWith(app) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(candidate) || !candidate.toRealPath().startsWith(app))
                throw new IOException("Selected overlay is missing, linked or outside the application: " + name);
            if (!resolved.add(candidate.toRealPath()))
                throw new IOException("Duplicate extra overlay entry: " + name);
        }
        return List.copyOf(resolved);
    }

    /** An explicit extra selection never replaces Zephyr's automatic board/app discovery. */
    public static List<String> extraCmakeArguments(Path app) throws IOException {
        List<Path> extras = selectedExtras(app);
        if (extras.isEmpty()) return List.of();
        String joined = String.join(";", extras.stream()
                .map(path -> path.toString().replace('\\', '/')).toList());
        return List.of("-DEXTRA_DTC_OVERLAY_FILE:STRING=" + joined);
    }

    private static String cacheValue(String text, String name) {
        Matcher match = CACHE_VALUE.matcher(text);
        while (match.find()) if (name.equals(match.group(1))) return match.group(2).strip();
        return null;
    }
    private static String bounded(Path file) throws IOException {
        if (Files.size(file) > MAX_METADATA) throw new IOException("Overlay metadata exceeds 2 MB.");
        return Files.readString(file, StandardCharsets.UTF_8);
    }
    private static String signature(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) return "missing";
        return Files.size(file) + ":" + Files.getLastModifiedTime(file)
                .to(java.util.concurrent.TimeUnit.NANOSECONDS);
    }
    private static Context empty(String state, String message, Path build) {
        return new Context(state, message, build, null, List.of(), List.of(), List.of(), List.of());
    }
}
