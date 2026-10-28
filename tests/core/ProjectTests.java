// SPDX-License-Identifier: Apache-2.0
import java.nio.file.*;
import io.github.niwantha33.zew.core.ProjectProfile;
import io.github.niwantha33.zew.core.BuildConfiguration;
import io.github.niwantha33.zew.core.WestCommands;
import io.github.niwantha33.zew.core.WorkbenchConfig;
public final class ProjectTests {
    static int checks;
    static void check(boolean value) { if (!value) throw new AssertionError(); checks++; }
    static void rejects(RunnableThrows operation) throws Exception {
        try { operation.run(); } catch (java.io.IOException | IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Operation should have rejected input");
    }
    interface RunnableThrows { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("zew-project-tests");
        try {
            Path app = root.resolve("app with spaces");
            ProjectProfile.createApplication(app, "esp32s3_devkitc/esp32s3/procpu");
            check(ProjectProfile.read(app).board().equals("esp32s3_devkitc/esp32s3/procpu"));
            check(Files.readString(app.resolve("CMakeLists.txt")).contains("target_sources(app PRIVATE src/main.c)"));
            check(!Files.readString(app.resolve(".zew/project.json")).contains(root.toString()));
            String original = Files.readString(app.resolve("src/main.c"));
            rejects(() -> ProjectProfile.createApplication(app, "qemu_x86/atom"));
            check(Files.readString(app.resolve("src/main.c")).equals(original));
            rejects(() -> new ProjectProfile("../bad")); rejects(() -> new ProjectProfile("board;evil"));
            rejects(() -> new ProjectProfile("board//core")); rejects(() -> new ProjectProfile("board/"));
            rejects(() -> new ProjectProfile("board\ncommand"));
            rejects(() -> new ProjectProfile("qemu_x86/atom").write(app));
            Path metadata = app.resolve(".zew/project.json");
            Files.writeString(metadata, "{\"schema\":2,\"board\":\"qemu_x86\",\"profile\":\"first\"}");
            rejects(() -> ProjectProfile.read(app));
            Files.writeString(metadata, "x".repeat(5000)); rejects(() -> ProjectProfile.read(app));
            Path imported = root.resolve("existing"); Files.createDirectories(imported);
            Files.writeString(imported.resolve("prj.conf"), "CONFIG_TEST=y\n");
            new ProjectProfile("nrf52840dk@1.0.0/nrf52840").write(imported);
            check(Files.readString(imported.resolve("prj.conf")).equals("CONFIG_TEST=y\n"));
            check(ProjectProfile.read(imported).board().equals("nrf52840dk@1.0.0/nrf52840"));
            new ProjectProfile("esp32s3_devkitc/esp32s3/procpu").replace(imported, "nrf52840dk@1.0.0/nrf52840");
            check(ProjectProfile.read(imported).board().equals("esp32s3_devkitc/esp32s3/procpu"));
            check(Files.readString(imported.resolve("prj.conf")).equals("CONFIG_TEST=y\n"));
            rejects(() -> new ProjectProfile("qemu_x86/atom").replace(imported, "old/stale"));
            check(ProjectProfile.read(imported).board().equals("esp32s3_devkitc/esp32s3/procpu"));
            var config = new WorkbenchConfig(root.resolve("python"), root.resolve("workspace"), imported, "lp_mspm0g3507/mspm0g3507");
            Path originalBuild = WestCommands.buildDirectory(config);
            check(WestCommands.buildDirectory(config, BuildConfiguration.DEFAULT).equals(originalBuild));
            check(!WestCommands.buildDirectory(config, BuildConfiguration.DEBUG).equals(originalBuild));
            check(!WestCommands.buildDirectory(config, BuildConfiguration.RELEASE).equals(
                    WestCommands.buildDirectory(config, BuildConfiguration.DEBUG)));
            check(BuildConfiguration.fromId(null) == BuildConfiguration.DEFAULT);
            rejects(() -> BuildConfiguration.fromId("../debug"));
            check(BuildConfiguration.DEBUG.cmakeArguments().contains("-DCONFIG_DEBUG_OPTIMIZATIONS=y"));
            Path build = root.resolve("verify-config"); Files.createDirectories(build.resolve("zephyr"));
            Files.writeString(build.resolve("zephyr/.config"), "CONFIG_DEBUG_OPTIMIZATIONS=y\n");
            BuildConfiguration.DEBUG.verify(build);
            rejects(() -> BuildConfiguration.RELEASE.verify(build));
            Files.writeString(build.resolve("zephyr/.config"), "CONFIG_SPEED_OPTIMIZATIONS=y\n");
            BuildConfiguration.RELEASE.verify(build);
            System.out.println("Passed " + checks + " portable project checks.");
        } finally { try (var paths = Files.walk(root)) { for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); } }
    }
}
