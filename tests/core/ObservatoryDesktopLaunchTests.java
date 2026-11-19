// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.ui.ObservatoryDesktopLaunch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

/** Pure-JDK handoff contract test; no Eclipse, debugger or spawned process. */
public final class ObservatoryDesktopLaunchTests {
    private static void check(boolean okay, String message) {
        if (!okay) throw new AssertionError(message);
    }
    private static void denied(Map<String, String> env) throws Exception {
        try {
            ObservatoryDesktopLaunch.plan(env);
            throw new AssertionError("Expected denied launch plan");
        } catch (IOException expected) {
            // A missing executable, unsafe bridge directory or malformed file fails closed.
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("observatory bridge space ");
        try {
            denied(Map.of());
            Path executable = root.resolve(
                    System.getProperty("os.name").toLowerCase().contains("win")
                    ? "rtos-observatory-desktop.exe" : "rtos-observatory-desktop");
            Files.writeString(executable, "fake executable; NEVER executed");
            check(executable.toFile().setExecutable(true) || Files.isExecutable(executable),
                    "test fixture must have executable permission");
            var env = Map.of("RTOS_OBSERVATORY_EXE", executable.toString());
            var plain = ObservatoryDesktopLaunch.plan(env);
            check(plain.arguments().equals(java.util.List.of(executable.toString()))
                    && !plain.bridgeStatus(), "no implicit debugger status without explicit directory");
            Path dir = Files.createDirectory(root.resolve("private"));
            env = Map.of("RTOS_OBSERVATORY_EXE", executable.toString(),
                    "RTOS_OBSERVATORY_BRIDGE_DIR", dir.toString());
            check(!ObservatoryDesktopLaunch.plan(env).bridgeStatus(),
                    "missing status remains an offline desktop launch");
            Path status = dir.resolve("rtos-observatory-dsf-status.tsv");
            Files.writeString(status, "RTOS-OBSERVATORY-DSF\t1\tfixture\n");
            var withStatus = ObservatoryDesktopLaunch.plan(env);
            check(withStatus.bridgeStatus()
                    && withStatus.arguments().equals(java.util.List.of(executable.toString(),
                            "--bridge-status", status.toString())),
                    "native process argument remains one literal path, no shell or GDB attachment");
            Files.writeString(status, "x".repeat(4097));
            denied(env);
            Files.delete(status);
            Path unrelated = root.resolve("unrelated");
            Files.writeString(unrelated, "x");
            try {
                Files.createSymbolicLink(status, unrelated);
                denied(env);
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException noSymlinks) {
                // Host filesystem does not allow creating symlinks.
            }
            denied(Map.of("RTOS_OBSERVATORY_EXE", root.resolve("missing").toString()));
            System.out.println("ObservatoryDesktopLaunchTests PASS");
        } finally {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
    }
}
