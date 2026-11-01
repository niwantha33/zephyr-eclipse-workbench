// SPDX-License-Identifier: Apache-2.0

import io.github.niwantha33.zew.core.DevicetreeDraftConfigureInspector;
import io.github.niwantha33.zew.core.GuidedOverlaySave;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Real-filesystem negative tests of source-only, backup/atomic reviewed save. */
public final class GuidedOverlaySaveTests {
    private static final String ZERO = "0".repeat(64);
    private static int passed;
    private static void ok(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        passed++;
    }
    private static void rejects(Checked action) throws Exception {
        boolean failed = false;
        try { action.run(); } catch (java.io.IOException expected) { failed = true; }
        ok(failed, "Unsafe or stale review must not edit source");
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static String digest(String value) throws Exception {
        return DevicetreeDraftConfigureInspector.digest(value.getBytes(StandardCharsets.UTF_8));
    }
    private static void exercise(boolean existing) throws Exception {
        Path root = Files.createTempDirectory("zew-guide-commit-Ω ");
        try {
            Path app = root.resolve("Trusted App");
            Files.createDirectories(app);
            Path build = app.resolve(".zew-build/board A");
            Files.createDirectories(build.resolve("zephyr"));
            Path generated = build.resolve("zephyr/zephyr.dts");
            Files.writeString(generated, "/dts-v1/;\n/ { soc { gpio@1234 {}; }; };\n");
            String originalDtsSha = DevicetreeDraftConfigureInspector.digest(Files.readAllBytes(generated));
            Path overlay = app.resolve("app.overlay");
            String original = "/* owner source, retain exactly */\n";
            if (existing) Files.writeString(overlay, original);
            else original = "";
            String node = "/soc/gpio@1234";
            String property = "status";
            String block = "/* Review only — this does not validate pin/driver hardware. */\n"
                    + "&{/soc/gpio@1234} {\n    status = \"disabled\";\n};\n";
            String expectedSource = original + block;
            String oldSha = existing ? digest(original) : ZERO;
            String expectedSha = digest(expectedSource);
            var evidence = new DevicetreeDraftConfigureInspector.Result(
                    "configured", "real-zephyr-cmake-accepted", originalDtsSha,
                    "f".repeat(64), oldSha, expectedSha, expectedSha,
                    digest(node), digest(property), "exact-value-observed",
                    "staged", "identity-verified", 4);
            var request = new GuidedOverlaySave.Request(node, property,
                    originalDtsSha, oldSha, digest(block), expectedSha, block);
            var review = GuidedOverlaySave.review(app, build, overlay, request, evidence);
            ok(!Files.exists(overlay) == !existing, "Source unchanged before confirmation");
            ok(review.preview().updated().equals(expectedSource), "Exact reviewed source addition");
            var badEvidence = new DevicetreeDraftConfigureInspector.Result(
                    "configured", "real-zephyr-cmake-accepted", originalDtsSha,
                    "f".repeat(64), oldSha, expectedSha, "e".repeat(64),
                    digest(node), digest(property), "exact-value-observed",
                    "staged", "identity-verified", 4);
            rejects(() -> GuidedOverlaySave.review(app, build, overlay, request, badEvidence));
            var wrongValue = new DevicetreeDraftConfigureInspector.Result(
                    "failed", "requested-node-or-property-not-observed", originalDtsSha,
                    "f".repeat(64), oldSha, expectedSha, expectedSha,
                    digest(node), digest(property), "unavailable-or-mismatched",
                    "staged", "identity-unknown", 4);
            rejects(() -> GuidedOverlaySave.review(app, build, overlay, request, wrongValue));
            var tamper = new GuidedOverlaySave.Request(node, property,
                    originalDtsSha, oldSha, digest("other source"), expectedSha, block);
            rejects(() -> GuidedOverlaySave.review(app, build, overlay, tamper, evidence));
            Files.writeString(generated, "/* selected build drift */");
            rejects(() -> GuidedOverlaySave.commit(app, build, review));
            Files.writeString(generated, "/dts-v1/;\n/ { soc { gpio@1234 {}; }; };\n");
            if (existing) {
                Files.writeString(overlay, original + "/* concurrent change */");
                rejects(() -> GuidedOverlaySave.commit(app, build, review));
                Files.writeString(overlay, original);
            } else {
                Files.writeString(overlay, "/* raced creation */");
                rejects(() -> GuidedOverlaySave.commit(app, build, review));
                Files.delete(overlay);
            }
            var saved = GuidedOverlaySave.commit(app, build, review);
            ok(Files.readString(overlay).equals(expectedSource), "Source committed atomically");
            ok(saved.sourceSha().equals(expectedSha), "Post-save source fingerprint match");
            if (existing) {
                ok(saved.backup() != null && Files.readString(saved.backup()).equals(original),
                        "Original source backed up before replacement");
                ok(saved.backup().toRealPath().startsWith(app.toRealPath().resolve(".zew/overlay-backups")),
                        "Backup scoped inside application");
            } else {
                ok(saved.backup() == null, "New source has no fabricated prior backup");
            }
            rejects(() -> GuidedOverlaySave.commit(app, build, review));
            rejects(() -> GuidedOverlaySave.review(app, build, overlay, request, evidence));
            ok(Files.readString(overlay).equals(expectedSource),
                    "Rejected duplicate or stale requests never rewrite source");
            Path escaping = root.resolve("other.overlay");
            rejects(() -> GuidedOverlaySave.review(app, build, escaping, request, evidence));
            Path badLink = app.resolve("symlink.overlay");
            try {
                Files.createSymbolicLink(badLink, escaping);
                rejects(() -> GuidedOverlaySave.review(app, build, badLink, request, evidence));
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                // Windows CI may not allow symlink creation.
            }
        } finally {
            try (var walk = Files.walk(root)) {
                for (Path file : walk.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(file);
            }
        }
    }
    public static void main(String[] args) throws Exception {
        exercise(true);
        exercise(false);
        System.out.println("PASS guided reviewed overlay save: " + passed
                + " real filesystem safety and evidence assertions");
    }
}
