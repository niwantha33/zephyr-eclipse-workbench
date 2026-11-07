// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable, build-local fingerprints captured after a successful managed
 * Zephyr build, then checked before displaying evidence as current.
 *
 * This does NOT reconstruct complete Kconfig origin/driver semantics. The
 * receipt covers the exact recorded generated outputs, CMake configuration,
 * declared configuration fragments, and DTS inputs reported by Zephyr's
 * preprocessor dependency file. An absent/partial receipt is UNVERIFIED,
 * NEVER silently treated as CURRENT. Nothing under the application is written.
 */
public final class ConfigurationEvidenceReceipt {
    private ConfigurationEvidenceReceipt() {}

    public enum State { CURRENT, CHANGED, UNVERIFIED, NO_BUILD }
    public record Result(State state, String detail, int sourceCount,
                         String configSha256, String devicetreeSha256) {
        public boolean current() { return state == State.CURRENT; }
    }

    private record Entry(char kind, Path file, String sha) {}
    private record InputSet(List<Path> files, boolean complete, String limitation) {}

    private static final String MAGIC = "ZEW-CONFIG-RECEIPT-1";
    private static final String NAME = "configuration-evidence-v1.txt";
    private static final int MAX_MANIFEST_BYTES = 1_048_576;
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    private static final int MAX_INPUTS = 4096;

    public static Path receiptPath(Path build) {
        return build.resolve(".zew").resolve(NAME);
    }

    /** Call only after the selected build has verified its generated outputs. */
    public static Result capture(Path application, Path build, String board,
                                 String profile) throws IOException {
        if (!outputsExist(build)) return unknown(State.NO_BUILD,
                "Missing generated .config or zephyr.dts; no evidence receipt was written.");
        if (application == null || !Files.isDirectory(application))
            return unknown(State.UNVERIFIED, "Application directory is unavailable.");
        Path app = application.toRealPath();
        Path root = build.toRealPath();
        InputSet sources = findInputs(app, root, profile);
        String configHash = hash(root.resolve("zephyr/.config"));
        String dtsHash = hash(root.resolve("zephyr/zephyr.dts"));
        String cacheHash = hash(root.resolve("CMakeCache.txt"));

        var entries = new ArrayList<Entry>();
        var known = new HashSet<Path>();
        boolean complete = sources.complete();
        String issue = sources.limitation();
        for (Path source : sources.files()) {
            if (!known.add(source)) continue;
            if (entries.size() >= MAX_INPUTS) {
                complete = false;
                issue = "Too many source inputs to capture safely.";
                break;
            }
            try {
                String fingerprint = hash(source);
                // An application/source edit AFTER the last generated output
                // may have happened during a successful compilation. The
                // hash would be fresh but NOT part of those generated outputs.
                // Degrade the receipt rather than certifying a mixed snapshot.
                // CMake can refresh its cache without rewriting the generated
                // Devicetree. Comparing CMakeLists.txt against zephyr.dts
                // incorrectly marks a successful rebuild incomplete forever.
                String sourceName = source.getFileName().toString();
                Path target = sourceName.equals("CMakeLists.txt") || sourceName.endsWith(".profile")
                        ? root.resolve("CMakeCache.txt")
                        : sourceName.endsWith(".conf") || sourceName.equals("Kconfig")
                            || sourceName.startsWith("Kconfig.")
                        ? root.resolve("zephyr/.config") : root.resolve("zephyr/zephyr.dts");
                if (Files.getLastModifiedTime(source).compareTo(
                        Files.getLastModifiedTime(target)) > 0) {
                    complete = false;
                    issue = "At least one input changed after the generated configuration output.";
                }
                entries.add(new Entry('S', source, fingerprint));
            } catch (IOException invalid) {
                complete = false;
                issue = "A selected source input could not be hashed safely.";
            }
        }

        StringBuilder output = new StringBuilder(MAGIC).append('\n');
        output.append("B\t").append(encode(board)).append('\n');
        output.append("P\t").append(encode(profile)).append('\n');
        output.append("A\t").append(encode(app.toString())).append('\n');
        output.append("C\t").append(complete ? '1' : '0').append('\n');
        // Keep the original bounded reason in the build-local receipt so a
        // later Studio refresh can explain persistent UNVERIFIED state.
        if (!complete) {
            String reason = issue == null || issue.isBlank()
                    ? "Source input coverage could not be verified." : issue;
            output.append("L\t").append(encode(reason.substring(0, Math.min(200, reason.length()))))
                    .append('\n');
        }
        output.append("O\t").append(encode(root.resolve("zephyr/.config").toString()))
                .append('\t').append(configHash).append('\n');
        output.append("O\t").append(encode(root.resolve("zephyr/zephyr.dts").toString()))
                .append('\t').append(dtsHash).append('\n');
        output.append("O\t").append(encode(root.resolve("CMakeCache.txt").toString()))
                .append('\t').append(cacheHash).append('\n');
        for (Entry entry : entries)
            output.append("S\t").append(encode(entry.file().toString()))
                    .append('\t').append(entry.sha()).append('\n');
        if (output.length() > MAX_MANIFEST_BYTES)
            return unknown(State.UNVERIFIED, "Input receipt exceeds the safe size limit.");

        Path directory = root.resolve(".zew");
        if (Files.isSymbolicLink(directory))
            return unknown(State.UNVERIFIED, "Unsafe build-local evidence directory.");
        Files.createDirectories(directory);
        Path destination = receiptPath(root);
        if (Files.isSymbolicLink(destination))
            return unknown(State.UNVERIFIED, "Unsafe existing evidence receipt.");
        Path temporary = Files.createTempFile(directory, "configuration-evidence-", ".tmp");
        try {
            Files.writeString(temporary, output, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new Result(complete ? State.CURRENT : State.UNVERIFIED,
                complete
                    ? "Recorded output and selected input hashes. All tracked inputs matched at capture. "
                        + "Full Kconfig origin semantics and runtime behaviour are not established."
                    : "Partial input receipt: " + issue
                        + " Do not treat the last generated configuration as current.",
                entries.size(), configHash, dtsHash);
    }

    /** No writes, subprocesses or inferred origins; comparison uses exact bytes. */
    public static Result inspect(Path application, Path build, String board, String profile) {
        if (!outputsExist(build)) return unknown(State.NO_BUILD,
                "No complete generated Kconfig/Devicetree pair for this build.");
        if (application == null || !Files.isDirectory(application))
            return unknown(State.UNVERIFIED, "Selected application is unavailable.");
        Path receipt = receiptPath(build);
        if (Files.isSymbolicLink(receipt)
                || !Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS))
            return unknown(State.UNVERIFIED,
                    "Build has no trusted input receipt. Rebuild once to capture source provenance.");
        try {
            if (Files.size(receipt) > MAX_MANIFEST_BYTES)
                return unknown(State.UNVERIFIED, "Oversized evidence receipt.");
            List<String> lines = Files.readAllLines(receipt, StandardCharsets.UTF_8);
            if (lines.size() < 9 || !MAGIC.equals(lines.get(0)))
                return unknown(State.UNVERIFIED, "Invalid evidence receipt format.");
            Path app = application.toRealPath();
            Path root = build.toRealPath();
            if (!("B\t" + encode(board)).equals(lines.get(1))
                    || !("P\t" + encode(profile)).equals(lines.get(2))
                    || !("A\t" + encode(app.toString())).equals(lines.get(3)))
                return unknown(State.CHANGED, "Different project, board or build profile selected.");
            boolean complete = "C\t1".equals(lines.get(4));
            if (!complete && !"C\t0".equals(lines.get(4)))
                return unknown(State.UNVERIFIED, "Unrecognized receipt coverage state.");
            int outputs = 0, sources = 0;
            String configHash = "", dtsHash = "", incompleteReason = "";
            Set<String> seen = new HashSet<>();
            for (int i = 5; i < lines.size(); i++) {
                String[] fields = lines.get(i).split("\t", -1);
                if (fields.length == 1 && fields[0].isEmpty() && i == lines.size() - 1) continue;
                if ("L".equals(fields[0])) {
                    if (complete || fields.length != 2 || !incompleteReason.isEmpty())
                        return unknown(State.UNVERIFIED, "Malformed input coverage reason.");
                    incompleteReason = decode(fields[1]);
                    if (incompleteReason.isBlank() || incompleteReason.length() > 200
                            || incompleteReason.chars().anyMatch(c -> Character.isISOControl(c)))
                        return unknown(State.UNVERIFIED, "Invalid input coverage reason.");
                    continue;
                }
                if (fields.length != 3 || (!"S".equals(fields[0]) && !"O".equals(fields[0]))
                        || !fields[2].matches("[0-9a-f]{64}"))
                    return unknown(State.UNVERIFIED, "Malformed receipt entry.");
                String decoded = decode(fields[1]);
                if (!seen.add(fields[0] + decoded))
                    return unknown(State.UNVERIFIED, "Duplicate receipt path.");
                Path file = Path.of(decoded);
                if (!file.isAbsolute())
                    return unknown(State.UNVERIFIED, "Non-absolute receipt entry.");
                if ("O".equals(fields[0])) {
                    outputs++;
                    if (file.equals(root.resolve("zephyr/.config"))) configHash = fields[2];
                    if (file.equals(root.resolve("zephyr/zephyr.dts"))) dtsHash = fields[2];
                } else sources++;
                if (sources > MAX_INPUTS)
                    return unknown(State.UNVERIFIED, "Too many source entries.");
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(file))
                    return unknown(State.CHANGED, "Recorded source or output no longer exists safely.");
                if (!hash(file).equals(fields[2]))
                    return new Result(State.CHANGED,
                            "A recorded source input or generated artifact changed after capture. "
                            + "Rebuild the exact board/profile.", sources, configHash, dtsHash);
            }
            if (outputs != 3 || configHash.isEmpty() || dtsHash.isEmpty()
                    || !seen.contains("O" + root.resolve("CMakeCache.txt")))
                return unknown(State.UNVERIFIED, "Incomplete generated-output provenance.");
            if (!complete) return new Result(State.UNVERIFIED,
                    "Input coverage was incomplete at capture"
                        + (incompleteReason.isBlank() ? "." : ": " + incompleteReason)
                        + " Inspect this build's generated dependencies before rebuilding.",
                    sources, configHash, dtsHash);
            return new Result(State.CURRENT,
                    "Recorded build outputs and tracked inputs match their captured SHA-256 hashes. "
                    + "This is not full Kconfig origin, compiled-driver or runtime validation.",
                    sources, configHash, dtsHash);
        } catch (IOException | IllegalArgumentException failed) {
            return unknown(State.UNVERIFIED, "Could not safely verify evidence receipt.");
        }
    }

    private static InputSet findInputs(Path app, Path build, String profile) throws IOException {
        var found = new LinkedHashSet<Path>();
        boolean complete = true;
        String limitation = "";
        if (Files.isRegularFile(app.resolve("CMakeLists.txt"), LinkOption.NOFOLLOW_LINKS))
            found.add(app.resolve("CMakeLists.txt").toRealPath());
        if (Files.isRegularFile(app.resolve("Kconfig"), LinkOption.NOFOLLOW_LINKS))
            found.add(app.resolve("Kconfig").toRealPath());
        // A named profile can change C compiler macros without changing prj.conf.
        // Its project-owned manifest must therefore participate in build freshness.
        if (profile != null && profile.startsWith("profile-")) {
            String id = profile.substring("profile-".length());
            BuildProfile.read(app, id); // fail closed on missing or invalid profile
            found.add(app.resolve(".zew/profiles").resolve(id + ".profile").toRealPath());
        }

        Path cache = build.resolve("CMakeCache.txt");
        if (!Files.isRegularFile(cache, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(cache))
            return new InputSet(List.copyOf(found), false, "Missing selected-build CMakeCache.txt.");
        if (Files.size(cache) > MAX_FILE_BYTES)
            return new InputSet(List.copyOf(found), false, "Oversized CMake cache.");
        String cacheText = Files.readString(cache, StandardCharsets.UTF_8);
        if (cacheText.length() > MAX_FILE_BYTES)
            return new InputSet(List.copyOf(found), false, "Oversized CMake cache.");

        boolean explicit = false;
        for (String row : cacheText.split("\\R")) {
            int colon = row.indexOf(':'), equals = row.indexOf('=');
            if (colon <= 0 || equals <= colon || row.startsWith("//") || row.startsWith("#"))
                continue;
            String key = row.substring(0, colon);
            if (!List.of("CONF_FILE", "EXTRA_CONF_FILE", "OVERLAY_CONFIG",
                    "DTC_OVERLAY_FILE", "EXTRA_DTC_OVERLAY_FILE").contains(key))
                continue;
            if ("CONF_FILE".equals(key)) explicit = true;
            String value = row.substring(equals + 1).strip();
            if (value.isBlank()) continue;
            for (String token : value.split(";")) {
                if (token.isBlank()) continue;
                Path candidate;
                try {
                    candidate = Path.of(token);
                    if (!candidate.isAbsolute()) candidate = app.resolve(candidate);
                    candidate = candidate.toAbsolutePath().normalize();
                } catch (RuntimeException invalid) {
                    complete = false; limitation = "Invalid source path in selected CMake cache.";
                    continue;
                }
                if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(candidate)) {
                    complete = false; limitation = "A declared configuration fragment is missing.";
                } else found.add(candidate.toRealPath());
            }
        }
        if (!explicit) {
            Path defaultConf = app.resolve("prj.conf");
            if (Files.isRegularFile(defaultConf, LinkOption.NOFOLLOW_LINKS))
                found.add(defaultConf.toRealPath());
        }

        Path dep = build.resolve("zephyr/zephyr.dts.d");
        if (!Files.isRegularFile(dep, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(dep)) {
            complete = false; limitation = "Zephyr DTS dependency file is unavailable.";
        } else {
            var inventory = DevicetreeSourceExplorer.inspect(app, build);
            long count = inventory.sources().stream().filter(DevicetreeSourceExplorer.Source::buildInput)
                    .count();
            if (count == 0) {
                complete = false; limitation = "No active DTS/DTSI inputs could be verified.";
            }
            for (var source : inventory.sources())
                if (source.buildInput()) found.add(source.path());
        }
        if (found.size() > MAX_INPUTS) {
            complete = false; limitation = "Too many tracked input files.";
        }
        return new InputSet(List.copyOf(found), complete, limitation);
    }

    private static String hash(Path file) throws IOException {
        if (file == null || Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Missing/unsafe evidence file.");
        long bytes = Files.size(file);
        if (bytes > MAX_FILE_BYTES) throw new IOException("Evidence file exceeds bounded size.");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                long total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_FILE_BYTES) throw new IOException("Evidence grew while reading.");
                    md.update(buffer, 0, count);
                }
            }
            if (Files.size(file) != bytes)
                throw new IOException("Evidence file changed while hashing.");
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static boolean outputsExist(Path build) {
        return build != null && Files.isDirectory(build)
                && Files.isRegularFile(build.resolve("zephyr/.config"), LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(build.resolve("zephyr/zephyr.dts"), LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(build.resolve("zephyr/.config"))
                && !Files.isSymbolicLink(build.resolve("zephyr/zephyr.dts"));
    }

    private static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((raw == null ? "" : raw).getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String raw) {
        return new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
    }

    private static Result unknown(State state, String detail) {
        return new Result(state, detail, 0, "", "");
    }
}
