// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.BuildConfiguration;
import io.github.niwantha33.zew.core.BuildProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Named Zephyr firmware profiles: roundtrip, evidence, isolation and hostile inputs. */
public final class BuildProfileTests {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void invalid(Runnable command, String message) {
        try { command.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejection: " + message);
    }

    public static void main(String[] args) throws Exception {
        Path app = Files.createTempDirectory("zew-profile-test-");
        var p = new BuildProfile("debug-rtt", "RTT Debug",
                BuildConfiguration.DEBUG, "APP_DIAGNOSTICS=1,APP_TRACE_ENABLED",
                "CONFIG_LOG=y\nCONFIG_LOG_DEFAULT_LEVEL=4\n# CONFIG_SHELL is not set\n");
        check(p.fingerprintSha256().matches("[0-9a-f]{64}"), "named profile has SHA-256 metadata fingerprint");
        var reOrdered = new BuildProfile("debug-rtt", "RTT Debug",
                BuildConfiguration.DEBUG, "APP_TRACE_ENABLED,APP_DIAGNOSTICS=1",
                "# CONFIG_SHELL is not set\nCONFIG_LOG_DEFAULT_LEVEL=4\nCONFIG_LOG=y\n");
        check(p.fingerprintSha256().equals(reOrdered.fingerprintSha256()),
                "equivalent key flags have the same canonical fingerprint");
        var renamed = new BuildProfile("debug-rtt", "Renamed RTT Debug",
                BuildConfiguration.DEBUG, p.definitions(), p.kconfig());
        check(!p.fingerprintSha256().equals(renamed.fingerprintSha256()),
                "profile label is covered by the fingerprint");
        p.save(app);
        check(BuildProfile.list(app).size() == 1, "one discoverable profile");
        check(BuildProfile.read(app, "debug-rtt").equals(p), "profile metadata and Kconfig roundtrip");
        check(BuildProfile.read(app, "debug-rtt").cmakeArguments(app).stream()
                .anyMatch(x -> x.startsWith("-DEXTRA_CONF_FILE:STRING=") && x.endsWith("debug-rtt.conf")),
                "effective Kconfig profile supplied via Zephyr EXTRA_CONF_FILE");
        check(p.cmakeArguments(app).stream().anyMatch(x -> x.contains("CONFIG_DEBUG_OPTIMIZATIONS=y")),
                "optimisation mapped through Zephyr's own Kconfig");

        var defaultBuild = app.resolve(".zew-build/a1b2c3");
        check(p.buildDirectory(defaultBuild).equals(app.resolve(".zew-build/a1b2c3-profile-debug-rtt")),
                "named profile cache cannot reuse default build");
        var other = new BuildProfile("release-pre", "Release PRE",
                BuildConfiguration.RELEASE, "APP_SELF_TEST=1", "CONFIG_LOG=y\n");
        other.save(app);
        check(!other.buildDirectory(defaultBuild).equals(p.buildDirectory(defaultBuild)),
                "named profiles independently isolated");
        check(BuildProfile.list(app).size() == 2, "both profile definitions survive");
        // New Zephyr-native policies roundtrip through named profile metadata
        // without changing legacy debug/release identities.
        var noOpt = new BuildProfile("development-no-opt", "Development O0",
                BuildConfiguration.NO_OPTIMIZATION, "", "");
        noOpt.save(app);
        check(BuildProfile.read(app, "development-no-opt").optimization()
                == BuildConfiguration.NO_OPTIMIZATION, "no-optimisation profile roundtrip");
        check(noOpt.cmakeArguments(app).stream()
                .anyMatch(arg -> arg.equals("-DCONFIG_NO_OPTIMIZATIONS=y")),
                "named no-optimisation delegates to Zephyr Kconfig");
        var sizeOpt = new BuildProfile("small-firmware", "Small firmware",
                BuildConfiguration.SIZE, "", "");
        sizeOpt.save(app);
        check(BuildProfile.read(app, "small-firmware").optimization() == BuildConfiguration.SIZE,
                "size-optimisation profile roundtrip");
        var aggressiveSize = new BuildProfile("aggressive-size", "Aggressive size",
                BuildConfiguration.AGGRESSIVE_SIZE, "", "");
        aggressiveSize.save(app);
        check(aggressiveSize.cmakeArguments(app).stream()
                .anyMatch(arg -> arg.equals("-DCONFIG_SIZE_OPTIMIZATIONS_AGGRESSIVE=y")),
                "aggressive size profile delegates to Zephyr Kconfig");
        check(BuildProfile.read(app, "aggressive-size").optimization()
                == BuildConfiguration.AGGRESSIVE_SIZE, "aggressive size roundtrip");

        Path build = p.buildDirectory(defaultBuild);
        Files.createDirectories(build.resolve("zephyr"));
        Files.writeString(build.resolve("zephyr/.config"),
                "CONFIG_DEBUG_OPTIMIZATIONS=y\nCONFIG_LOG=y\nCONFIG_LOG_DEFAULT_LEVEL=4\n");
        Files.writeString(build.resolve("compile_commands.json"),
                "[{\"command\":\"cc -DAPP_DIAGNOSTICS=1 -DAPP_TRACE_ENABLED -c test.c\"}]");
        p.verify(build);
        Files.writeString(build.resolve("zephyr/.config"),
                "CONFIG_DEBUG_OPTIMIZATIONS=y\nCONFIG_LOG=n\nCONFIG_LOG_DEFAULT_LEVEL=4\n");
        try {
            p.verify(build);
            throw new AssertionError("effective configuration mismatch silently accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("CONFIG_LOG"), "reject false effective Kconfig claim");
        }
        Files.writeString(build.resolve("zephyr/.config"),
                "CONFIG_DEBUG_OPTIMIZATIONS=y\nCONFIG_LOG=y\nCONFIG_LOG_DEFAULT_LEVEL=4\n");
        Files.writeString(build.resolve("compile_commands.json"), "[{\"command\":\"cc -c test.c\"}]");
        try {
            p.verify(build);
            throw new AssertionError("missing compiler definition silently accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("APP_DIAGNOSTICS"), "reject false macro claim");
        }

        invalid(() -> new BuildProfile("../outside", "Unsafe", BuildConfiguration.DEBUG, "", ""),
                "profile path traversal");
        invalid(() -> new BuildProfile("config-debug", "Unsafe", BuildConfiguration.DEBUG,
                "CONFIG_LOG=1", ""), "CONFIG_ must be a declared Kconfig symbol");
        invalid(() -> new BuildProfile("unsafe", "Unsafe", BuildConfiguration.DEBUG,
                "HELLO=1;rm -rf /", ""), "macro command injection");
        invalid(() -> new BuildProfile("unsafe", "Unsafe", BuildConfiguration.DEBUG, "",
                "CONFIG_LOG=y\nCONFIG_LOG=n\n"), "duplicate Kconfig requests");
        invalid(() -> new BuildProfile("unsafe", "Unsafe", BuildConfiguration.DEBUG, "",
                "CONFIG_LOG=y\nEXTRA_CONF_FILE=evil\n"), "fragment injection");
        invalid(() -> new BuildProfile("unsafe", "Unsafe", BuildConfiguration.DEBUG, "",
                "CONFIG_LOG=y\nCONFIG_L0G=$(evil)\n"), "unsupported Kconfig expression");
        check(BuildProfile.parseConfig("# CONFIG_LOG is not set\n").get("CONFIG_LOG").equals("n"),
                "unselected symbols use n rather than fabricated y");

        BuildProfile.delete(app, "release-pre");
        check(BuildProfile.list(app).size() == 4,
                "deleting legacy release profile preserves named native modes");
        try { BuildProfile.read(app, "release-pre"); throw new AssertionError("deleted profile read"); }
        catch (IOException expected) { /* fail closed when selected profile is deleted */ }
        System.out.println("PASS BuildProfileTests");
    }
}
