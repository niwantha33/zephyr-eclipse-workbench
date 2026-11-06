// SPDX-License-Identifier: Apache-2.0
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import io.github.niwantha33.zew.ui.YamlDocumentModel;
import io.github.niwantha33.zew.ui.YamlScalarEdit;
import io.github.niwantha33.zew.ui.YamlPatchValidator;
import io.github.niwantha33.zew.ui.YamlSourceLoader;
import io.github.niwantha33.zew.ui.YamlTreeProjection;

/**
 * End-to-end managed-Python candidate verification plus real guarded, atomic
 * application write on disposable files. No Eclipse/JFace dependencies.
 */
public final class YamlScalarEditTests {
    private static void check(boolean yes, String label) {
        if (!yes) throw new AssertionError(label);
        System.out.println("PASS YAML EDIT: " + label);
    }

    @FunctionalInterface interface Attempt { void run() throws Exception; }
    private static void denied(Attempt action, String label) throws Exception {
        try {
            action.run();
            throw new AssertionError("Disallowed YAML edit succeeded: " + label);
        } catch (IOException rejected) {
            check(!rejected.getMessage().contains("PRIVATE"), label);
        }
    }

    private static YamlTreeProjection.Row named(YamlTreeProjection.Row root, String name) {
        for (var child : root.children()) {
            if (child.label().equals(name)) return child;
            try { return named(child, name); }
            catch (IllegalArgumentException missing) { /* examine next branch */ }
        }
        throw new IllegalArgumentException("Missing fixture row: " + name);
    }

    public static void main(String[] args) throws Exception {
        String python = System.getenv("ZEW_TEST_PYTHON");
        check(python != null && !python.isBlank(), "managed Python explicitly supplied");
        Path inspect = Path.of("plugins/io.github.niwantha33.zew.ui/scripts/yaml_observe_file.py")
                .toAbsolutePath();
        Path checker = Path.of("plugins/io.github.niwantha33.zew.ui/scripts/yaml_validate_patch.py")
                .toAbsolutePath();
        Path root = Files.createTempDirectory("zew-yaml-edit-fixture-");
        Path app = Files.createDirectory(root.resolve("app"));
        Path zephyr = Files.createDirectory(root.resolve("zephyr"));
        Path selected = Files.createDirectories(app.resolve("config")).resolve("settings.yaml");
        String original = "header: yes\r\n"
                + "settings:\r\n"
                + "  greeting: \"Olá 🌍\" # keep this comment\r\n"
                + "  enabled: true\r\n"
                + "  timeout: 12\r\n"
                + "tail: done\r\n";
        byte[] initial = original.getBytes(StandardCharsets.UTF_8);
        Files.write(selected, initial);
        try {
            var source = YamlSourceLoader.resolve(selected, app, zephyr);
            var snapshot = YamlSourceLoader.inspect(Path.of(python), inspect,
                    source, () -> false);
            var rows = YamlTreeProjection.rows(snapshot);
            check(rows.size() == 1 && snapshot.parsed(),
                    "verified exact YAML tree available before editing");
            var greeting = named(rows.get(0), "greeting");
            check(YamlScalarEdit.target(snapshot, greeting) != null,
                    "mapping-key UI selection resolves its source-token scalar");
            var p = YamlScalarEdit.prepare(source, snapshot, greeting, "new: 🌍 # path \\ share");
            check(p.diff().contains("- \"Olá 🌍\"")
                    && p.diff().contains("+ \"new: 🌍 # path \\\\ share\""),
                    "preview shows complete old/new scalar spellings");
            check(Arrays.equals(Files.readAllBytes(selected), initial)
                    && Arrays.equals(p.original(), initial),
                    "preview performs zero source writes");
            var verified = YamlPatchValidator.validate(Path.of(python), checker, p, () -> false);
            check(verified.parsed() && p.candidateSha().equals(verified.sha256()),
                    "managed Python reparses exact candidate bytes and verifies its hash");
            check(YamlScalarEdit.target(verified, greeting) != null
                    && "str".equals(YamlScalarEdit.target(verified, greeting).scalar().scalarType()),
                    "candidate retains original string scalar type");
            Path backup = YamlScalarEdit.apply(p);
            byte[] edited = Files.readAllBytes(selected);
            check(Arrays.equals(Files.readAllBytes(backup), initial),
                    "original bytes are retained in a separate backup");
            String updated = new String(edited, StandardCharsets.UTF_8);
            check(updated.contains("  greeting: \"new: 🌍 # path \\\\ share\" # keep this comment\r\n")
                    && updated.startsWith("header: yes\r\n")
                    && updated.endsWith("tail: done\r\n"),
                    "atomic source patch preserves Unicode, CRLF, comments and untouched text");
            denied(() -> YamlScalarEdit.apply(p),
                    "identical preview cannot overwrite already-modified source");

            snapshot = YamlSourceLoader.inspect(Path.of(python), inspect, source, () -> false);
            var enabled = named(YamlTreeProjection.rows(snapshot).get(0), "enabled");
            denied(() -> YamlScalarEdit.prepare(source, snapshot, enabled, "PRIVATE"),
                    "boolean fields cannot be converted into strings without an explicit type change");
            var booleanEdit = YamlScalarEdit.prepare(source, snapshot, enabled, "false");
            YamlPatchValidator.validate(Path.of(python), checker, booleanEdit, () -> false);
            check(booleanEdit.diff().contains("+ false"), "typed boolean edit previews clearly");
            var timeout = named(YamlTreeProjection.rows(snapshot).get(0), "timeout");
            denied(() -> YamlScalarEdit.prepare(source, snapshot, timeout, "twelve"),
                    "integer values require canonical numeric spelling");
            var integerEdit = YamlScalarEdit.prepare(source, snapshot, timeout, "42");
            YamlPatchValidator.validate(Path.of(python), checker, integerEdit, () -> false);
            check(integerEdit.diff().contains("+ 42"), "integer scalar changes stay type-safe");

            Files.writeString(selected, "settings: [changed externally]\r\n", StandardCharsets.UTF_8);
            denied(() -> YamlScalarEdit.apply(booleanEdit),
                    "stale source or another writer cannot be overwritten after a preview");
            check(Files.readString(selected).contains("changed externally"),
                    "conflicting file bytes are kept without applying the patch");

            Path generated = Files.createDirectories(app.resolve(".zew-build"))
                    .resolve("generated.yaml");
            Files.writeString(generated, "value: safe\n", StandardCharsets.UTF_8);
            var generatedSource = YamlSourceLoader.resolve(generated, app, zephyr);
            denied(() -> YamlScalarEdit.requireEditable(generatedSource),
                    "build artefact paths always remain read-only");
            Path board = Files.createDirectories(zephyr.resolve("boards/vendor/demo"))
                    .resolve("board.yml");
            Files.writeString(board, "board: {name: example}\n", StandardCharsets.UTF_8);
            var checkout = YamlSourceLoader.resolve(board, app, zephyr);
            denied(() -> YamlScalarEdit.requireEditable(checkout),
                    "checked-out Zephyr board files always remain read-only");

            try {
                YamlPatchValidator.validate(Path.of(python), checker, integerEdit, () -> true);
                throw new AssertionError("Cancelled patch validator succeeded");
            } catch (InterruptedException expected) {
                check(true, "cancelling the candidate validation stops the child process");
            }
            check(YamlScalarEdit.target(snapshot, rows.get(0)) == null,
                    "mapping rows cannot silently become write targets");
        } finally {
            try (var files = Files.walk(root)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (IOException ignored) {}
                });
            }
        }
    }
}
