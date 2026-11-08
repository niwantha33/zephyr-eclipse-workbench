// SPDX-License-Identifier: Apache-2.0
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import io.github.niwantha33.zew.ui.YamlSourceLoader;
import io.github.niwantha33.zew.ui.YamlSourceLoader.Source;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Mapping;
import io.github.niwantha33.zew.ui.YamlFlowGraph;

/** Headless Java 21 security, path selection and actual managed Python tests. */
public final class YamlSourceLoaderTests {
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        System.out.println("PASS YAML SOURCE: " + label);
    }

    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static void fails(Checked action, String label) throws Exception {
        try {
            action.run();
            throw new AssertionError("Unexpected success: " + label);
        } catch (java.io.IOException expected) {
            check(!expected.getMessage().contains("SECRET"), label);
        }
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("zew-yaml-selected-");
        Path application = Files.createDirectories(temp.resolve("application"));
        Path zephyr = Files.createDirectories(temp.resolve("workspace/zephyr"));
        Path outside = Files.createDirectories(temp.resolve("unrelated"));
        Path appFile = application.resolve("tests/demo/sample.yaml");
        Files.createDirectories(appFile.getParent());
        byte[] text = "name: Zephyr\nitems:\n  - 4\n  - 8\n".getBytes(StandardCharsets.UTF_8);
        Files.write(appFile, text);
        Path boardFile = zephyr.resolve("boards/ti/myboard/board.yml");
        Files.createDirectories(boardFile.getParent());
        Files.writeString(boardFile, "board:\n  name: test\n", StandardCharsets.UTF_8);
        Path external = outside.resolve("secret.yaml");
        Files.writeString(external, "SECRET", StandardCharsets.UTF_8);

        try {
            Source appSource = YamlSourceLoader.resolve(appFile, application, zephyr);
            check("application".equals(appSource.kind())
                    && "tests/demo/sample.yaml".equals(appSource.relative()),
                    "application root and precise relative path selected");
            // This is the real on-disk location behind Eclipse's linked
            // Build Outputs folder, as shown in the reported screenshot.
            Path generated = application.resolve(".zew-build/first/build_info.yml");
            Files.createDirectories(generated.getParent());
            Files.writeString(generated, "build:\n  board: qemu_x86\n  profile: debug\n",
                    StandardCharsets.UTF_8);
            Source generatedSource = YamlSourceLoader.resolve(generated, application, zephyr);
            check("application".equals(generatedSource.kind())
                    && ".zew-build/first/build_info.yml".equals(generatedSource.relative()),
                    "generated YAML from Build Outputs resolves to its physical managed application source");
            Source boardSource = YamlSourceLoader.resolve(boardFile, application, zephyr);
            check("zephyr".equals(boardSource.kind())
                    && "boards/ti/myboard/board.yml".equals(boardSource.relative()),
                    "explicit checkout root selected");
            check(YamlSourceLoader.hash(appFile).matches("[0-9a-f]{64}"),
                    "source hash computed before observing");
            fails(() -> YamlSourceLoader.resolve(external, application, zephyr),
                    "reject unrelated source outside both selected roots");
            fails(() -> YamlSourceLoader.resolve(application.resolve("nothere.yml"), application, zephyr),
                    "reject missing source");
            fails(() -> YamlSourceLoader.resolve(application.resolve("main.c"), application, zephyr),
                    "reject unsupported extension");

            Path sym = application.resolve("link.yaml");
            try {
                Files.createSymbolicLink(sym, external);
                fails(() -> YamlSourceLoader.resolve(sym, application, zephyr),
                        "reject symlink escaping managed root");
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException ignored) {
                // Some Windows CI runners do not grant symlink privileges.
            }

            byte[] huge = new byte[256 * 1024 + 1];
            Arrays.fill(huge, (byte) 'x');
            Path bigFile = application.resolve("huge.yaml");
            Files.write(bigFile, huge);
            fails(() -> YamlSourceLoader.hash(bigFile), "reject oversized source");

            String python = System.getenv("ZEW_TEST_PYTHON");
            check(python != null && !python.isBlank(),
                    "managed Python path is explicitly configured in core qualifier");
            Path script = Path.of("plugins/io.github.niwantha33.zew.ui/scripts/yaml_observe_file.py")
                    .toAbsolutePath();
            var generatedSnapshot = YamlSourceLoader.inspect(Path.of(python), script,
                    generatedSource, () -> false);
            check(generatedSnapshot.parsed()
                    && generatedSnapshot.sha256().equals(YamlSourceLoader.hash(generated))
                    && !YamlFlowGraph.from(generatedSnapshot).edges().isEmpty(),
                    "actual generated build_info.yml is parsed into connected syntax blocks");
            var snapshot = YamlSourceLoader.inspect(Path.of(python), script, appSource, () -> false);
            check(snapshot.parsed() && "twister-test".equals(snapshot.kind()),
                    "bundled Python produces a correctly typed Twister observation");
            check(snapshot.root() instanceof Mapping
                    && snapshot.sha256().equals(YamlSourceLoader.hash(appFile)),
                    "Java validates Python provenance against exact source bytes");
            check(Arrays.equals(Files.readAllBytes(appFile), text),
                    "inspection never modifies application YAML");
            try {
                YamlSourceLoader.inspect(Path.of(python), script, appSource, () -> true);
                throw new AssertionError("Cancellation should interrupt YAML inspection");
            } catch (InterruptedException expected) {
                check(true, "cancellation stops source inspection");
            }
            Files.writeString(appFile, "name: changed\n", StandardCharsets.UTF_8);
            check(!YamlSourceLoader.hash(appFile).equals(snapshot.sha256()),
                    "stale source detection distinguishes changed bytes");
        } finally {
            // Test-only files, never an application source.
            try (var paths = Files.walk(temp)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (java.io.IOException ignored) {}
                });
            }
        }
    }
}
