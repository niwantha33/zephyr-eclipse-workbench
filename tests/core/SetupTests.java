// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.ProjectToolPath;
import io.github.niwantha33.zew.core.SetupFiles;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SetupTests {
    private static int passed;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        System.out.println("PASS setup: " + name); passed++;
    }
    private static void rejects(Throwing action, String name) throws Exception {
        try { action.run(); throw new AssertionError(name); } catch (java.io.IOException | IllegalArgumentException expected) { check(true, name); }
    }
    @FunctionalInterface interface Throwing { void run() throws Exception; }
    private static void zip(Path path, String name) throws Exception {
        try (var output = new ZipOutputStream(Files.newOutputStream(path))) { output.putNextEntry(new ZipEntry(name)); output.write("fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.closeEntry(); }
    }

    private static void checkProjectToolPath(Path root) {
        Path managed = root.resolve("Zephyr setup");
        Path scripts = managed.resolve("python/Scripts");
        Path git = managed.resolve("git/cmd");
        Path sdk = managed.resolve("sdk");
        Path dtc = root.resolve("Selected DTC/bin");
        Path windows = root.resolve("Windows");
        Path globalTools = root.resolve("Unselected global tools");
        var settings = new Properties();
        settings.setProperty("zew.setupRoot", managed.toString());
        settings.setProperty("python", managed.resolve("python/python.exe").toString());
        settings.setProperty("tools.cmake", scripts.resolve("cmake.exe").toString());
        settings.setProperty("tools.ninja", scripts.resolve("ninja.exe").toString());
        settings.setProperty("tools.git", git.resolve("git.exe").toString());
        settings.setProperty("tools.sdk", sdk.toString());
        settings.setProperty("tools.dtc", dtc.resolve("dtc.exe").toString());
        var host = Map.of("Path", globalTools.toString(), "SYSTEMROOT", windows.toString());
        var expected = List.of(scripts, git, managed.resolve("python"), dtc, sdk.resolve("hosttools/bin"),
                sdk.resolve("hosttools/usr/bin"), managed.resolve("board-python/esp32s3/Scripts"),
                managed.resolve("board-python/esp32s3/bin"), windows.resolve("System32"), windows);
        String separator = Pattern.quote(File.pathSeparator);
        var isolated = List.of(ProjectToolPath.resolve(settings, host, true).split(separator, -1));
        check(isolated.equals(expected.stream().map(Path::toString).toList()),
                "managed build path uses selected tools and Windows system directories even with an inherited global PATH");
        var existing = List.of(ProjectToolPath.resolve(settings, host, false).split(separator, -1));
        check(existing.getLast().equals(globalTools.toString()),
                "existing-tool search keeps inherited PATH after the selected tools");
        var noSystemRoot = List.of(ProjectToolPath.resolve(settings, Map.of("Path", globalTools.toString()), true)
                .split(separator, -1));
        check(noSystemRoot.equals(isolated.subList(0, isolated.size() - 2)),
                "missing Windows system root never restores inherited PATH or inserts a current-directory entry");
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("setup Ω ");
        try {
            checkProjectToolPath(root);
            var data = root.resolve("file"); Files.writeString(data, "good");
            String hash = "770e607624d689265ca6c44884d0807d9b054d23c473c106c72be9de08b7376c";
            check(SetupFiles.matches(data, 4, hash), "matching bootstrap bytes accepted");
            Files.writeString(data, "evil"); check(!SetupFiles.matches(data, 4, hash), "tampered cached bytes rejected");
            check(!SetupFiles.matches(data, 5, hash), "truncated cache rejected");
            rejects(() -> SetupFiles.download(java.net.URI.create("http://invalid.example/a"), data, 4, hash, () -> false, line -> {}), "insecure download rejected before network");
            Path archive = root.resolve("a.zip"); zip(archive, "folder/file.txt");
            Path extracted = root.resolve("extract"); SetupFiles.unzip(archive, extracted, () -> false);
            check(Files.readString(extracted.resolve("folder/file.txt")).equals("fixture"), "real bootstrap ZIP extracted");
            rejects(() -> SetupFiles.unzip(archive, root.resolve("cancelled"), () -> true), "bootstrap extraction cancellation");
            for (String name : new String[] {"../outside", "/outside", "C:/outside", "folder\\outside"}) {
                zip(archive, name); rejects(() -> SetupFiles.unzip(archive, root.resolve("bad"), () -> false), "unsafe ZIP path rejected: " + name);
            }
            if (!System.getProperty("os.name").startsWith("Windows")) {
                Files.createDirectories(root.resolve("links")); Files.createSymbolicLink(root.resolve("links/link"), extracted);
                rejects(() -> SetupFiles.safePath(root.resolve("links"), "link/file"), "archive cannot follow existing symlink");
            }
            System.out.println("Passed " + passed + " bootstrap checks.");
        } finally { try (var paths = Files.walk(root)) { for (var p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); } }
    }
}
