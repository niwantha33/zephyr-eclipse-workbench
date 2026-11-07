// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Project-owned named Zephyr firmware profile. No machine-specific paths are serialized.
 * A profile is a pair of .profile metadata and .conf Kconfig fragments. The legacy
 * Application/Debug/Release configurations remain unchanged for existing projects.
 */
public record BuildProfile(String id, String label, BuildConfiguration optimization,
                           String definitions, String kconfig) {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,39}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _.-]{0,79}");
    private static final Pattern DEFINE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}(?:=(?:[0-9]{1,12}|0x[0-9A-Fa-f]{1,16}))?");
    private static final Pattern ASSIGNMENT = Pattern.compile("CONFIG_[A-Z0-9_]+=(?:y|n|[0-9]+|0x[0-9A-Fa-f]+|\\\"[^\\\"\\r\\n]{0,256}\\\")");
    private static final Pattern DISABLED = Pattern.compile("# CONFIG_[A-Z0-9_]+ is not set");
    private static final int MAX_PROPERTIES = 4096;
    private static final int MAX_FRAGMENT = 16384;

    public BuildProfile {
        if (id == null || !ID.matcher(id).matches()
                || id.equals("default") || id.equals("debug") || id.equals("release"))
            throw new IllegalArgumentException("Profile ID must be lowercase letters, digits or hyphens (not a built-in name).");
        if (label == null || !NAME.matcher(label).matches())
            throw new IllegalArgumentException("Profile name must be 1–80 plain characters.");
        if (optimization == null) throw new IllegalArgumentException("Choose an optimization policy.");
        definitions = definitions == null ? "" : definitions.trim();
        kconfig = kconfig == null ? "" : kconfig.replace("\r\n", "\n");
        parseDefinitions(definitions);
        parseConfig(kconfig);
    }

    public static List<String> parseDefinitions(String text) {
        if (text == null || text.isBlank()) return List.of();
        if (text.length() > 2048) throw new IllegalArgumentException("Too many compiler definitions.");
        String[] parts = text.strip().split("[,\\s]+");
        if (parts.length > 32) throw new IllegalArgumentException("A profile supports at most 32 definitions.");
        var found = new LinkedHashMap<String, String>();
        for (String part : parts) {
            if (!DEFINE.matcher(part).matches() || part.startsWith("CONFIG_"))
                throw new IllegalArgumentException("Invalid C macro '" + part + "'. Use application Kconfig for CONFIG_*.");
            String key = part.split("=", 2)[0];
            if (found.putIfAbsent(key, part) != null)
                throw new IllegalArgumentException("Duplicate C macro " + key);
        }
        return List.copyOf(found.values());
    }

    public static Map<String, String> parseConfig(String fragment) {
        if (fragment == null) return Map.of();
        if (fragment.getBytes(StandardCharsets.UTF_8).length > MAX_FRAGMENT)
            throw new IllegalArgumentException("Kconfig fragment exceeds 16 KiB.");
        var requests = new LinkedHashMap<String, String>();
        for (String raw : fragment.split("\\R", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") && !line.startsWith("# CONFIG_")) continue;
            String key, value;
            if (DISABLED.matcher(line).matches()) {
                key = line.substring(2, line.indexOf(" is not set"));
                value = "n";
            } else if (ASSIGNMENT.matcher(line).matches()) {
                int equals = line.indexOf('=');
                key = line.substring(0, equals);
                value = line.substring(equals + 1);
            } else {
                throw new IllegalArgumentException("Invalid Kconfig profile line: " + line);
            }
            if (requests.putIfAbsent(key, value) != null)
                throw new IllegalArgumentException("Duplicate Kconfig setting " + key);
        }
        return Map.copyOf(requests);
    }

    private static Path directory(Path application) throws IOException {
        Path root = application.resolve(".zew");
        Path dir = root.resolve("profiles");
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(dir))
            throw new IOException("Profile metadata folders must not be symbolic links.");
        return dir;
    }

    private static Path checkedFile(Path directory, String filename) throws IOException {
        Path file = directory.resolve(filename);
        if (Files.isSymbolicLink(file))
            throw new IOException("Profile files must not be symbolic links: " + filename);
        return file;
    }

    public static List<BuildProfile> list(Path application) throws IOException {
        Path dir = directory(application);
        if (!Files.exists(dir)) return List.of();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Profile directory is not a regular directory.");
        var result = new ArrayList<BuildProfile>();
        try (var files = Files.newDirectoryStream(dir, "*.profile")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!ID.matcher(name.substring(0, name.length() - ".profile".length())).matches())
                    throw new IOException("Unsafe profile filename " + name);
                result.add(read(application, name.substring(0, name.length() - ".profile".length())));
                if (result.size() > 64) throw new IOException("Too many build profiles.");
            }
        }
        result.sort(Comparator.comparing(BuildProfile::label).thenComparing(BuildProfile::id));
        return List.copyOf(result);
    }

    public static BuildProfile read(Path application, String id) throws IOException {
        if (id == null || !ID.matcher(id).matches()) throw new IOException("Invalid selected profile ID.");
        Path dir = directory(application);
        Path meta = checkedFile(dir, id + ".profile");
        Path conf = checkedFile(dir, id + ".conf");
        if (!Files.isRegularFile(meta, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(conf, LinkOption.NOFOLLOW_LINKS)
                || Files.size(meta) > MAX_PROPERTIES || Files.size(conf) > MAX_FRAGMENT)
            throw new IOException("Missing or oversized profile " + id + ". Restore the profile files before building.");
        Properties values = new Properties();
        try (var reader = new StringReader(Files.readString(meta, StandardCharsets.UTF_8))) {
            values.load(reader);
        }
        if (!"1".equals(values.getProperty("schema")) || values.size() != 4)
            throw new IOException("Unsupported build profile schema for " + id);
        try {
            return new BuildProfile(id, values.getProperty("name"),
                    BuildConfiguration.fromId(values.getProperty("optimization")),
                    values.getProperty("defines", ""), Files.readString(conf, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid profile " + id + ": " + ex.getMessage(), ex);
        }
    }

    /** Creates/updates only this named profile; nothing changes prj.conf or other profiles. */
    public void save(Path application) throws IOException {
        Path dir = directory(application);
        Files.createDirectories(dir);
        Path meta = checkedFile(dir, id + ".profile");
        Path conf = checkedFile(dir, id + ".conf");
        String contents = "schema=1\nname=" + label + "\noptimization=" + optimization.id()
                + "\ndefines=" + String.join(",", parseDefinitions(definitions)) + "\n";
        if (contents.getBytes(StandardCharsets.UTF_8).length > MAX_PROPERTIES)
            throw new IOException("Profile metadata too large.");
        replace(dir, conf, kconfig);
        replace(dir, meta, contents);
    }

    private static void replace(Path dir, Path destination, String contents) throws IOException {
        Path temp = Files.createTempFile(dir, ".profile-", ".tmp");
        try {
            Files.writeString(temp, contents, StandardCharsets.UTF_8);
            checkedFile(dir, destination.getFileName().toString());
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static void delete(Path application, String id) throws IOException {
        read(application, id); // validated existing regular files, no external path
        Path dir = directory(application);
        Files.delete(checkedFile(dir, id + ".profile"));
        Files.delete(checkedFile(dir, id + ".conf"));
    }

    /** No legacy cache is reused across named profiles. */
    public Path buildDirectory(Path defaultBoardBuild) {
        return defaultBoardBuild.resolveSibling(defaultBoardBuild.getFileName() + "-profile-" + id);
    }

    /** C definitions are explicit global compile flags; CONFIG_* belongs to the Kconfig fragment. */
    public List<String> cmakeArguments(Path application) throws IOException {
        Path fragment = checkedFile(directory(application), id + ".conf");
        if (!Files.isRegularFile(fragment, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Selected Kconfig fragment missing: " + fragment);
        var args = new ArrayList<>(optimization.cmakeArguments());
        args.add("-DEXTRA_CONF_FILE:STRING=" + WestCommands.cmakePath(fragment.toAbsolutePath().normalize().toString()));
        List<String> macros = parseDefinitions(definitions);
        if (!macros.isEmpty()) {
            String flags = String.join(" ", macros.stream().map(s -> "-D" + s).toList());
            args.add("-DCMAKE_C_FLAGS:STRING=" + flags);
            args.add("-DCMAKE_CXX_FLAGS:STRING=" + flags);
        }
        return List.copyOf(args);
    }

    /** Check effective Kconfig; a requested but rejected setting is never shown as applied. */
    public void verify(Path build) throws IOException {
        optimization.verify(build);
        Map<String, String> resolved = BuildArtifacts.configuration(build);
        for (var request : parseConfig(kconfig).entrySet()) {
            String actual = resolved.getOrDefault(request.getKey(), "n");
            if (!actual.equals(request.getValue()))
                throw new IOException("Profile " + label + " requested " + request.getKey() + "="
                        + request.getValue() + " but generated .config contains " + actual);
        }
        List<String> macros = parseDefinitions(definitions);
        if (!macros.isEmpty()) {
            Path database = build.resolve("compile_commands.json");
            if (!Files.isRegularFile(database) || Files.size(database) > 16L * 1024 * 1024)
                throw new IOException("Cannot verify profile compiler definitions: compile_commands.json unavailable.");
            String commands = Files.readString(database, StandardCharsets.UTF_8);
            for (String macro : macros) {
                String expression = "(?<![A-Za-z0-9_])-D" + Pattern.quote(macro)
                        + "(?=[\\s\\\",])";
                if (!Pattern.compile(expression).matcher(commands).find())
                    throw new IOException("Profile macro -D" + macro + " not present in compile_commands.json.");
            }
        }
    }
}
