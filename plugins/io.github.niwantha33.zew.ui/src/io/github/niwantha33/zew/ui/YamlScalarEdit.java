// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.niwantha33.zew.ui.YamlDocumentModel.Entry;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Mapping;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Node;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Position;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Range;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Scalar;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Sequence;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Snapshot;

/**
 * Step 11: one-scalar patch preview and guarded atomic application write.
 *
 * Never parses or serializes a YAML document in Java: only replaces the exact
 * source token range observed in Step 3, preserving all other source bytes,
 * comments, whitespace and line endings. A separate managed-Python check
 * MUST accept the candidate before an explicit UI confirmation and apply.
 */
public final class YamlScalarEdit {
    private static final long LIMIT = 256L * 1024;
    private static final int MAX_VALUE = 512;
    private static final Pattern INTEGER = Pattern.compile("-?(?:0|[1-9][0-9]{0,17})");
    private static final Pattern FLOAT = Pattern.compile(
            "-?(?:0|[1-9][0-9]{0,17})\\.[0-9]{1,17}(?:[eE][+-]?[0-9]{1,3})?");
    private static final Set<String> BLOCKED = Set.of(
            ".git", ".west", ".zew-build", ".zew", ".cache", "build", "generated", "out",
            "zephyr", "twister-out", "target", ".venv");

    public record Target(Scalar scalar, Range tokenRange) {
        public Target {
            if (scalar == null || tokenRange == null) throw new IllegalArgumentException("No scalar selected.");
        }
    }

    public record Preview(YamlSourceLoader.Source source, String originalSha,
                          byte[] original, byte[] candidate, String oldToken,
                          String newToken, String beforeLine, String afterLine,
                          String scalarType, YamlTreeProjection.Row row) {
        public Preview {
            if (source == null || originalSha == null || original == null || candidate == null
                    || oldToken == null || newToken == null || beforeLine == null
                    || afterLine == null || scalarType == null || row == null)
                throw new IllegalArgumentException("Incomplete YAML patch");
            original = original.clone();
            candidate = candidate.clone();
        }
        @Override public byte[] original() { return original.clone(); }
        @Override public byte[] candidate() { return candidate.clone(); }
        public String candidateSha() { return digest(candidate); }
        public String diff() {
            return "- " + truncate(beforeLine, 320) + "\n+ " + truncate(afterLine, 320);
        }
    }

    private YamlScalarEdit() {}

    /** Resolve exactly one scalar by its displayed mapping-key or item range. */
    public static Target target(Snapshot snapshot, YamlTreeProjection.Row row) {
        if (snapshot == null || !snapshot.parsed() || snapshot.root() == null || row == null)
            return null;
        List<Target> matches = new ArrayList<>();
        find(snapshot.root(), row, matches, 0, "(document)", false);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static void find(Node node, YamlTreeProjection.Row row, List<Target> matches,
                             int depth, String label, boolean mappingKey) {
        if (depth > 32 || matches.size() > 1) return;
        if (node instanceof Scalar scalar && row.label().equals(label)
                && row.sourceRange().equals(mappingKey ? null : scalar.range()))
            matches.add(new Target(scalar, scalar.range()));
        if (node instanceof Mapping mapping) {
            for (Entry entry : mapping.entries()) {
                if (entry.value() instanceof Scalar scalar
                        && row.label().equals(entry.key())
                        && row.sourceRange().equals(entry.keyRange()))
                    matches.add(new Target(scalar, scalar.range()));
                if (!(entry.value() instanceof Scalar))
                    find(entry.value(), row, matches, depth + 1, entry.key(), true);
            }
        } else if (node instanceof Sequence sequence) {
            for (int i = 0; i < sequence.items().size(); i++)
                find(sequence.items().get(i), row, matches, depth + 1, "[" + i + "]", false);
        }
    }

    /** Only explicitly managed application sources, never checkout/build files. */
    public static void requireEditable(YamlSourceLoader.Source source) throws IOException {
        if (source == null || !"application".equals(source.kind()))
            throw new IOException("Only managed application YAML sources can be edited.");
        YamlSourceLoader.Source checked = YamlSourceLoader.resolve(source.file(), source.root(), null);
        if (!checked.equals(source)) throw new IOException("Selected application path changed.");
        for (Path part : Path.of(source.relative())) {
            String name = part.toString().toLowerCase(java.util.Locale.ROOT);
            if (BLOCKED.contains(name) || name.startsWith(".zew-")
                    || name.endsWith(".bak") || name.endsWith(".generated.yml"))
                throw new IOException("Generated, hidden and managed build paths are read-only.");
        }
        if (!Files.isWritable(source.file()))
            throw new IOException("The selected source is not writable.");
    }

    private static String digest(byte[] contents) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String decode(byte[] bytes) throws IOException {
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (decoded.startsWith("\ufeff") || decoded.indexOf('\0') >= 0)
                throw new IOException("BOM and NUL-containing YAML sources are read-only.");
            return decoded;
        } catch (CharacterCodingException error) {
            throw new IOException("Source is not strict UTF-8.", error);
        }
    }

    private static int offset(String text, Position where) throws IOException {
        int start = 0, line = 1;
        while (line < where.line()) {
            int end = text.indexOf('\n', start);
            if (end < 0) throw new IOException("Observed YAML line no longer exists.");
            start = end + 1;
            line++;
        }
        int end = text.indexOf('\n', start);
        if (end < 0) end = text.length();
        if (end > start && text.charAt(end - 1) == '\r') end--;
        String segment = text.substring(start, end);
        int count = segment.codePointCount(0, segment.length());
        if (where.column() < 1 || where.column() > count + 1)
            throw new IOException("Observed YAML column is out of bounds.");
        return start + segment.offsetByCodePoints(0, where.column() - 1);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private static String render(Scalar old, String replacement) throws IOException {
        if (replacement == null || replacement.codePointCount(0, replacement.length()) > MAX_VALUE
                || replacement.chars().anyMatch(c -> c < 0x20 || c == 0x7f
                        || c == 0x2028 || c == 0x2029))
            throw new IOException("Use a single-line printable YAML scalar (maximum 512 characters).");
        for (int i = 0; i < replacement.length(); i++) {
            char ch = replacement.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= replacement.length() || !Character.isLowSurrogate(replacement.charAt(++i)))
                    throw new IOException("Invalid Unicode scalar.");
            } else if (Character.isLowSurrogate(ch)) throw new IOException("Invalid Unicode scalar.");
        }
        return switch (old.scalarType()) {
            case "str" -> "\"" + replacement.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            case "int" -> {
                if (!INTEGER.matcher(replacement).matches()) throw new IOException("Expected signed decimal integer.");
                yield replacement;
            }
            case "bool" -> {
                if (!Set.of("true", "false").contains(replacement))
                    throw new IOException("Boolean values must be true or false.");
                yield replacement;
            }
            case "float" -> {
                if (!FLOAT.matcher(replacement).matches()) throw new IOException("Expected finite decimal float.");
                yield replacement;
            }
            case "null" -> {
                if (!"null".equals(replacement)) throw new IOException("Null values must remain null.");
                yield replacement;
            }
            default -> throw new IOException("This YAML scalar type is read-only until explicitly supported.");
        };
    }

    /** Build a literal source-token patch; no filesystem mutations. */
    public static Preview prepare(YamlSourceLoader.Source source, Snapshot snapshot,
                                  YamlTreeProjection.Row row, String replacement)
            throws IOException {
        requireEditable(source);
        Target target = target(snapshot, row);
        if (target == null) throw new IOException("Select a supported scalar row, not a mapping or sequence.");
        if (snapshot == null || !snapshot.parsed() || !source.kind().equals(snapshot.sourceRoot())
                || !source.relative().equals(snapshot.relativePath())
                || snapshot.sha256() == null)
            throw new IOException("No trusted source-linked YAML observation is available.");
        Range range = target.tokenRange();
        if (range.start().line() != range.end().line())
            throw new IOException("Multiline and block scalars are read-only.");
        String token = render(target.scalar(), replacement);
        byte[] raw = Files.readAllBytes(source.file());
        if (raw.length > LIMIT || !snapshot.sha256().equals(digest(raw)))
            throw new IOException("YAML source changed since inspection. Refresh before editing.");
        String text = decode(raw);
        int start = offset(text, range.start()), end = offset(text, range.end());
        if (start >= end || text.substring(start, end).indexOf('\n') >= 0)
            throw new IOException("Scalar token range is not editable.");
        String old = text.substring(start, end);
        String candidateText = text.substring(0, start) + token + text.substring(end);
        byte[] candidate = candidateText.getBytes(StandardCharsets.UTF_8);
        if (candidate.length > LIMIT)
            throw new IOException("Edited YAML exceeds the 256 KiB source limit.");
        int lineStart = text.lastIndexOf('\n', start - 1) + 1;
        int lineEnd = text.indexOf('\n', start);
        if (lineEnd < 0) lineEnd = text.length();
        String beforeLine = text.substring(lineStart, lineEnd);
        String afterLine = beforeLine.substring(0, start - lineStart) + token
                + beforeLine.substring(end - lineStart);
        return new Preview(source, digest(raw), raw, candidate, old, token,
                beforeLine, afterLine, target.scalar().scalarType(), row);
    }

    /** Verify the in-memory candidate was parsed and retained its scalar type. */
    public static void verifyCandidate(Preview preview, Snapshot candidate) throws IOException {
        if (preview == null || candidate == null || !candidate.parsed()
                || !"application".equals(candidate.sourceRoot())
                || !preview.source().relative().equals(candidate.relativePath())
                || !preview.candidateSha().equals(candidate.sha256())
                || candidate.sizeBytes() == null
                || candidate.sizeBytes() != preview.candidate().length)
            throw new IOException("Candidate failed managed YAML syntax or provenance checks.");
        Target changed = target(candidate, preview.row());
        if (changed == null || !preview.scalarType().equals(changed.scalar().scalarType()))
            throw new IOException("Candidate changed the selected YAML scalar type or structure.");
    }

    /**
     * User-confirmed write: revalidate exact original bytes immediately before
     * atomic same-directory replacement. Keep an independent original backup.
     * An unsupported atomic move FAILS CLOSED (does not silently copy).
     */
    public static Path apply(Preview preview) throws IOException {
        if (preview == null) throw new IOException("No confirmed patch.");
        YamlSourceLoader.Source source = preview.source();
        requireEditable(source);
        byte[] current = Files.readAllBytes(source.file());
        if (!Arrays.equals(current, preview.original()) ||
                !preview.originalSha().equals(digest(current)))
            throw new IOException("YAML source changed since patch preview. No changes applied.");
        Path directory = source.file().getParent();
        Path backup = null, staging = null;
        try {
            backup = Files.createTempFile(directory, "." + source.file().getFileName() + ".zew-backup-", ".bak");
            Files.write(backup, current, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(backup, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            staging = Files.createTempFile(directory, "." + source.file().getFileName() + ".zew-edit-", ".pending");
            Files.write(staging, preview.candidate(), StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.setPosixFilePermissions(staging, Files.getPosixFilePermissions(source.file()));
            } catch (UnsupportedOperationException ignored) {
                // Windows has no POSIX file permissions.
            }
            requireEditable(source);
            if (!preview.originalSha().equals(YamlSourceLoader.hash(source.file())))
                throw new IOException("YAML changed while the patch was being staged.");
            try {
                Files.move(staging, source.file(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Atomic YAML replacement is unsupported on this filesystem.", unsupported);
            }
            return backup;
        } finally {
            if (staging != null) Files.deleteIfExists(staging);
            // An original backup is retained even when the atomic replace fails.
        }
    }
}
