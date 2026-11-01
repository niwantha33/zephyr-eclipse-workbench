// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/** Opt-in official Espressif SVD download. Never guesses registers or another chip. */
public final class GitHubSvdProvisioner {
    private GitHubSvdProvisioner() {}
    private static final int MAX_SVD_SIZE = 32 * 1024 * 1024;
    // Pin a known Espressif release rather than mutable main.
    private static final String REF = "be20aa1";
    private static final Map<String, String> FILES = Map.ofEntries(
            Map.entry("esp32", "esp32.svd"),
            Map.entry("esp32s2", "esp32s2.svd"),
            Map.entry("esp32s3", "esp32s3.svd"),
            Map.entry("esp32c2", "esp32c2.svd"),
            Map.entry("esp32c3", "esp32c3.svd"),
            Map.entry("esp32c6", "esp32c6.svd"),
            Map.entry("esp32h2", "esp32h2.svd"),
            Map.entry("esp32p4", "esp32p4.svd"));
    public record Downloaded(Path file, String sha256, String source) {}

    public static boolean supports(String board) {
        return FILES.containsKey(SvdAutoDiscovery.partFromBoard(board));
    }
    public static Downloaded download(String board, Path root) throws Exception {
        String part = SvdAutoDiscovery.partFromBoard(board);
        String name = FILES.get(part);
        if (name == null) throw new IOException("No exact official Espressif SVD for " + part);
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("SVD root is a symbolic link");
        Path destination = root.resolve(name);
        String source = "https://raw.githubusercontent.com/espressif/svd/" + REF + "/svd/" + name;
        if (Files.isRegularFile(destination) && !Files.isSymbolicLink(destination)
                && Files.size(destination) <= MAX_SVD_SIZE
                && exact(Files.readAllBytes(destination), part)) {
            return new Downloaded(destination, hash(Files.readAllBytes(destination)), source);
        }
        Path temp = Files.createTempFile(root, "esp-svd-", ".tmp");
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(source))
                    .timeout(Duration.ofSeconds(90)).GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("Official GitHub raw SVD returned HTTP " + response.statusCode());
            }
            byte[] content;
            try (InputStream in = response.body()) {
                content = in.readNBytes(MAX_SVD_SIZE + 1);
                if (content.length > MAX_SVD_SIZE) throw new IOException("SVD exceeds size limit");
            }
            if (!exact(content, part))
                throw new IOException("Downloaded SVD does not identify the exact SoC " + part);
            Files.write(temp, content);
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            return new Downloaded(destination, hash(content), source);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
    public static boolean exact(byte[] bytes, String part) {
        try {
            if (bytes.length == 0 || bytes.length > MAX_SVD_SIZE) return false;
            SvdDevice device = SvdDevice.read(new java.io.ByteArrayInputStream(bytes));
            return device.name().replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT)
                    .equals(part.toLowerCase(Locale.ROOT)) && !device.registers().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
