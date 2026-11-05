// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.BuildFailureLogs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/** Offline real-filesystem tests of exact-build failed-attempt discovery. */
public final class BuildFailureLogsTests {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static Path receipt(Path log) { return log.resolveSibling(log.getFileName() + ".failure.txt"); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("zew failed builds Ω ");
        try {
            Path application = root.resolve("app");
            Path debug = application.resolve(".zew-build/debug");
            Path release = application.resolve(".zew-build/release");
            Files.createDirectories(debug);
            Files.createDirectories(release);
            check(BuildFailureLogs.latestFailure(application, debug).isEmpty(), "Missing log directory has no failure");
            Path first = BuildFailureLogs.create(application, debug);
            Path second = BuildFailureLogs.create(application, debug);
            check(!first.equals(second), "Each attempt gets a unique log file");
            check(Files.isRegularFile(first) && Files.isRegularFile(second), "Attempt creation creates real log files");
            check(!first.startsWith(debug), "Logs live outside pristine build output");
            check(first.startsWith(application.resolve(".zew/local-build-logs")), "Logs stay in application metadata");
            check(BuildFailureLogs.latestFailure(application, debug).isEmpty(), "Success or in-progress log without failure receipt is excluded");
            check(!BuildFailureLogs.directory(application, debug).equals(BuildFailureLogs.directory(application, release)),
                "Debug and release have independent evidence directories");
            BuildFailureLogs.failed(first, "first compiler failure");
            check(BuildFailureLogs.latestFailure(application, debug).orElseThrow().equals(first), "Receipt marks exact attempt as failed");
            check(BuildFailureLogs.report(first).equals("first compiler failure"), "Reopen the recorded final-attempt report");
            check(BuildFailureLogs.latestFailure(application, release).isEmpty(), "Debug failure does not leak into release");
            Path laterSuccess = BuildFailureLogs.create(application, debug);
            Files.writeString(laterSuccess, "Build complete");
            check(BuildFailureLogs.latestFailure(application, debug).orElseThrow().equals(first), "Later success does not hide last failure");
            try {
                BuildFailureLogs.failed(first, "overwritten report");
                throw new AssertionError("Duplicate failure receipt must be rejected");
            } catch (java.nio.file.FileAlreadyExistsException expected) { checks++; }
            check(Files.readString(receipt(first)).equals("first compiler failure"), "Original failure receipt survives duplicate write");

            BuildFailureLogs.failed(second, "second failure");
            Files.setLastModifiedTime(receipt(first), FileTime.fromMillis(2000));
            Files.setLastModifiedTime(receipt(second), FileTime.fromMillis(3000));
            check(BuildFailureLogs.latestFailure(application, debug).orElseThrow().equals(second), "Latest receipt timestamp wins");
            Files.setLastModifiedTime(receipt(first), FileTime.fromMillis(4000));
            Files.setLastModifiedTime(receipt(second), FileTime.fromMillis(4000));
            Path tie = first.toString().compareTo(second.toString()) > 0 ? first : second;
            check(BuildFailureLogs.latestFailure(application, debug).orElseThrow().equals(tie), "Timestamp ties use deterministic lexicographic ordering");
            check(BuildFailureLogs.latestFailure(application, debug).orElseThrow().equals(tie), "Repeated discovery preserves tie result");
            Path releaseLog = BuildFailureLogs.create(application, release);
            BuildFailureLogs.failed(releaseLog, "release failed independently");
            check(BuildFailureLogs.latestFailure(application, release).orElseThrow().equals(releaseLog), "Release discovers its own failed attempt");
            check(BuildFailureLogs.latestFailure(application, debug).orElseThrow().equals(tie), "Release attempt does not replace debug result");

            // Windows users may lack permission to create symlinks; run when the filesystem allows them.
            Path link = first.getParent().resolve("symlink.log");
            boolean symlinks = false;
            try { Files.createSymbolicLink(link, laterSuccess); symlinks = true; }
            catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
                System.out.println("BuildFailureLogsTests: symlink checks skipped (host permission/API unavailable)");
            }
            if (symlinks) {
                BuildFailureLogs.failed(link, "symlink failure must not qualify");
                check(BuildFailureLogs.latestFailure(application, debug).orElseThrow().equals(tie), "Symlink log is excluded even with regular receipt");
                Path receiptLinkLog = BuildFailureLogs.create(application, debug);
                Files.createSymbolicLink(receipt(receiptLinkLog), receipt(first));
                check(BuildFailureLogs.latestFailure(application, debug).orElseThrow().equals(tie), "Symlink failure receipt is excluded");
                try {
                    BuildFailureLogs.failed(receiptLinkLog, "overwrite symlink target");
                    throw new AssertionError("Existing symlink receipt must reject write");
                } catch (java.nio.file.FileAlreadyExistsException expected) { checks++; }
                check(Files.readString(receipt(first)).equals("first compiler failure"), "Symlink target report remains unchanged");
            }
            System.out.println("BuildFailureLogsTests PASS: " + checks + " checks");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
