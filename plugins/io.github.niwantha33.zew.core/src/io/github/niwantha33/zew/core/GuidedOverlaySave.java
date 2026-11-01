// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Explicit, hash-pinned guided source save after real *scratch-only* CMake
 * acceptance. No source is written without a separate UI review/confirmation.
 *
 * Reuses the existing atomic, backed-up DevicetreeOverlayChanges writer;
 * refuses to reinterpret raw arbitrary DTS as a verified guided request.
 */
public final class GuidedOverlaySave {
    private GuidedOverlaySave() {}
    private static final String ZERO = "0".repeat(64);
    public record Request(String node, String property, String originalDtsSha,
            String originalOverlaySha, String additionSha, String proposedSha,
            String addition) {}
    public record Review(DevicetreeOverlayChanges.Preview preview, String originalDtsSha,
            String originalOverlaySha, String proposedSha) {}
    public record Saved(Path file, Path backup, String sourceSha) {}

    public static Review review(Path application, Path build, Path selectedOverlay,
            Request request, DevicetreeDraftConfigureInspector.Result preflight)
            throws IOException {
        if (application == null || build == null || selectedOverlay == null
                || request == null || preflight == null || !preflight.configured()
                || !ZERO.equals(preflight.scratchSha()) && !validSha(preflight.scratchSha())
                || !validSha(request.originalDtsSha())
                || !validSha(request.originalOverlaySha()) || !validSha(request.additionSha())
                || !validSha(request.proposedSha())
                || request.node() == null || request.property() == null
                || !request.node().matches("/(?:[A-Za-z0-9,._+@-]+(?:/[A-Za-z0-9,._+@-]+)*)?")
                || !request.property().matches("[A-Za-z][A-Za-z0-9,._+?-]{0,99}")
                || request.addition() == null || request.addition().length() > 8192
                || !request.addition().startsWith(
                    "/* Review only — this does not validate pin/driver hardware. */\n&{"
                    + request.node() + "} {\n    " + request.property() + " = ")
                || !request.addition().endsWith(";\n};\n")
                || !hash(request.addition().getBytes(StandardCharsets.UTF_8))
                        .equals(request.additionSha()))
            throw new IOException("A current, exact, binding-guided and CMake-verified draft is required.");
        if (!"exact-value-observed".equals(preflight.valueState())
                || !"staged".equals(preflight.staging())
                || !"identity-verified".equals(preflight.identity())
                || !preflight.dtsSha().equals(request.originalDtsSha())
                || !preflight.originalOverlaySha().equals(request.originalOverlaySha())
                || !preflight.proposedOverlaySha().equals(request.proposedSha())
                || !preflight.preparedOverlaySha().equals(request.proposedSha())
                || !preflight.nodeSha().equals(hash(request.node().getBytes(StandardCharsets.UTF_8)))
                || !preflight.propertySha().equals(
                        hash(request.property().getBytes(StandardCharsets.UTF_8))))
            throw new IOException("Scratch CMake evidence belongs to another review or did not confirm its value.");

        Path generated = build.resolve("zephyr/zephyr.dts");
        if (!Files.isRegularFile(generated, LinkOption.NOFOLLOW_LINKS)
                || !hash(Files.readAllBytes(generated)).equals(request.originalDtsSha()))
            throw new IOException("Selected build DTS changed after review. Rebuild/review first.");
        // Preserve the caller's original Windows path spelling for the
        // existing checkedTarget() owner-path check. That writer separately
        // canonicalises both paths and rejects symlinks/path escapes.
        Path resolved = selectedOverlay.toAbsolutePath().normalize();
        if (!resolved.toString().endsWith(".overlay"))
            throw new IOException("Selected source must be an application .overlay.");
        // Existing writer canonicalises parent and rejects any symlinks,
        // escaped paths and unsafe candidates before doing any source edits.
        var preview = DevicetreeOverlayChanges.previewAddition(
                application, resolved, request.addition());
        byte[] current = preview.original().getBytes(StandardCharsets.UTF_8);
        String original = preview.existed() ? hash(current) : ZERO;
        if (!original.equals(request.originalOverlaySha())
                || !hash(preview.updated().getBytes(StandardCharsets.UTF_8))
                        .equals(request.proposedSha())
                || !preview.changed())
            throw new IOException("Overlay changed or proposal differs from reviewed scratch source. Review again.");
        return new Review(preview, request.originalDtsSha(),
                request.originalOverlaySha(), request.proposedSha());
    }

    /** Must be called only after a SECOND explicit user confirmation. */
    public static Saved commit(Path application, Path build, Review review)
            throws IOException {
        if (review == null || !validSha(review.originalDtsSha())
                || !validSha(review.originalOverlaySha()) || !validSha(review.proposedSha()))
            throw new IOException("Missing reviewed source identity.");
        Path generated = build.resolve("zephyr/zephyr.dts");
        if (!Files.isRegularFile(generated, LinkOption.NOFOLLOW_LINKS)
                || !hash(Files.readAllBytes(generated)).equals(review.originalDtsSha()))
            throw new IOException("Generated build changed before source save. No overlay written.");
        // This atomic editor checks exact original bytes and rejects races,
        // backing up existing overlay before replacement.
        Path backup = DevicetreeOverlayChanges.commit(application, review.preview());
        Path actual = review.preview().file();
        if (!Files.isRegularFile(actual, LinkOption.NOFOLLOW_LINKS)
                || !hash(Files.readAllBytes(actual)).equals(review.proposedSha()))
            throw new IOException("Saved overlay verification failed; do not build until inspected.");
        return new Saved(actual, backup, review.proposedSha());
    }

    private static boolean validSha(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }
    private static String hash(byte[] bytes) throws IOException {
        return DevicetreeDraftConfigureInspector.digest(bytes);
    }
}
