// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;

/** Small authenticated channel description; it can only update this workbench feature. */
public record ReleaseManifest(String version, String revision, String directory, String notes, List<Artifact> artifacts) {
    public static final String FEATURE = "io.github.niwantha33.zew.feature.feature.group";
    public record Artifact(String path, long size, String sha256) {}

    public static ReleaseManifest parse(String text) throws IOException {
        if (text.length() > 65536) throw new IOException("Update manifest is too large.");
        var properties = new Properties();
        try { properties.load(new StringReader(text)); }
        catch (IllegalArgumentException error) { throw new IOException("Invalid update manifest encoding.", error); }
        if (!"1".equals(properties.getProperty("schema")) || !FEATURE.equals(properties.getProperty("feature"))) {
            throw new IOException("Unsupported workbench update manifest.");
        }
        String version = required(properties, "version");
        if (!version.matches("[0-9]{1,8}\\.[0-9]{1,8}\\.[0-9]{1,8}(\\.[A-Za-z0-9_-]{1,64})?")) throw new IOException("Invalid update version.");
        String revision = required(properties, "revision");
        if (!revision.matches("[0-9a-f]{40}")) throw new IOException("Update must pin an immutable Git commit.");
        String directory = required(properties, "directory");
        validPath(directory);
        if (!directory.equals("releases/" + version)) throw new IOException("Unexpected release directory.");
        try {
            int count = Integer.parseInt(required(properties, "files"));
            if (count < 3 || count > 100) throw new IOException("Invalid update file count.");
            var artifacts = new ArrayList<Artifact>();
            var names = new HashSet<String>();
            long total = 0;
            for (int i = 0; i < count; i++) {
                String prefix = "file." + i + ".";
                String path = required(properties, prefix + "path");
                validPath(path);
                if (!(path.equals("p2.index") || path.matches("(?:content|artifacts)\\.(?:jar|xml|xml\\.xz)")
                        || path.matches("(?:plugins|features)/[A-Za-z0-9_.-]+\\.jar"))) throw new IOException("Unexpected update file type.");
                if (!names.add(path)) throw new IOException("Duplicate update file.");
                long size = Long.parseLong(required(properties, prefix + "size"));
                String hash = required(properties, prefix + "sha256");
                if (size < 1 || size > 8_388_608 || !hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid update file integrity record.");
                total += size;
                if (total > 67_108_864) throw new IOException("Update exceeds the package size limit.");
                artifacts.add(new Artifact(path, size, hash));
            }
            if (!names.contains("p2.index") || !(names.contains("content.jar") || names.contains("content.xml"))
                    || !(names.contains("artifacts.jar") || names.contains("artifacts.xml"))) throw new IOException("Incomplete p2 repository.");
            return new ReleaseManifest(version, revision, directory, properties.getProperty("notes", "Workbench maintenance update."), List.copyOf(artifacts));
        } catch (NumberFormatException error) { throw new IOException("Invalid update manifest number.", error); }
    }

    private static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("Update manifest is missing " + key + ".");
        return value;
    }

    private static void validPath(String value) throws IOException {
        if (!value.matches("[A-Za-z0-9_./-]+") || value.startsWith("/") || value.contains("..") || value.contains("//")) {
            throw new IOException("Unsafe update path.");
        }
    }
}
