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
    private static final String REF = "be20aa12560889d6125d144cdb48cf615ac17628";
    private static final Map<String, String> FILES = Map.ofEntries(
            Map.entry("esp32", "esp32.svd"),
            Map.entry("esp32s2", "esp32s2.svd"),
            Map.entry("esp32s3", "esp32s3.svd"),
            Map.entry("esp32c2", "esp32c2.svd"),
            Map.entry("esp32c3", "esp32c3.svd"),
            Map.entry("esp32c6", "esp32c6.svd"),
            Map.entry("esp32h2", "esp32h2.svd"),
            Map.entry("esp32p4", "esp32p4.svd"));
    // Reviewed SHA-256 values of exact upstream Git blob bytes at REF.
    // Source: https://github.com/espressif/svd/tree/be20aa12560889d6125d144cdb48cf615ac17628/svd
    // A matching XML <name> alone is NOT trusted provenance.
    private static final Map<String, String> SHA256 = Map.ofEntries(
            Map.entry("esp32", "f80fb973c19896eb904c00efcead9242f0027deb0a297b7b427d6982172dfcac"),
            Map.entry("esp32s2", "082b2ee12c2bf955ee330eb51a72b525962ea90d2174d0fa01d02d1cdd83a546"),
            Map.entry("esp32s3", "d7629249eba2ad660a960530d124966e9274c8228341380dd4102f251e879e17"),
            Map.entry("esp32c2", "966777659ac55db6ce5aff5b27198228ece570128fb3e5a8f4e0c56707f7c623"),
            Map.entry("esp32c3", "9ef33dbff56d807996fd8edfa4aa4ad008b5a861c241d36280caa0dedbbca41a"),
            Map.entry("esp32c6", "519b374c472483b170d6e5b479e48c07675c18af5bf2613c61eff888dc0fc8cd"),
            Map.entry("esp32h2", "78b63a839a7968e178a61495a024857cb93f12c8b80c74fd70470c3e8671080a"),
            Map.entry("esp32p4", "ef13fce373f783e17f64440c5ef945167c8fb40ea6ae4cc751d027993f5092da"));

    public static boolean trusted(byte[] bytes, String part) throws Exception {
        String expected = SHA256.get(part);
        if (expected == null || bytes.length == 0 || bytes.length > MAX_SVD_SIZE) return false;
        return MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes),
                HexFormat.of().parseHex(expected));
    }

    public static String pinnedSha256(String part) {
        return SHA256.get(part);
    }

    public record Downloaded(Path file, String sha256, String source) {}

    public static boolean supports(String board) {
        String part = SvdAutoDiscovery.partFromBoard(board);
        return FILES.containsKey(part) && SHA256.containsKey(part);
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
                && Files.size(destination) <= MAX_SVD_SIZE) {
            byte[] cached = Files.readAllBytes(destination);
            if (trusted(cached, part) && exact(cached, part))
                return new Downloaded(destination, hash(cached), source);
            // Forged or stale cache is never accepted, even if the XML part name matches.
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
            if (!trusted(content, part))
                throw new IOException("Downloaded official SVD has an unexpected SHA-256 for " + part);
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
