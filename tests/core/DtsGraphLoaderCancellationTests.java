// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.ui.DtsGraphLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/** Pure-JDK canceled-observer regression; no target, network or subprocess launch. */
public final class DtsGraphLoaderCancellationTests {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createTempDirectory("zew-dts-cancel-");
        Path build = Files.createDirectories(scratch.resolve("profile/zephyr"));
        Path dts = build.resolve("zephyr.dts");
        try {
            Files.writeString(dts, "/dts-v1/;\n/ {};\n");
            var result = DtsGraphLoader.load(Path.of("not-launched-python"),
                    Path.of("not-launched-script"), scratch, scratch,
                    scratch.resolve("profile"), () -> true);
            check(!result.observed() && result.explanation().contains("cancelled"),
                    "Canceled selected-build observation must exit before subprocess launch.");
            System.out.println("DtsGraphLoaderCancellationTests PASS");
        } finally {
            Files.deleteIfExists(dts);
            Files.deleteIfExists(build);
            Files.deleteIfExists(scratch.resolve("profile"));
            Files.deleteIfExists(scratch);
        }
    }
}
