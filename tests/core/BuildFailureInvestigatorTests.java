// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.BuildFailureInvestigator;

/** Offline log fixtures; these do not prove a firmware or hardware build. */
public final class BuildFailureInvestigatorTests {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static String investigate(String... lines) {
        var investigation = new BuildFailureInvestigator();
        for (String line : lines) investigation.accept(line);
        return investigation.summary();
    }
    private static String jsonReport(java.nio.file.Path log) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        java.io.PrintStream original = System.out;
        try (var capture = new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setOut(capture);
            BuildFailureInvestigator.main(new String[] { "--json", log.toString() });
        } finally { System.setOut(original); }
        return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
    public static void main(String[] args) throws Exception {
        String progress = "[38/153] Building C object zephyr/CMakeFiles/zephyr.dir/soc/ti/mspm0/common/soc.c.obj";
        String unknown = investigate(progress, "ninja: build stopped: subcommand failed.",
                "FATAL ERROR: command exited with status 1: 'C:\\Sandbox\\Zephyr_Setup\\cmake.EXE' --build 'C:\\Sandbox\\app\\.zew-build\\debug'");
        check(unknown.toLowerCase().contains("unknown"), "Wrapper-only log must explicitly report unknown cause");
        check(!unknown.contains("soc.c.obj"), "Last parallel progress object must not become the diagnosed failed target");

        String target = "zephyr/CMakeFiles/zephyr.dir/drivers/adc/adc_mspm0.c.obj";
        String compiler = "C:\\Zephyr Project\\src\\main.c:42:7: error: 'ADC_CHANNEL_0' undeclared (first use in this function)";
        String known = investigate("FAILED: " + target, "arm-zephyr-eabi-gcc.exe -c ../src/main.c", compiler,
                progress, "ninja: build stopped: subcommand failed.");
        check(known.contains(target), "Retain explicit FAILED target, independently of subsequent progress");
        check(known.contains(compiler), "Retain compiler diagnostic with Windows drive, source line and column");
        check(!known.contains("soc.c.obj"), "Unrelated progress must not overwrite the explicit target");

        String ansi = investigate("\u001b[01;31mC:/app/src/main.c:9:2: error: expected ';' before '}' token\u001b[0m");
        check(ansi.contains("main.c:9:2: error: expected"), "ANSI-coloured compiler errors are detected");
        check(!ansi.contains("\u001b"), "Terminal controls do not leak into reports");

        String linkerLine = "C:/sdk/bin/arm-zephyr-eabi-ld.bfd.exe: app/libapp.a(main.c.obj): in function `main': undefined reference to `missing_driver'";
        String linker = investigate("FAILED: zephyr/zephyr_pre0.elf", linkerLine,
                "collect2.exe: error: ld returned 1 exit status", "ninja: build stopped: subcommand failed.");
        check(linker.contains("undefined reference"), "Retain substantive linker diagnostic");
        check(linker.contains("missing_driver"), "Retain missing symbol evidence");

        String cmakeLine = "CMake Error at CMakeLists.txt:12 (find_package):";
        String cmake = investigate(cmakeLine, "  Could not find a package configuration file provided by \"Zephyr\"",
                "-- Configuring incomplete, errors occurred!");
        check(cmake.contains("Could not find a package configuration"), "Retain multiline CMake explanation");
        check(known.contains("arm-zephyr-eabi-gcc.exe -c"), "Retain Ninja's failed command");
        check(cmake.contains(cmakeLine), "Retain CMake source and invocation evidence");

        var flooded = new BuildFailureInvestigator();
        flooded.accept(compiler);
        for (int i = 0; i < 5000; i++) flooded.accept("[" + i + "/5000] Building C object irrelevant-" + i + ".obj");
        flooded.accept("ninja: build stopped: subcommand failed.");
        check(flooded.summary().contains(compiler), "Compiler evidence survives long parallel progress output");
        check(flooded.summary().length() < 20000, "Investigation summary remains bounded");
        check(flooded.summary().equals(flooded.summary()), "Repeated reports are deterministic");
        String ninja = "ninja: error: loading 'build.ninja': The system cannot find the file specified";
        check(investigate(ninja).contains(ninja), "Generic Ninja diagnostic survives without compiler output");
        String header = "C:/app/src/main.c:3:10: fatal error: zephyr/drivers/missing.h: No such file or directory";
        check(investigate(header, "compilation terminated.").contains(header), "Fatal missing-header diagnostic is preserved");

        var diagnosticFlood = new BuildFailureInvestigator();
        for (int i = 0; i < 5000; i++) {
            diagnosticFlood.accept("FAILED: object-" + i + "x".repeat(5000));
            diagnosticFlood.accept("gcc.exe -c " + "x".repeat(5000));
            diagnosticFlood.accept("src/main.c:1:1: error: diagnostic-" + i + "x".repeat(5000));
        }
        check(diagnosticFlood.summary().length() <= 100000, "Diagnostic, command and target floods stay bounded");
        check(diagnosticFlood.summary().contains("Additional diagnostics omitted"), "Report explicitly declares evidence truncation");
        check(diagnosticFlood.summary().contains("diagnostic-0"), "Earliest diagnostic survives flood");

        java.nio.file.Path log = java.nio.file.Files.createTempFile("zew-build-log-", ".txt");
        try {
            byte[] logBytes = (header + "\r\n" + ninja + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.write(log, logBytes);
            String firstJson = jsonReport(log);
            check(firstJson.equals(jsonReport(log)), "JSON CLI output is deterministic for exact artifact bytes");
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(logBytes));
            check(firstJson.contains("\"log_sha256\":\"" + hash + "\""), "JSON provenance hashes exact input bytes including CRLF");
            check(firstJson.contains("\"root_cause\":\"unknown\""), "Reported compiler error does not become a verified underlying cause");
            check(firstJson.contains("fatal error: zephyr/drivers/missing.h"), "JSON report contains diagnostic evidence");
            check(java.util.Arrays.equals(java.nio.file.Files.readAllBytes(log), logBytes), "CLI investigation leaves input artifact unchanged");
        } finally { java.nio.file.Files.deleteIfExists(log); }
        var attempts = new BuildFailureInvestigator();
        attempts.accept("main.c:1: error: previous recovered configuration error");
        attempts.beginAttempt();
        attempts.accept("other.c:3: error: actual final compiler failure");
        check(!attempts.summary().contains("previous recovered"), "Recovered attempt does not obscure final failure");
        check(attempts.summary().contains("log line 2: other.c:3"), "Reset retains full-log line numbering");
        System.out.println("BuildFailureInvestigatorTests PASS: " + checks + " checks");
    }
}
