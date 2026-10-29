// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Diagnoses undefined Kconfig settings using Zephyr's actual configure output.
 * Never edits application sources, guesses module installations or disables warnings.
 */
public final class KconfigFailure {
    private static final Pattern UNDEFINED = Pattern.compile(
            "^(.+?):(\\d+):\\s*warning:\\s*attempt to assign the value '([^']*)' "
            + "to the undefined symbol ([A-Za-z_][A-Za-z0-9_]*)\\s*$");
    private static final int MAX_ISSUES = 16;
    private final LinkedHashMap<String, Issue> issues = new LinkedHashMap<>();

    public record Issue(String file, int line, String symbol) {
        public String suggestion() {
            if ("DEBUG_INFO".equals(symbol)) {
                return "Remove CONFIG_DEBUG_INFO from this configuration file: it is not a "
                        + "Zephyr 4.4 Kconfig option. For debug-friendly -Og optimization, "
                        + "select Project Properties > Zephyr > Debug (-Og), which creates an "
                        + "independent build, or use CONFIG_DEBUG_OPTIMIZATIONS=y in an "
                        + "appropriate debug-only configuration. Debug symbols and "
                        + "CONFIG_DEBUG_THREAD_INFO are separate features.";
            }
            return "CONFIG_" + symbol + " is undefined in the active Kconfig tree. "
                    + "Check its spelling and availability in the pinned Zephyr version, "
                    + "selected board, and activated modules. Do not suppress Kconfig "
                    + "warnings or download unrelated toolchains to bypass this error.";
        }
    }

    public static Optional<Issue> parse(String line) {
        if (line == null || line.length() > 16384) return Optional.empty();
        var match = UNDEFINED.matcher(line.strip());
        if (!match.matches()) return Optional.empty();
        try {
            int lineNumber = Integer.parseInt(match.group(2));
            if (lineNumber < 1) return Optional.empty();
            return Optional.of(new Issue(match.group(1), lineNumber, match.group(4)));
        } catch (NumberFormatException error) {
            return Optional.empty();
        }
    }

    public void accept(String line) {
        parse(line).ifPresent(issue -> {
            if (issues.size() < MAX_ISSUES) {
                issues.putIfAbsent(issue.file() + ":" + issue.line() + ":" + issue.symbol(), issue);
            }
        });
    }

    public boolean hasIssues() { return !issues.isEmpty(); }

    public String description() {
        if (issues.isEmpty()) return "";
        var message = new StringBuilder("Zephyr Kconfig rejected undefined configuration setting(s):");
        for (Issue issue : issues.values()) {
            message.append("\n  ").append(issue.file()).append(":").append(issue.line())
                    .append(": CONFIG_").append(issue.symbol()).append("\n  ")
                    .append(issue.suggestion());
        }
        message.append("\nNo application configuration or installed dependency was modified.");
        return message.toString();
    }
}
