// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/** Opt-in, pinned vendor SVD download. No mutable refs or unpinned source fallback. */
public final class GitHubSvdProvisioner {
    private GitHubSvdProvisioner() {}
    private static final int MAX_SVD_SIZE = 32 * 1024 * 1024;
    public record Downloaded(Path file, String sha256, String source) {}

    private static SvdDownloadCatalog.Entry pinned(String part) {
        var entry = SvdDownloadCatalog.forPart(part);
        if (entry == null || !entry.pinned() || !"svd".equals(entry.type())
                || !"raw.githubusercontent.com".equals(entry.url().getHost())
                || !entry.url().getPath().matches("/espressif/svd/[a-f0-9]{40}/svd/[a-z0-9]+\\.svd"))
            return null;
        return entry;
    }
    public static boolean trusted(byte[] bytes, String part) throws Exception {
        var entry = pinned(part);
        if (entry == null || bytes.length == 0 || bytes.length > MAX_SVD_SIZE) return false;
        return MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes),
                HexFormat.of().parseHex(entry.sha256()));
    }
    public static String pinnedSha256(String part) {
        var entry = pinned(part);
        return entry == null ? null : entry.sha256();
    }
    public static boolean supports(String board) {
        var entry = SvdDownloadCatalog.forBoard(board);
        return entry != null && pinned(entry.part()) != null
                && (!SvdPartCompatibility.requiresQualifier(entry.part())
                    || SvdPartCompatibility.knownQualifier(entry.part(), entry.qualifier()));
    }
    public static Downloaded download(String board, Path root) throws Exception {
        if (!supports(board)) throw new IOException("No pinned exact vendor SVD for selected board.");
        var entry = SvdDownloadCatalog.forBoard(board);
        String part = entry.part();
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("Refusing symlink SVD directory");
        Path destination = root.resolve(entry.filename());
        if (Files.isRegularFile(destination) && !Files.isSymbolicLink(destination)
                && Files.size(destination) <= MAX_SVD_SIZE) {
            byte[] cached = Files.readAllBytes(destination);
            if (trusted(cached, part) && exact(cached, part))
                return new Downloaded(destination, entry.sha256(), entry.url().toString());
        }
        Path temp = Files.createTempFile(root, "verified-svd-", ".tmp");
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            var request = HttpRequest.newBuilder(entry.url()).timeout(Duration.ofSeconds(90))
                    .GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("Pinned vendor SVD endpoint returned HTTP " + response.statusCode());
            }
            byte[] bytes;
            try (InputStream input = response.body()) {
                bytes = input.readNBytes(MAX_SVD_SIZE + 1);
                if (bytes.length > MAX_SVD_SIZE) throw new IOException("Vendor SVD size exceeded");
            }
            if (!trusted(bytes, part) || !exact(bytes, part))
                throw new IOException("SVD digest or exact device identity did not match bundled pin");
            Files.write(temp, bytes);
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            return new Downloaded(destination, entry.sha256(), entry.url().toString());
        } finally { Files.deleteIfExists(temp); }
    }
    public static boolean exact(byte[] bytes, String part) {
        try {
            if (bytes.length == 0 || bytes.length > MAX_SVD_SIZE) return false;
            SvdDevice device = SvdDevice.read(new java.io.ByteArrayInputStream(bytes));
            return SvdPartCompatibility.matches(part, device.name()) && !device.registers().isEmpty();
        } catch (Exception ignored) { return false; }
    }
}
