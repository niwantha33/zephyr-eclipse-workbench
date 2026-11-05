// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Bounded observations of tool output, never a guessed source-level root cause. */
public final class BuildFailureInvestigator implements Consumer<String> {
    private static final Pattern ANSI = Pattern.compile("\\x1b\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern DIAGNOSTIC = Pattern.compile(
        "(?i)(?:^|\\s)(?:fatal error:|error:|CMake Error|undefined reference to|"
        + "multiple definition of|collect2: error|ninja: error:|Traceback \\(most recent call last\\))");
    private final List<String> observations = new ArrayList<>();
    private final List<String> targets = new ArrayList<>();
    private int lineNumber;
    private boolean omitted;
    private int contextRemaining;
    private boolean commandPending;
    private final List<String> commands = new ArrayList<>();

    /** Start another explicit tool attempt while retaining complete-log line numbering. */
    public void beginAttempt() {
        observations.clear();
        targets.clear();
        commands.clear();
        omitted = false;
        contextRemaining = 0;
        commandPending = false;
    }

    @Override public void accept(String raw) {
        lineNumber++;
        String line = ANSI.matcher(raw).replaceAll("").replaceAll("[\\p{Cntrl}&&[^\\t]]", "").strip();
        if (line.startsWith("FAILED:")) {
            commandPending = true;
            if (targets.size() < 8) targets.add(limit(line)); else omitted = true;
        } else if (!line.startsWith("FATAL ERROR: command exited") && DIAGNOSTIC.matcher(line).find()) {
            observe(line);
            contextRemaining = 4;
            commandPending = false;
        } else if (commandPending && !line.isBlank()) {
            // Ninja prints the failed command immediately after its FAILED target.
            // Do not mistake a parallel progress line or stop summary for that command.
            if (!line.matches("^\\[\\d+/\\d+\\].*") && !line.startsWith("ninja:")
                    && !line.startsWith("FATAL ERROR:")) {
                if (commands.size() < 8) commands.add(limit(line)); else omitted = true;
            }
            commandPending = false;
        } else if (contextRemaining > 0) {
            if (line.startsWith("[") || line.startsWith("ninja:") || line.startsWith("FATAL ERROR:")) contextRemaining = 0;
            else { observe(line); contextRemaining--; }
        }
    }
    private void observe(String line) {
        if (observations.size() < 24) observations.add("log line " + lineNumber + ": " + limit(line));
        else omitted = true;
    }
    private static String limit(String line) {
        return line.length() > 2000 ? line.substring(0, 2000) + " [line shortened]" : line;
    }
    public String summary() {
        var result = new StringBuilder("Build failure investigation (observed tool output)\n");
        if (!targets.isEmpty()) result.append(String.join("\n", targets)).append('\n');
        if (!commands.isEmpty()) result.append("Failed command output (reported by Ninja):\n")
            .append(String.join("\n", commands)).append('\n');
        if (observations.isEmpty()) {
            result.append("Root cause unknown: no compiler, linker or CMake diagnostic was captured.\n")
                .append("Ninja stopped and exit status 1 are failure summaries. The last Building line does not identify the failed source.\n")
                .append("Next: inspect the complete log above FAILED:, or rerun this same build with verbose, single-job output.\n");
        } else {
            result.append(String.join("\n", observations)).append('\n')
                .append("Next: inspect the first diagnostic and its surrounding log before editing configuration or source.\n")
                .append("These are reported errors; their underlying cause has not been independently verified.\n");
        }
        if (omitted) result.append("Additional diagnostics omitted from summary; consult the complete log.\n");
        return result.toString();
    }

    private static String json(String text) {
        var out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '\\' || c == '\"') out.append('\\').append(c);
            else if (c < 32) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('\"').toString();
    }

    /** Offline entry point, no process execution or network access. */
    public static void main(String[] args) throws Exception {
        boolean machine = args.length == 2 && args[0].equals("--json");
        if ((!machine && args.length != 1) || args[args.length - 1].startsWith("--")) {
            System.err.println("Usage: BuildFailureInvestigator [--json] BUILD_LOG");
            System.exit(2);
            return;
        }
        var investigator = new BuildFailureInvestigator();
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var bytes = new java.security.DigestInputStream(
                Files.newInputStream(Path.of(args[args.length - 1])), digest);
             var input = new java.io.BufferedReader(new java.io.InputStreamReader(bytes, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) investigator.accept(line);
        }
        if (machine) {
            System.out.println("{\"schema_version\":\"0.1.0\",\"root_cause\":\"unknown\","
                + "\"extraction_method\":\"tool-output-patterns\",\"producing_tool_version\":null,"
                + "\"log_sha256\":" + json(java.util.HexFormat.of().formatHex(digest.digest()))
                + ",\"summary\":" + json(investigator.summary()) + "}");
        } else System.out.print(investigator.summary());
    }
}
