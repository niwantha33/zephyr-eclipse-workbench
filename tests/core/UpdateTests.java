// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.*;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual HTTP transport and filesystem staging tests; no real credentials or GitHub writes. */
public final class UpdateTests {
    private static int passed;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        passed++; System.out.println("PASS update: " + name);
    }
    private interface Throwing { void run() throws Exception; }
    private static void rejects(Class<? extends Exception> type, Throwing action, String name) throws Exception {
        try { action.run(); } catch (Exception error) { check(type.isInstance(error), name); return; }
        throw new AssertionError("Expected failure: " + name);
    }
    private static String manifest() {
        var text = new StringBuilder("schema=1\nfeature=" + ReleaseManifest.FEATURE
                + "\nversion=0.1.2.test\nrevision=" + "a".repeat(40) + "\ndirectory=releases/0.1.2.test\nfiles=3\n");
        String[] paths = {"p2.index", "content.jar", "artifacts.jar"};
        for (int i=0; i<paths.length; i++) text.append("file.").append(i).append(".path=").append(paths[i])
                .append("\nfile.").append(i).append(".size=3\nfile.").append(i).append(".sha256=")
                .append(UpdateStager.sha256("abc".getBytes(StandardCharsets.UTF_8))).append('\n');
        return text.toString();
    }
    public static void main(String[] args) throws Exception {
        String valid = manifest();
        var release = ReleaseManifest.parse(valid);
        check(release.version().equals("0.1.2.test") && release.artifacts().size()==3, "valid immutable manifest");
        rejects(IOException.class, () -> ReleaseManifest.parse(valid.replace("schema=1", "schema=2")), "unknown schema rejected");
        rejects(IOException.class, () -> ReleaseManifest.parse(valid.replace(ReleaseManifest.FEATURE, "another.feature")), "foreign feature rejected");
        rejects(IOException.class, () -> ReleaseManifest.parse(valid.replace("revision=" + "a".repeat(40), "revision=main")), "floating revision rejected");
        rejects(IOException.class, () -> ReleaseManifest.parse(valid.replace("version=0.1.2.test", "version=../bad")), "invalid version rejected");
        rejects(IOException.class, () -> ReleaseManifest.parse(valid.replace("file.0.path=p2.index", "file.0.path=../p2.index")), "path traversal rejected");
        rejects(IOException.class, () -> ReleaseManifest.parse(valid.replace("file.0.path=p2.index", "file.0.path=/p2.index")), "absolute path rejected");
        rejects(IOException.class, () -> ReleaseManifest.parse(valid.replace("file.0.path=p2.index", "file.0.path=tools/run.exe")), "executable payload rejected");
        rejects(IOException.class, () -> ReleaseManifest.parse(valid.replace("file.1.path=content.jar", "file.1.path=p2.index")), "duplicate file rejected");
        rejects(IOException.class, () -> ReleaseManifest.parse(valid.replace("file.1.size=3", "file.1.size=9999999999")), "oversize payload rejected");
        rejects(IOException.class, () -> ReleaseManifest.parse(valid.replace("files=3", "files=999")), "file count bounded");
        rejects(IOException.class, () -> ReleaseManifest.parse("x".repeat(65537)), "manifest size bounded");
        Path cache = Files.createTempDirectory("zew update space Ω-");
        try {
            var stager = new UpdateStager();
            var progress = new AtomicInteger();
            var ready = stager.stage(release, cache, file -> "abc".getBytes(StandardCharsets.UTF_8), () -> false, text -> progress.incrementAndGet());
            check(Files.readString(ready.resolve("content.jar")).equals("abc") && progress.get()==3, "verified staging with progress");
            rejects(IOException.class, () -> stager.stage(release, cache, file -> "xyz".getBytes(StandardCharsets.UTF_8), () -> false, text -> {}), "tampered bytes rejected");
            rejects(IOException.class, () -> stager.stage(release, cache, file -> new byte[0], () -> false, text -> {}), "truncated file rejected");
            rejects(InterruptedIOException.class, () -> stager.stage(release, cache, file -> new byte[0], () -> true, text -> {}), "staging cancellation");
            try (var files = Files.list(cache)) { check(files.count()==1, "partial staging removed; verified release preserved"); }
        } finally {
            try (var files=Files.walk(cache)) { for (var path:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
        var executor = Executors.newCachedThreadPool(r -> { var t=new Thread(r); t.setDaemon(true); return t; });
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var redirects = new AtomicInteger();
        var auth = new AtomicInteger();
        server.setExecutor(executor);
        server.createContext("/raw", exchange -> {
            if ("Bearer synthetic-test-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) auth.incrementAndGet();
            byte[] bytes="abc".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/big", exchange -> { byte[] bytes=new byte[1024]; exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); });
        server.createContext("/redirect", exchange -> { exchange.getResponseHeaders().add("Location","/target"); exchange.sendResponseHeaders(302,-1); exchange.close(); });
        server.createContext("/target", exchange -> { redirects.incrementAndGet(); exchange.sendResponseHeaders(200,-1); exchange.close(); });
        server.createContext("/private", exchange -> { exchange.sendResponseHeaders(401,-1); exchange.close(); });
        server.createContext("/missing", exchange -> { exchange.sendResponseHeaders(404,-1); exchange.close(); });
        server.createContext("/slow", exchange -> {
            try { Thread.sleep(2000); exchange.sendResponseHeaders(200,-1); } catch (Exception ignored) {} finally { exchange.close(); }
        });
        server.start();
        try {
            var http = new BoundedHttp();
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            var raw=http.get(URI.create(base+"/raw"),"synthetic-test-token",3,Duration.ofSeconds(5),()->false);
            check(Arrays.equals(raw,"abc".getBytes(StandardCharsets.UTF_8)) && auth.get()==1,"raw bytes and private authorization header");
            rejects(IOException.class,()->http.get(URI.create(base+"/raw"),"synthetic-secret\ninvalid",100,Duration.ofSeconds(5),()->false),"malformed credential rejected before header construction");
            rejects(IOException.class,()->http.get(URI.create(base+"/big"),"",10,Duration.ofSeconds(5),()->false),"HTTP body size bounded");
            rejects(IOException.class,()->http.get(URI.create(base+"/redirect"),"synthetic-test-token",100,Duration.ofSeconds(5),()->false),"redirect rejected");
            check(redirects.get()==0,"credentials not forwarded to redirect destination");
            rejects(IOException.class,()->http.get(URI.create(base+"/private"),"",100,Duration.ofSeconds(5),()->false),"authentication failure surfaced");
            rejects(IOException.class,()->http.get(URI.create(base+"/missing"),"",100,Duration.ofSeconds(5),()->false),"unavailable channel surfaced");
            rejects(IOException.class,()->http.get(URI.create(base+"/slow"),"",100,Duration.ofMillis(200),()->false),"HTTP timeout bounded");
            rejects(InterruptedIOException.class,()->http.get(URI.create(base+"/slow"),"",100,Duration.ofSeconds(5),()->true),"HTTP cancellation before start");
            long start=System.nanoTime();
            rejects(InterruptedIOException.class,()->http.get(URI.create(base+"/slow"),"",100,Duration.ofSeconds(5),()->System.nanoTime()-start>200_000_000L),"HTTP cancellation during request");
        } finally { server.stop(0); executor.shutdownNow(); }
        System.out.println("Passed "+passed+" update checks.");
    }
}
