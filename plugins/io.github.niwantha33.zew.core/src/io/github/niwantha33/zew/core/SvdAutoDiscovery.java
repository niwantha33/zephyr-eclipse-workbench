// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Discover ONLY an exact SoC's vendor SVD from already installed Zephyr,
 * vendor SDK or CMSIS pack files. Never infer a board from core architecture.
 * No network access or target register reads. Path/ZIP walking is bounded.
 */
public final class SvdAutoDiscovery {
    public static final long MAX_SVD_BYTES = 32L * 1024 * 1024;
    private static final long MAX_PACK_BYTES = 512L * 1024 * 1024;
    private static final int MAX_VISITED = 40000;
    private static final int MAX_PACK_ENTRIES = 50000;
    private SvdAutoDiscovery() {}

    public enum State { MATCHED, MISSING, AMBIGUOUS, INVALID_TARGET, INCOMPLETE_SCAN }
    public record Result(State state, String part, Path path, int registers,
            String explanation, List<String> inspected) {
        public Result {
            inspected = List.copyOf(inspected);
        }
    }
    private record Candidate(Path path, String name, int registers, String fingerprint) {}

    public static String partFromBoard(String board) {
        if (board == null || board.isBlank()) return "";
        // Exact board qualifier, not board vendor, CPU or family approximation.
        String[] segments = board.toLowerCase(Locale.ROOT).trim().split("/");
        String part = segments.length < 2 ? "" : segments[1].trim();
        return part.matches("[a-z0-9_+-]{5,72}") ? part : "";
    }

    private static String normal(String text) {
        return text.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }
    private static String fingerprint(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static byte[] bounded(InputStream input) throws IOException {
        return input.readNBytes((int) MAX_SVD_BYTES + 1);
    }
    private static Candidate validate(byte[] bytes, String part, Path source, Path cache) throws Exception {
        if (bytes.length == 0 || bytes.length > MAX_SVD_BYTES) return null;
        SvdDevice device = SvdDevice.read(new ByteArrayInputStream(bytes));
        // Exact chip name is mandatory. A family-level SVD cannot be silently
        // used as a physical chip-specific register map.
        if (!normal(device.name()).equals(normal(part))) return null;
        String sha = fingerprint(bytes);
        Path chosen = source;
        if (cache != null) {
            Files.createDirectories(cache);
            if (Files.isSymbolicLink(cache)) throw new IOException("SVD cache cannot be a symbolic link");
            chosen = cache.resolve(normal(part) + "-" + sha.substring(0, 16) + ".svd");
            if (!Files.exists(chosen)) {
                Path temp = Files.createTempFile(cache, "svd-verified-", ".tmp");
                try {
                    Files.write(temp, bytes);
                    try { Files.move(temp, chosen, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
                    catch (java.nio.file.AtomicMoveNotSupportedException e) {
                        Files.move(temp, chosen);
                    }
                } finally { Files.deleteIfExists(temp); }
            }
        }
        return new Candidate(chosen, device.name(), device.registers().size(), sha);
    }
    private static Candidate fileCandidate(Path file, String part) {
        try {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)
                    || Files.size(file) > MAX_SVD_BYTES) return null;
            byte[] bytes;
            try (InputStream input = Files.newInputStream(file)) { bytes = bounded(input); }
            return validate(bytes, part, file, null);
        } catch (Exception ignored) {
            return null; // invalid/unreadable SDK copy is never trusted
        }
    }
    private static void packCandidates(Path pack, String part, Path cache, List<Candidate> candidates) {
        try {
            if (Files.isSymbolicLink(pack) || Files.size(pack) > MAX_PACK_BYTES) return;
            try (var zip = new ZipFile(pack.toFile())) {
                var entries = zip.entries();
                int count = 0;
                while (entries.hasMoreElements() && ++count <= MAX_PACK_ENTRIES) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName().replace('\\', '/');
                    if (entry.isDirectory() || !name.toLowerCase(Locale.ROOT).endsWith(".svd")
                            || !normal(Path.of(name).getFileName().toString()).contains(normal(part))
                            || entry.getSize() > MAX_SVD_BYTES) continue;
                    byte[] bytes;
                    try (InputStream input = zip.getInputStream(entry)) { bytes = bounded(input); }
                    Candidate candidate = validate(bytes, part, pack, cache);
                    if (candidate != null) candidates.add(candidate);
                }
            }
        } catch (Exception ignored) {
            // Malformed or incomplete vendor pack is never registered.
        }
    }

    public static Result resolve(String board, List<Path> roots, Path cache) {
        String part = partFromBoard(board);
        if (part.isEmpty() || Set.of("appcpu", "netcore", "cpu0", "atom", "soc").contains(part)) {
            return new Result(State.INVALID_TARGET, part, null, 0,
                    "Board target lacks an exact SoC identifier; no SVD was guessed.", List.of());
        }
        var inspected = new ArrayList<String>();
        var found = new ArrayList<Candidate>();
        boolean[] truncated = {false};
        int[] visited = {0};
        var seenRoots = new HashSet<Path>();
        for (Path raw : roots) {
            if (raw == null || !Files.isDirectory(raw)) continue;
            try {
                Path root = raw.toRealPath();
                if (!seenRoots.add(root)) continue;
                inspected.add(root.toString());
                Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 11,
                    new SimpleFileVisitor<Path>() {
                        @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (++visited[0] > MAX_VISITED) {
                                truncated[0] = true; return FileVisitResult.TERMINATE;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (++visited[0] > MAX_VISITED) {
                                truncated[0] = true; return FileVisitResult.TERMINATE;
                            }
                            if (attrs.isSymbolicLink() || !attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                            if (name.endsWith(".svd") && normal(name).contains(normal(part))) {
                                Candidate candidate = fileCandidate(file, part);
                                if (candidate != null) found.add(candidate);
                            } else if (name.endsWith(".pack"))
                                packCandidates(file, part, cache, found);
                            return FileVisitResult.CONTINUE;
                        }
                        @Override public FileVisitResult visitFileFailed(Path path, IOException failure) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
            } catch (Exception ignored) {
                inspected.add("Inaccessible root: " + raw);
            }
            if (truncated[0]) break;
        }
        Map<String, Candidate> unique = new LinkedHashMap<>();
        for (var candidate : found) unique.putIfAbsent(candidate.fingerprint(), candidate);
        if (unique.size() == 1) {
            var candidate = unique.values().iterator().next();
            return new Result(State.MATCHED, part, candidate.path(), candidate.registers(),
                    "Exact SVD device name matched " + part + "; register values require a halted manual read.",
                    inspected);
        }
        if (unique.size() > 1)
            return new Result(State.AMBIGUOUS, part, null, 0,
                    "Multiple different vendor SVD revisions match " + part
                    + "; choose the correct installed pack manually.", inspected);
        return new Result(truncated[0] ? State.INCOMPLETE_SCAN : State.MISSING,
                part, null, 0,
                truncated[0] ? "Search budget reached; search an explicit vendor SVD directory."
                        : "No exact vendor SVD installed for " + part + ". Install its official CMSIS Device Family Pack "
                        + "or choose an exact device .svd via Load SVD; no register map was invented.",
                inspected);
    }
}
