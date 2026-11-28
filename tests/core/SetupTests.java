// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.ProjectToolPath;
import io.github.niwantha33.zew.core.ManagedBuildCache;
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

    private static void checkManagedProcessEnvironment() {
        var host = new java.util.LinkedHashMap<>(Map.of(
                "Path", "unselected", "PythonHome", "other-python", "PYTHONPATH", "other-site",
                "GIT_DIR", "other-repository", "GIT_CONFIG_KEY_0", "alias.bad",
                "GIT_CONFIG_VALUE_0", "bad", "CMAKE_TOOLCHAIN_FILE", "foreign-toolchain",
                "CC", "foreign-compiler", "TEMP", "system-temp", "BOARD_ROOT", "other-board"));
        var ordinary = new java.util.LinkedHashMap<>(host);
        io.github.niwantha33.zew.core.ProcessEnvironment.apply(ordinary, Map.of("PATH", "selected"));
        check(ordinary.containsKey("PythonHome") && ordinary.containsKey("CC"),
                "external environments retain their existing host configuration");
        var selected = new io.github.niwantha33.zew.core.ProcessEnvironment(true);
        selected.put("PATH", "selected");
        selected.put("ZEPHYR_BASE", "selected-source");
        selected.put("GIT_CONFIG_GLOBAL", "NUL");
        var child = new java.util.LinkedHashMap<>(host);
        io.github.niwantha33.zew.core.ProcessEnvironment.apply(child, selected);
        check(child.size() == 4 && "selected".equals(child.get("Path"))
                && "selected-source".equals(child.get("ZEPHYR_BASE"))
                && "NUL".equals(child.get("GIT_CONFIG_GLOBAL"))
                && "system-temp".equals(child.get("TEMP")),
                "managed environment removes ambient inputs, retains OS state and applies selected overrides");
        check(host.size() == 10 && "unselected".equals(host.get("Path")), "host map never changes");
    }

    private static void checkManagedGdbEnvironment(Path root) throws Exception {
        Path setup = Files.createDirectory(root.resolve("managed debug"));
        Files.writeString(setup.resolve(".zew-managed"), "fixture");
        Path sdk = Files.createDirectory(setup.resolve("sdk"));
        Path gdb = Files.createFile(Files.createDirectories(sdk.resolve("arm/bin")).resolve("gdb.exe"));
        Path support = Files.createDirectories(sdk.resolve("hosttools/bin"));
        Path windows = Files.createDirectories(root.resolve("Windows system/System32")).getParent();
        Path foreign = Files.createFile(root.resolve("global-gdb.exe"));
        var host = Map.of("Path", root.resolve("global tools").toString(),
                "SYSTEMROOT", windows.toString(), "TEMP", root.toString(),
                "PYTHONHOME", "foreign python", "GDB_DATA_DIRECTORY", "foreign gdb",
                "CMAKE_TOOLCHAIN_FILE", "foreign cmake", "HOST_PRIVATE_VALUE", "fixture");
        var env = new java.util.HashMap<String, String>();
        for (String entry : io.github.niwantha33.zew.core.ManagedGdbEnvironment.create(setup, sdk, gdb, host)) {
            int equals = entry.indexOf('=');
            env.put(entry.substring(0, equals), entry.substring(equals + 1));
        }
        var paths = List.of(env.get("PATH").split(Pattern.quote(File.pathSeparator), -1));
        Path canonicalWindows = windows.toRealPath();
        check(paths.equals(List.of(gdb.toRealPath().getParent(), support.toRealPath(),
                canonicalWindows.resolve("System32"), canonicalWindows).stream().map(Path::toString).toList()),
                "managed GDB PATH contains only selected SDK and system directories");
        check(!env.containsKey("PYTHONHOME") && !env.containsKey("GDB_DATA_DIRECTORY")
                && !env.containsKey("CMAKE_TOOLCHAIN_FILE") && !env.containsKey("HOST_PRIVATE_VALUE")
                && root.toString().equals(env.get("TEMP")) && "1".equals(env.get("PYTHONNOUSERSITE")),
                "GDB environment excludes inherited tool overrides and arbitrary host data");
        check(host.size() == 7 && host.containsKey("Path"), "managed GDB leaves parent environment unchanged");
        rejects(() -> io.github.niwantha33.zew.core.ManagedGdbEnvironment.create(setup, sdk, foreign, host),
                "managed GDB rejects an executable outside its SDK");
        rejects(() -> io.github.niwantha33.zew.core.ManagedGdbEnvironment.create(setup, sdk, gdb, Map.of()),
                "managed GDB rejects missing Windows system root");
        Path anotherSdk = Files.createDirectory(root.resolve("other sdk"));
        Path anotherGdb = Files.createFile(anotherSdk.resolve("gdb.exe"));
        rejects(() -> io.github.niwantha33.zew.core.ManagedGdbEnvironment.create(setup, anotherSdk, anotherGdb, host),
                "managed GDB rejects an SDK outside selected setup");
    }

    private static void checkManagedCache(Path root) throws Exception {
        Path build = Files.createDirectory(root.resolve("configured-build"));
        Path python = Files.createFile(root.resolve("selected-python.exe"));
        Path cmake = Files.createFile(root.resolve("selected-cmake.exe"));
        Path ninja = Files.createFile(root.resolve("selected-ninja.exe"));
        Path global = Files.createFile(root.resolve("global-cmake.exe"));
        var settings = new Properties();
        settings.setProperty("python", python.toString());
        settings.setProperty("tools.cmake", cmake.toString());
        settings.setProperty("tools.ninja", ninja.toString());
        ManagedBuildCache.verify(build, settings);
        Path cache = build.resolve("CMakeCache.txt");
        String known = "CMAKE_MAKE_PROGRAM:FILEPATH=" + ninja + "\n_Python3_EXECUTABLE:INTERNAL=" + python + "\n";
        Files.writeString(cache, known + "CMAKE_COMMAND:INTERNAL=" + cmake + "\n");
        ManagedBuildCache.verify(build, settings);
        check(true, "matching managed executable cache can be reused");
        Files.writeString(cache, known + "CMAKE_COMMAND:INTERNAL=" + global + "\n");
        rejects(() -> ManagedBuildCache.verify(build, settings), "stale CMake from another installation rejected");
        Files.writeString(cache, known + "Python3_EXECUTABLE:FILEPATH=python.exe\n");
        rejects(() -> ManagedBuildCache.verify(build, settings), "unqualified cached Python rejected");
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("setup Ω ");
        try {
            checkProjectToolPath(root);
            checkManagedProcessEnvironment();
            checkManagedGdbEnvironment(root);
            checkManagedCache(root);
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
