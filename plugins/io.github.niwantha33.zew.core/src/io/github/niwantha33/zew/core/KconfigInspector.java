// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Read-only bridge to the selected build's own Zephyr Kconfiglib environment.
 *
 * A missing/stale Ninja Kconfig target is never approximated using guessed
 * board dependencies. The UI can fall back to raw Kconfig source help.
 */
public final class KconfigInspector {
    private KconfigInspector() {}

    public record Details(String symbol, String type, String effective, String visibility,
            String assignable, String dependencies, String dependencyState,
            String prompt, String definitions, String selects, String implies, String help) {
        public boolean canRequest(String value) {
            return ("y".equals(value) || "n".equals(value))
                    && Arrays.asList(assignable.split(",")).contains(value);
        }
        public String describe() {
            return "\n\nKconfiglib — selected build (read-only)"
                    + "\nType: " + type
                    + "\nActual value: " + effective
                    + "\nVisibility: " + visibility
                    + "\nUser-assignable values: " + (assignable.isBlank() ? "(none)" : assignable)
                    + "\nDirect dependency: " + dependencies
                    + "\nDependency expression result: " + dependencyState
                    + "\nPrompt: " + prompt
                    + (selects.isBlank() ? "" : "\nSelects (Kconfig):\n" + selects)
                    + (implies.isBlank() ? "" : "\nImplies (Kconfig):\n" + implies)
                    + "\nDefinitions:\n" + definitions
                    + (help.isBlank() ? "" : "\n\nUpstream Kconfig help:\n" + help)
                    + "\n\nThese values describe the last generated build. Changing prj.conf "
                    + "requires a new Zephyr configuration/build to re-evaluate dependencies.";
        }
    }

    public record Result(Details details, String unavailable) {
        public boolean available() { return details != null; }
        public static Result missing(String message) {
            return new Result(null, message == null || message.isBlank()
                    ? "Kconfig evaluation unavailable for this build." : message);
        }
    }

    public static Result parse(String output) throws IOException {
        if (output == null || output.length() > 100_000) throw new IOException("Invalid probe output size");
        String[] lines = output.strip().split("\\R", 3);
        if (lines.length < 2) throw new IOException("Incomplete Kconfig inspection result");
        if ("ZEW-KCONFIG-UNAVAILABLE".equals(lines[0]))
            return Result.missing(lines[1]);
        if (!"ZEW-KCONFIG-1".equals(lines[0])) throw new IOException("Unexpected probe protocol");
        String[] raw = lines[1].split("\\t", -1);
        if (raw.length != 12) throw new IOException("Unexpected Kconfig inspection field count");
        String[] data = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            try {
                data[i] = new String(Base64.getDecoder().decode(raw[i]), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException badEncoding) {
                throw new IOException("Invalid Kconfig probe field", badEncoding);
            }
        }
        if (!data[0].matches("[A-Z0-9_]{1,160}"))
            throw new IOException("Unexpected Kconfig symbol name");
        return new Result(new Details(data[0], data[1], data[2], data[3],
                data[4], data[5], data[6], data[7], data[8], data[9],
                data[10], data[11]), "");
    }

    /** Creates only a short-lived script copy in a system temp directory. */
    public static Result inspect(Path python, Path ninja, Path build, String symbol,
            InputStream packagedScript) {
        if (symbol == null || !symbol.matches("[A-Z0-9_]{1,160}"))
            return Result.missing("Invalid Kconfig symbol.");
        if (build == null || !Files.isRegularFile(build.resolve("zephyr/.config"))
                || !Files.isRegularFile(build.resolve("build.ninja")))
            return Result.missing("Configure the exact board with Ninja before inspecting dependencies.");
        if (python == null || ninja == null || !Files.isRegularFile(python)
                || !Files.isRegularFile(ninja) || packagedScript == null)
            return Result.missing("Managed Python, Ninja or the packaged Kconfig inspector is unavailable.");
        Path script = null;
        try (packagedScript) {
            script = Files.createTempFile("zew-kconfig-", ".py");
            Files.copy(packagedScript, script, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            var process = new ProcessBuilder(python.toString(), script.toString(),
                    "--build", build.toString(), "--ninja", ninja.toString(),
                    "--symbol", symbol)
                    .redirectErrorStream(true).start();
            try {
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(3, TimeUnit.SECONDS);
                    return Result.missing("Kconfiglib lookup timed out; the source-only view remains available.");
                }
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                return Result.missing("Kconfiglib lookup cancelled.");
            }
            byte[] bytes = process.getInputStream().readNBytes(100_001);
            if (bytes.length > 100_000)
                return Result.missing("Kconfiglib output exceeded the safe limit.");
            String output = new String(bytes, StandardCharsets.UTF_8);
            try {
                Result result = parse(output);
                if (result.available() && !symbol.equals(result.details.symbol()))
                    return Result.missing("Kconfiglib returned metadata for a different symbol.");
                return result;
            } catch (IOException protocol) {
                return Result.missing("Kconfiglib could not reconstruct this build's generated environment.");
            }
        } catch (IOException error) {
            return Result.missing("Unable to inspect Kconfig for this build: " + error.getMessage());
        } finally {
            if (script != null) try { Files.deleteIfExists(script); }
            catch (IOException ignored) { /* disposable scratch file */ }
        }
    }
}
