// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Explicitly selected source overlay changes. Does not discover or activate overlays. */
public final class DevicetreeOverlayChanges {
    private static final long MAX_BYTES = 2_000_000;
    private DevicetreeOverlayChanges() {}

    public record Preview(Path file, boolean existed, String original, String updated, String addition) {
        public boolean changed() { return !original.equals(updated); }
    }

    /** Preview an appended override. Existing text is retained exactly; Zephyr resolves override order. */
    public static Preview preview(Path application, Path selectedOverlay,
            DevicetreeParameters.Property property, String value) throws IOException {
        Path file = checkedTarget(application, selectedOverlay);
        boolean exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
        String original = exists ? read(file) : "";
        String newline = original.contains("\r\n") ? "\r\n" : "\n";
        String addition = DevicetreeParameters.overlayRequest(property, value).replace("\n", newline);
        String updated = original.endsWith(addition) ? original
                : original + (original.isEmpty() || original.endsWith("\n") ? "" : newline) + addition;
        if (updated.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IOException("Updated overlay exceeds 2 MB.");
        return new Preview(file, exists, original, updated, addition);
    }

    /** Save only after the caller shows this exact preview and obtains the user's explicit action. */
    public static Path commit(Path application, Preview preview) throws IOException {
        Path file = checkedTarget(application, preview.file());
        if (!file.equals(preview.file())) throw new IOException("Overlay location changed; review again.");
        unchanged(file, preview);
        if (!preview.changed()) return null;
        Path backup = null;
        if (preview.existed()) {
            Path root = application.toRealPath();
            Path backups = root.resolve(".zew/overlay-backups");
            if (Files.isSymbolicLink(root.resolve(".zew")) || Files.isSymbolicLink(backups))
                throw new IOException("Refusing to save through a symlinked backup directory.");
            Files.createDirectories(backups);
            backup = backups.resolve(UUID.randomUUID() + "-" + file.getFileName());
            Files.writeString(backup, preview.original(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
        Path temporary = Files.createTempFile(file.getParent(), ".zew-overlay-", ".tmp");
        try {
            Files.writeString(temporary, preview.updated(), StandardCharsets.UTF_8);
            if (preview.existed() && Files.getFileStore(file).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(file));
            checkedTarget(application, file);
            unchanged(file, preview);
            if (preview.existed()) {
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unavailable) {
                    throw new IOException("Filesystem cannot atomically update this overlay; original preserved.", unavailable);
                }
            } else {
                // Link publishes complete content atomically and cannot overwrite a newly created file.
                try { Files.createLink(file, temporary); }
                catch (UnsupportedOperationException unavailable) {
                    throw new IOException("Filesystem cannot safely create this overlay. Create it in the editor and review again.", unavailable);
                }
            }
            return backup;
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void unchanged(Path file, Preview preview) throws IOException {
        boolean exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
        if (exists != preview.existed() || exists && !read(file).equals(preview.original()))
            throw new IOException("Overlay changed since preview. Reload and review; no source was overwritten.");
    }

    private static Path checkedTarget(Path application, Path overlay) throws IOException {
        if (application == null || overlay == null) throw new IOException("Select an application and its active source overlay.");
        Path root = application.toRealPath();
        Path original = overlay.toAbsolutePath().normalize();
        // Windows temp paths can expose an 8.3 alias (RUNNER~1) while
        // toRealPath() expands the application root to its long spelling.
        // Resolve the overlay's existing parent to the same canonical form
        // before comparing paths; lexical startsWith would reject a safe file.
        if (!original.getFileName().toString().endsWith(".overlay"))
            throw new IOException("Select a source .overlay inside this application; generated Devicetree is read-only.");
        if (!Files.isDirectory(original.getParent(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("The selected overlay's parent directory does not exist.");
        if (Files.exists(original, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(original))
            throw new IOException("Refusing to update a symlinked overlay path.");
        Path canonicalParent = original.getParent().toRealPath();
        Path file = canonicalParent.resolve(original.getFileName()).normalize();
        if (!file.startsWith(root))
            throw new IOException("Select a source .overlay inside this application; generated Devicetree is read-only.");
        // A junction or symlink inside the project must not bypass the
        // explicit, original-path source-ownership check.
        Path sourceRoot = application.toAbsolutePath().normalize();
        // commit() receives the canonical path from Preview; preview() may
        // receive the spelling supplied by the user (including a Windows 8.3
        // alias). Both must be accepted only after the canonical containment
        // check above succeeded.
        if (!original.startsWith(sourceRoot) && !original.startsWith(root))
            throw new IOException("Overlay does not belong to the selected application.");
        for (Path part = original; part != null && !part.equals(sourceRoot); part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new IOException("Refusing to update a symlinked overlay path.");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("The selected overlay is not a regular source file.");
        return file;
    }

    private static String read(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES) throw new IOException("Overlay exceeds 2 MB.");
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
