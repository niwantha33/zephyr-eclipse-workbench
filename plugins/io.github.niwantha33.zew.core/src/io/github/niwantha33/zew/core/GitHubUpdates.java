// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Fixed-origin private GitHub contents transport. No token appears in a URL or file. */
public final class GitHubUpdates {
    private static final String API = "https://api.github.com/repos/niwantha33/zephyr-eclipse-workbench/contents/";
    private final BoundedHttp http = new BoundedHttp();
    private final String token;
    public GitHubUpdates(String token) { this.token = token; }

    public ReleaseManifest check(BooleanSupplier cancelled) throws IOException, InterruptedException {
        var bytes = fetch("channel.properties", "updates", 65536, cancelled);
        return ReleaseManifest.parse(new String(bytes, StandardCharsets.UTF_8));
    }

    public byte[] artifact(ReleaseManifest manifest, ReleaseManifest.Artifact file, BooleanSupplier cancelled) throws IOException, InterruptedException {
        return fetch(manifest.directory() + "/" + file.path(), manifest.revision(), (int) file.size(), cancelled);
    }

    private byte[] fetch(String path, String revision, int limit, BooleanSupplier cancelled) throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(revision, StandardCharsets.UTF_8);
        return http.get(URI.create(API + path + "?ref=" + encoded), token, limit, Duration.ofSeconds(45), cancelled);
    }
}
