// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.InvestigationBundle;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class InvestigationBundleTests {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("zew-evidence-");
        try {
            Path app = Files.createDirectory(root.resolve("app"));
            Files.writeString(app.resolve("prj.conf"), "CONFIG_GPIO=y\n");
            Files.writeString(app.resolve("credentials.txt"), "excluded");
            var original = InvestigationBundle.collect(app, null);
            check(original.entries().size() == 2, "allowlist excludes unrelated files");
            byte[] mutable = original.entries().get("source/prj.conf"); mutable[0] = 0;
            check(original.entries().get("source/prj.conf")[0] != 0, "immutable preview snapshot");
            Path output = root.resolve("snapshot.zip");
            InvestigationBundle.write(output, original);
            var reopened = InvestigationBundle.read(output);
            check(InvestigationBundle.inspect(reopened).contains("CONFIG_GPIO=y"), "offline round trip");
            expectFailure(() -> InvestigationBundle.write(output, original), "no overwrite");
            check(Files.size(output) > 0, "existing package preserved");
            Files.writeString(app.resolve("prj.conf"), "# CONFIG_GPIO is not set\nCONFIG_ADC=y\n");
            String diff = InvestigationBundle.compare(original, InvestigationBundle.collect(app, null));
            check(diff.contains("CONFIG_GPIO: y → n") && diff.contains("CONFIG_ADC: <absent> → y"), "semantic difference");
            check(Files.readString(app.resolve("prj.conf")).startsWith("# CONFIG_GPIO"), "source remains unchanged");
            Path bad = root.resolve("bad.zip");
            zip(bad, "../escape", "bad");
            expectFailure(() -> InvestigationBundle.read(bad), "reject traversal without extracting");
            check(!Files.exists(root.resolve("escape")), "no extraction");
            zip(bad, "generated/.config", "x".repeat(2_000_001));
            expectFailure(() -> InvestigationBundle.read(bad), "decompression bound");
            // A structurally valid archive with altered bytes must fail manifest validation.
            try (var out = new ZipOutputStream(Files.newOutputStream(bad)); var in = new ZipInputStream(Files.newInputStream(output))) {
                ZipEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    out.putNextEntry(new ZipEntry(entry.getName()));
                    out.write(entry.getName().equals("source/prj.conf") ? "CONFIG_GPIO=n".getBytes() : in.readAllBytes());
                    out.closeEntry();
                }
            }
            expectFailure(() -> InvestigationBundle.read(bad), "tamper detection");
            System.out.println("InvestigationBundleTests PASS");
        } finally {
            try (var walk = Files.walk(root)) { for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
        }
    }
    private static void zip(Path path, String name, String text) throws Exception {
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry(name)); zip.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
        }
    }
    @FunctionalInterface interface Work { void run() throws Exception; }
    private static void expectFailure(Work work, String label) throws Exception {
        try { work.run(); } catch (java.io.IOException expected) { return; }
        throw new AssertionError(label);
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
