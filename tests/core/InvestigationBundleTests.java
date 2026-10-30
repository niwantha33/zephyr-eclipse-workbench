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
            // Shareable snapshots must not silently include secrets,
            // user comments, custom DTS strings or absolute workspace paths.
            Files.writeString(app.resolve("prj.conf"),
                    "# user note: private-marker-123\nCONFIG_GPIO=y\n"
                    + "CONFIG_WIFI_PASSWORD=\"password-marker-456\"\n"
                    + "CONFIG_AUTH_TOKEN=0x12345678\nCONFIG_DEVICE_LABEL=\"identity-marker-789\"\n");
            var redacted = InvestigationBundle.collect(app, null);
            String safeConfig = new String(redacted.entries().get("source/prj.conf"),
                    java.nio.charset.StandardCharsets.UTF_8);
            check(safeConfig.contains("CONFIG_GPIO=y")
                    && !safeConfig.contains("password-marker-456")
                    && !safeConfig.contains("identity-marker-789")
                    && !safeConfig.contains("private-marker-123")
                    && !safeConfig.contains("0x12345678"), "private config strings and notes are removed from export");
            Files.writeString(app.resolve("prj.conf"), "CONFIG_GPIO=y\n");
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
            // Archive identifies the exact build image without exporting the
            // executable, local SDK paths or other unrelated files.
            Path build = Files.createDirectory(root.resolve("build"));
            Path zephyr = Files.createDirectory(build.resolve("zephyr"));
            Files.writeString(zephyr.resolve(".config"),
                    "CONFIG_BOARD_TARGET=\"nucleo_f446re/stm32f446xx\"\n"
                            + "CONFIG_SOC=\"stm32f446xx\"\nCONFIG_ARM=y\n");
            Files.writeString(build.resolve(".zew-built-target"), "nucleo_f446re/stm32f446xx");
            Files.write(zephyr.resolve("zephyr.elf"), new byte[] {0x7f, 0x45, 0x4c, 0x46, 1, 2, 3, 4});
            var withIdentity = InvestigationBundle.collect(app, build);
            String identity = new String(withIdentity.entries().get("build/build-identity.txt"),
                    java.nio.charset.StandardCharsets.UTF_8);
            check(identity.contains("nucleo_f446re/stm32f446xx")
                    && identity.contains("matches generated config")
                    && identity.contains("ELF SHA-256:")
                    && identity.contains("CONFIG_ARM=y"), "build identity and bounded ELF fingerprint exported");
            check(!withIdentity.entries().containsKey("zephyr/zephyr.elf")
                    && !identity.contains(root.toString()), "local paths and ELF content excluded");
            Files.writeString(zephyr.resolve("zephyr.dts"),
                    "/ {\n soc {\n  adc@4000 {\n   compatible = \"vnd,adc\";\n"
                    + "   status = \"okay\";\n   password = \"dts-secret-123\";\n  };\n };\n};\n");
            var withHardware = InvestigationBundle.collect(app, build);
            check(withHardware.entries().containsKey("generated/devicetree-inventory.tsv")
                    && !withHardware.entries().containsKey("generated/zephyr.dts"),
                    "shareable export uses redacted hardware inventory instead of raw DTS");
            String inventory = new String(withHardware.entries().get("generated/devicetree-inventory.tsv"),
                    java.nio.charset.StandardCharsets.UTF_8);
            check(inventory.contains("adc@4000") && !inventory.contains("dts-secret-123"),
                    "Devicetree inventory retains node structure but excludes custom secrets");
            Path withBuild = root.resolve("snapshot-build.zip");
            InvestigationBundle.write(withBuild, withIdentity);
            check(InvestigationBundle.read(withBuild).entries().containsKey("build/build-identity.txt"),
                    "build identity survives offline archive verification");
            Files.writeString(build.resolve(".zew-built-target"), "wrong_board");
            check(new String(InvestigationBundle.collect(app, build).entries().get("build/build-identity.txt"),
                    java.nio.charset.StandardCharsets.UTF_8).contains("MISMATCH"),
                    "mismatched build target never reported verified");
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
