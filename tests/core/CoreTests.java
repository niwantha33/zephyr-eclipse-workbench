// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.*;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Dependency-free integration harness for the pure Java core, including real child processes. */
public final class CoreTests {
    private static int passed;
    private static final Path ROOT = Path.of(System.getProperty("user.dir"));
    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
    private static final String CLASSES = Path.of(System.getProperty("java.class.path")).toAbsolutePath().toString();
    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        passed++;
        System.out.println("PASS " + name);
    }
    private interface Throwing { void run() throws Exception; }
    private static void rejects(Class<? extends Exception> type, Throwing action, String name) throws Exception {
        try { action.run(); } catch (Exception error) { check(type.isInstance(error), name + " (" + error.getClass().getSimpleName() + ")"); return; }
        throw new AssertionError("Expected " + type.getSimpleName() + ": " + name);
    }
    private static List<String> fixture(String... args) {
        var command = new ArrayList<>(List.of(JAVA, "-cp", CLASSES, "ProcessFixture"));
        command.addAll(List.of(args)); return command;
    }
    public static void main(String[] args) throws Exception {
        check(WestCommands.cmakePath("C:\\Sandbox\\Zephyr Setup Ω\\python.exe").equals("C:/Sandbox/Zephyr Setup Ω/python.exe"), "Windows CMake paths avoid macro escapes");
        Path temp = Files.createTempDirectory("zew test space-");
        try {
            Path workspace = Files.createDirectories(temp.resolve("workspace with spaces"));
            Files.createDirectories(workspace.resolve(".west"));
            Path app = Files.createDirectories(temp.resolve("app Ω"));
            Files.writeString(app.resolve("CMakeLists.txt"), "# fixture\n");
            var config = new WorkbenchConfig(Path.of(JAVA), workspace, app, "esp32s3_devkitc/esp32s3/procpu");
            config.validateApplication(); check(true, "paths containing spaces and Unicode");
            var command = WestCommands.build(config, false);
            check(command.subList(1, 4).equals(List.of("-m", "west", "build")), "explicit Python module invocation");
            check(command.contains(app.toString()), "application remains one argument");
            check(command.contains(config.board()), "qualified board remains one argument");
            check(!command.contains("--pristine"), "incremental build is default");
            check(WestCommands.build(config, true).containsAll(List.of("--pristine", "always")), "explicit pristine rebuild");
            check(WestCommands.buildDirectory(config).startsWith(app.resolve(".zew-build")), "build output confined to owned directory");
            var other = new WorkbenchConfig(Path.of(JAVA), workspace, app, "nrf52840dk/nrf52840");
            check(!WestCommands.buildDirectory(config).equals(WestCommands.buildDirectory(other)), "board outputs isolated");
            rejects(IllegalArgumentException.class, () -> new WorkbenchConfig(Path.of(JAVA), workspace, app, "--help"), "option injection rejected");
            rejects(IllegalArgumentException.class, () -> new WorkbenchConfig(Path.of(JAVA), workspace, app, "board/../../x"), "traversal board rejected");
            rejects(IllegalArgumentException.class, () -> new WorkbenchConfig(Path.of(JAVA), temp, app, "board").validateEnvironment(), "missing workspace rejected");
            rejects(IllegalArgumentException.class, () -> new WorkbenchConfig(Path.of(JAVA), workspace, temp, "board").validateApplication(), "non-application rejected");
            check(WestCommands.parseBoards("z_board\na_board\nz_board\nWARNING: ignore\n").equals(List.of("a_board", "z_board")), "board list sorted and deduplicated");
            check(WestCommands.parseZephyrPath(app + "\n").equals(app), "nonstandard Zephyr path parsed");
            rejects(IllegalArgumentException.class, () -> WestCommands.parseZephyrPath("a\nb\n"), "ambiguous path rejected");
            var runner = new ProcessRunner();
            var environment = runner.run(fixture("environment", "ZEW_TOOL_TEST", "PATH", "PYTHONIOENCODING"), ROOT, Duration.ofSeconds(10),
                    () -> false, line -> {}, java.util.Map.of("ZEW_TOOL_TEST", "selected-sdk", "PATH", "explicit-tools-path"));
            check(environment.requireSuccess().contains("selected-sdk\nexplicit-tools-path\nutf-8"), "explicit tool environment and PATH precedence preserved");
            check(System.getenv("ZEW_TOOL_TEST") == null, "selected environment does not mutate parent process");
            var echo = runner.run(fixture("echo", "hello world", "$(touch NEVER)", "Ω"), ROOT, Duration.ofSeconds(10), () -> false, line -> {});
            check(echo.requireSuccess().contains("$(touch NEVER)"), "literal arguments are not shell evaluated");
            var unicode = runner.run(fixture("unicode"), ROOT, Duration.ofSeconds(10), () -> false, line -> {});
            check(unicode.requireSuccess().contains("Ω"), "UTF-8 process output: " + unicode.output());
            String python = System.getenv("ZEW_TEST_PYTHON");
            if (python != null) {
                var pythonUnicode = runner.run(List.of(python, "-I", "-X", "utf8", "-c",
                    "import sys;print(sys.argv[1])", "Ω"), ROOT, Duration.ofSeconds(10), () -> false, line -> {});
                check(pythonUnicode.requireSuccess().strip().equals("Ω"), "Unicode tool arguments survive native process launch: " + pythonUnicode.output());
            }
            var failure = runner.run(fixture("fail"), ROOT, Duration.ofSeconds(10), () -> false, line -> {});
            check(failure.exitCode() == 7, "nonzero exit status preserved");
            rejects(IOException.class, failure::requireSuccess, "failed tools cannot report success");
            try { failure.requireSuccess(); } catch (IOException error) {
                check(error.getMessage().contains("intentional error"), "original tool diagnostics survive failed discovery/compile stages");
            }
            var flood = runner.run(fixture("flood"), ROOT, Duration.ofSeconds(15), () -> false, line -> {});
            check(flood.exitCode() == 0 && flood.output().contains("line-9999"), "large merged output does not deadlock");
            var capture = runner.run(fixture("capture"), ROOT, Duration.ofSeconds(15), () -> false, line -> {});
            check(capture.truncated() && capture.output().length() <= 2_000_000, "capture limit bounds memory");
            check(capture.requireSuccess() != null, "large successful build output remains successful");
            var commandsScanner = new BuildHostRequirements.CommandScanner();
            var oversizedCommands = runner.run(fixture("largecommands"), ROOT, Duration.ofSeconds(20),
                    () -> false, commandsScanner);
            oversizedCommands.requireSuccess();
            check(oversizedCommands.truncated(), "large Ninja listing exceeds bounded capture without failing");
            check(commandsScanner.missing().equals(Set.of("ESPTOOL")),
                    "late missing executable is detected after two-megabyte capture limit");
            var badCommandsScanner = new BuildHostRequirements.CommandScanner();
            var badCommands = runner.run(fixture("largecommands", "fail"), ROOT, Duration.ofSeconds(20),
                    () -> false, badCommandsScanner);
            rejects(IOException.class, badCommands::requireSuccess,
                    "failed Ninja command listing remains a hard failure when output exceeds capture limit");
            rejects(IOException.class, capture::requireCompleteOutput, "truncated discovery protocol rejected");
            rejects(IOException.class, () -> runner.run(fixture("sleep"), ROOT, Duration.ofMillis(250), () -> false, line -> {}), "hung tool timeout");
            rejects(InterruptedIOException.class, () -> runner.run(fixture("sleep"), ROOT, Duration.ofSeconds(10), () -> true, line -> {}), "cancel before launch");
            long start = System.nanoTime();
            rejects(InterruptedIOException.class, () -> runner.run(fixture("sleep"), ROOT, Duration.ofSeconds(10), () -> System.nanoTime()-start > 300_000_000L, line -> {}), "cancel running tool");
            rejects(IOException.class, () -> {
                var unexpected = runner.run(fixture("echo", "x"), ROOT, Duration.ofSeconds(10), () -> false, line -> { throw new IllegalStateException("fixture failure"); });
                throw new AssertionError("Output callback was not invoked: exit=" + unexpected.exitCode() + ", output=" + unexpected.output());
            }, "output handler failure surfaced");
            var childPid = new AtomicLong();
            long childStart = System.nanoTime();
            rejects(InterruptedIOException.class, () -> runner.run(fixture("child"), ROOT, Duration.ofSeconds(15),
                    () -> childPid.get() > 0 && System.nanoTime()-childStart > 600_000_000L,
                    line -> { if (line.startsWith("CHILD=")) childPid.set(Long.parseLong(line.substring(6))); }), "cancel parent with child");
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (ProcessHandle.of(childPid.get()).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(25);
            check(childPid.get() > 0 && !ProcessHandle.of(childPid.get()).map(ProcessHandle::isAlive).orElse(false), "owned descendant terminated");
            System.out.println("Passed " + passed + " core checks.");
        } finally {
            try (var paths = Files.walk(temp)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}

