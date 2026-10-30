// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.PhysicalOperationLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;

/** Real OS lock contention, release and crash recovery, without a physical probe. */
public final class PhysicalOperationLockTests {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            try (var lock = PhysicalOperationLock.acquire(Path.of(args[0]))) {
                System.out.println("LOCKED"); System.out.flush();
                Thread.sleep(60_000);
            } catch (IOException busy) { System.exit(7); }
            return;
        }
        Path root = Files.createTempDirectory("zew-hardware-lock-");
        try {
            for (int cycle = 0; cycle < 3; cycle++) {
                var lease = PhysicalOperationLock.acquire(root);
                try {
                    rejects(root);
                    Process competing = child(root);
                    if (!competing.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        competing.destroyForcibly(); throw new AssertionError("Competing process hung");
                    }
                    check(competing.exitValue() == 7, "cross-process contention");
                } finally { lease.close(); lease.close(); }
                try (var next = PhysicalOperationLock.acquire(root)) { check(next != null, "relaunch after release"); }
            }
            Process crashed = child(root);
            try {
                check("LOCKED".equals(new java.io.BufferedReader(new java.io.InputStreamReader(crashed.getInputStream())).readLine()), "child acquired");
                rejects(root);
            } finally { crashed.destroyForcibly(); crashed.waitFor(); }
            try (var recovered = PhysicalOperationLock.acquire(root)) { check(recovered != null, "OS releases lock on crash"); }
            check(Files.exists(root.resolve("physical-operation.lock")), "lock inode remains stable");
            System.out.println("PhysicalOperationLockTests PASS");
        } finally {
            Files.deleteIfExists(root.resolve("physical-operation.lock")); Files.delete(root);
        }
    }
    private static Process child(Path directory) throws Exception {
        String suffix = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return new ProcessBuilder(List.of(Path.of(System.getProperty("java.home"), "bin", suffix).toString(),
                "-cp", System.getProperty("java.class.path"), PhysicalOperationLockTests.class.getName(), directory.toString())).start();
    }
    private static void rejects(Path root) throws Exception {
        try (var unexpected = PhysicalOperationLock.acquire(root)) { throw new AssertionError("Overlapping operation accepted"); }
        catch (IOException expected) { check(expected.getMessage().contains("Another Workbench"), "actionable contention"); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
