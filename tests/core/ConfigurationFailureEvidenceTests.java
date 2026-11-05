// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.BuildFailureLogs;
import io.github.niwantha33.zew.core.ConfigurationFailureEvidence;
import io.github.niwantha33.zew.core.ConfigurationFailureEvidence.Mode;
import java.nio.file.Files;
import java.nio.file.Path;

/** CS-02: verify useful, read-only findings without a successful Zephyr configure. */
public final class ConfigurationFailureEvidenceTests {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("zew failed configure ");
        Path app = Files.createDirectories(root.resolve("app"));
        Path build = root.resolve("build");
        Files.writeString(app.resolve("prj.conf"),
                "CONFIG_GPIO=y\nCONFIG_NOT_REAL=y\n");
        var sourceOnly = ConfigurationFailureEvidence.inspect(app, build);
        check(sourceOnly.mode() == Mode.SOURCE_ONLY
                && sourceOnly.requestedSymbols() == 2
                && !sourceOnly.recordedFailure(),
                "no successful build: still show exact requested symbols without pretending effective values");
        check(sourceOnly.findings().stream().anyMatch(f ->
                    f.title().equals("Effective Kconfig not generated"))
                && sourceOnly.findings().stream().anyMatch(f ->
                    f.title().equals("Resolved Devicetree not generated"))
                && sourceOnly.findings().stream().noneMatch(f ->
                    f.title().startsWith("Undefined Kconfig symbol")),
                "unresolved CONFIG request is NOT declared nonexistent without actual Zephyr evidence");

        Path log = BuildFailureLogs.create(app, build);
        Files.writeString(log, String.join("\n",
                "Zephyr configure failed:",
                "/some/private/target/prj.conf:2: warning: attempt to assign the value 'y' to the undefined symbol NOT_REAL",
                "FATAL ERROR: command exited with status 1", ""));
        BuildFailureLogs.failed(log, "Failure stage: Configure\nZephyr reported undefined symbol");
        var logged = ConfigurationFailureEvidence.inspect(app, build);
        check(logged.mode() == Mode.SOURCE_ONLY && logged.recordedFailure(),
                "failed configure preserves source-only view and recognizes saved failure");
        check(logged.findings().stream().anyMatch(f ->
                    f.title().contains("CONFIG_NOT_REAL")
                    && f.explanation().contains("prj.conf:2")
                    && !f.explanation().contains("/some/private/target")),
                "actual Kconfig error is observed with safe basename and line, not guessed");
        check(logged.findings().stream().anyMatch(f ->
                    f.title().equals("Recorded failed build attempt")
                    && f.explanation().contains("predate a successful build")),
                "saved failed attempt is clearly historical");

        Path zephyr = Files.createDirectories(build.resolve("zephyr"));
        Files.writeString(zephyr.resolve(".config"), "CONFIG_GPIO=y\n");
        var partial = ConfigurationFailureEvidence.inspect(app, build);
        check(partial.mode() == Mode.PARTIAL_GENERATED
                && partial.findings().stream().anyMatch(f ->
                    f.title().equals("Resolved Devicetree not generated")),
                "partial configured output is not treated as a fully valid build");
        Files.writeString(zephyr.resolve("zephyr.dts"), "/dts-v1/;\n/ { };\n");
        var fullyGenerated = ConfigurationFailureEvidence.inspect(app, build);
        check(fullyGenerated.mode() == Mode.GENERATED && fullyGenerated.recordedFailure()
                && fullyGenerated.findings().stream().anyMatch(f ->
                    f.title().equals("Recorded failed build attempt")),
                "historical failures preserved but not represented as current even after success");

        var otherBuild = root.resolve("another-build");
        var isolated = ConfigurationFailureEvidence.inspect(app, otherBuild);
        check(!isolated.recordedFailure() && isolated.mode() == Mode.SOURCE_ONLY,
                "failed logs are scoped to their exact build directory, not another profile");
        var inaccessible = ConfigurationFailureEvidence.inspect(root.resolve("deleted"), build);
        check(inaccessible.findings().stream().anyMatch(f ->
                    f.title().equals("No selected Zephyr application")),
                "inaccessible project produces safe explicit state");
        System.out.println("PASS failure-aware configuration evidence: " + checks);
    }
}
