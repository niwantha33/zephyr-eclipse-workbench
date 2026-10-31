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
import java.util.List;
import java.util.zip.ZipFile;

/**
 * Explicit opt-in acquisition of an official vendor CMSIS Device Family Pack.
 * The downloaded pack never supplies register data without SvdAutoDiscovery's
 * exact-part PDSC mapping and SVD parser validation.
 */
public final class VendorSvdProvisioner {
    private VendorSvdProvisioner() {}

    private static final long MAX_BYTES = 120L * 1024 * 1024;
    private static final String TI_MSPM0_PACK =
            "TexasInstruments.MSPM0G1X0X_G3X0X_DFP.1.3.1.pack";
    private static final URI TI_MSPM0_URI =
            URI.create("https://www.keil.com/pack/" + TI_MSPM0_PACK);

    public record Provisioned(Path pack, String sha256, SvdAutoDiscovery.Result device) {}

    public static boolean supports(String board) {
        return "mspm0g3507".equals(SvdAutoDiscovery.partFromBoard(board));
    }

    public static Provisioned download(String board, Path root) throws Exception {
        if (!supports(board))
            throw new IOException("No verified automatic vendor-pack URL for this exact SoC.");
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root))
            throw new IOException("Refusing a symbolic-link vendor-pack directory.");
        Path destination = root.resolve(TI_MSPM0_PACK);
        // Validate an already downloaded pack too; no trust based on filenames.
        if (Files.isRegularFile(destination) && !Files.isSymbolicLink(destination)) {
            var existing = SvdAutoDiscovery.resolve(board, List.of(root), root.resolve("cache"));
            if (existing.state() == SvdAutoDiscovery.State.MATCHED)
                return new Provisioned(destination, sha256(destination), existing);
        }
        Path temp = Files.createTempFile(root, "vendor-pack-", ".tmp");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(15)).build();
            URI uri = TI_MSPM0_URI;
            boolean complete = false;
            for (int hops = 0; hops < 4; hops++) {
                if (!"https".equalsIgnoreCase(uri.getScheme()))
                    throw new IOException("Vendor download must use HTTPS.");
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(90))
                        .header("User-Agent", "Zephyr-Eclipse-Workbench/0.1")
                        .GET().build();
                HttpResponse<InputStream> response =
                        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    try (InputStream ignored = response.body()) {
                        String location = response.headers().firstValue("location")
                                .orElseThrow(() -> new IOException("Vendor redirect without Location."));
                        URI next = uri.resolve(location);
                        if (!"https".equalsIgnoreCase(next.getScheme()))
                            throw new IOException("Insecure redirect from vendor pack host.");
                        // Only the official Keil download endpoints are permitted.
                        String host = next.getHost().toLowerCase(java.util.Locale.ROOT);
                        if (!host.equals("www.keil.com") && !host.equals("keil.com")
                                && !host.equals("www.keil.arm.com") && !host.equals("keil.arm.com"))
                            throw new IOException("Unexpected vendor redirect host: " + host);
                        uri = next;
                    }
                    continue;
                }
                if (status != 200) {
                    response.body().close();
                    throw new IOException("Official CMSIS pack request returned HTTP " + status);
                }
                try (InputStream in = response.body();
                     var out = Files.newOutputStream(temp)) {
                    byte[] buffer = new byte[64 * 1024];
                    long count = 0;
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        count += n;
                        if (count > MAX_BYTES) throw new IOException("Vendor pack exceeds size limit.");
                        out.write(buffer, 0, n);
                    }
                    if (count < 1024) throw new IOException("Incomplete vendor-pack response.");
                }
                complete = true;
                break;
            }
            if (!complete) throw new IOException("Too many vendor-pack redirects.");
            // Never accept a login/HTML response, invalid ZIP, or archive without
            // a device-specific PDSC -> SVD mapping. No register reads occur here.
            try (ZipFile zip = new ZipFile(temp.toFile())) {
                if (zip.size() == 0) throw new IOException("Empty CMSIS pack.");
            }
            Path staging = root.resolve(TI_MSPM0_PACK);
            // Probe in an isolated directory before making the pack available.
            Path probeDir = Files.createTempDirectory(root, "svd-probe-");
            try {
                Path probePack = probeDir.resolve(TI_MSPM0_PACK);
                Files.copy(temp, probePack);
                var result = SvdAutoDiscovery.resolve(board, List.of(probeDir), probeDir.resolve("cache"));
                if (result.state() != SvdAutoDiscovery.State.MATCHED || !result.mappedByPack())
                    throw new IOException("Vendor pack lacks an unambiguous exact MSPM0G3507 SVD mapping: "
                            + result.explanation());
            } finally {
                try (var paths = Files.walk(probeDir)) {
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); }
                        catch (IOException ignored) { /* Temporary cleanup. */ }
                    });
                }
            }
            Files.move(temp, staging, StandardCopyOption.REPLACE_EXISTING);
            var result = SvdAutoDiscovery.resolve(board, List.of(root), root.resolve("cache"));
            if (result.state() != SvdAutoDiscovery.State.MATCHED)
                throw new IOException("Downloaded pack could not be registered: " + result.explanation());
            return new Provisioned(staging, sha256(staging), result);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = input.read(buffer)) >= 0) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
