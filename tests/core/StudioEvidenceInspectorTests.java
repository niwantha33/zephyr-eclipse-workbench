// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.StudioEvidenceInspector;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Actual subprocess transport of a synthetic CLI; not Zephyr/hardware qualification. */
public final class StudioEvidenceInspectorTests {
    private static final class Resource extends ByteArrayInputStream {
        boolean closed;
        Resource(String text) { super(text.getBytes(StandardCharsets.UTF_8)); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        String selected = System.getenv("ZEW_TEST_PYTHON");
        if (selected == null || selected.isBlank()) throw new AssertionError("ZEW_TEST_PYTHON is required");
        Path python = Path.of(selected);
        Path root = Files.createTempDirectory("zew-native-cli-test-Ω ");
        try {
            Path source = root.resolve("prj.conf"); Files.writeString(source, "CONFIG_ADC=y\n");
            Path ninja = root.resolve("ninja"); Files.writeString(ninja, "fixture, not executed");
            var script = new Resource("import json,sys\nprint(json.dumps({'status':'partial','fixture':'x'*131072}))\nsys.exit(1)\n");
            var init = new Resource("# fixture\n");
            var resources = new LinkedHashMap<String, java.io.InputStream>();
            resources.put("studio.py", script); resources.put("zew_evidence/__init__.py", init);
            var result = StudioEvidenceInspector.kconfig(python, ninja, root, source, resources, Map.of(), () -> false);
            check(result.exitCode() == 1 && result.output().length() > 131072,
                    "Pipe-sized report is fully drained while process runs");
            check(result.equivalentCommand().contains("studio-kconfig")
                    && result.equivalentCommand().contains(source.toString()), "Equivalent argv names selected source");
            check(Files.readString(source).equals("CONFIG_ADC=y\n"), "Source remains unchanged");
            check(script.closed && init.closed, "Packaged resources closed after run");
            script = new Resource("# cancelled fixture\n");
            init = new Resource("# fixture\n");
            resources = new LinkedHashMap<>(); resources.put("studio.py", script); resources.put("zew_evidence/__init__.py", init);
            boolean cancelled = false;
            try { StudioEvidenceInspector.kconfig(python, ninja, root, source, resources, Map.of(), () -> true); }
            catch (java.io.InterruptedIOException expected) { cancelled = true; }
            check(cancelled && script.closed && init.closed, "Cancellation closes all resources without launching");
            System.out.println("PASS native Studio CLI transport: 5 assertions (synthetic subprocess)");
        } finally {
            try (var walk = Files.walk(root)) {
                for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
