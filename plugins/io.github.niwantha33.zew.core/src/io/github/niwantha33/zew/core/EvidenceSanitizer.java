// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Conservative, deliberately lossy sanitisation for shareable investigation exports. */
public final class EvidenceSanitizer {
    private static final Pattern ASSIGN = Pattern.compile("^\\s*(CONFIG_[A-Z0-9_]+)=(.*)$");
    private static final Pattern NOT_SET = Pattern.compile("^\\s*#\\s*(CONFIG_[A-Z0-9_]+) is not set\\s*$");
    private static final Pattern SCALAR = Pattern.compile("(?:[ymn]|-?[0-9]{1,16}|0[xX][0-9A-Fa-f]{1,16})");
    private static final Pattern PUBLIC_ID = Pattern.compile("[A-Za-z0-9_.@/+-]{1,160}");
    private static final Pattern PRIVATE_NAME = Pattern.compile("(?i).*(?:PASSWORD|TOKEN|SECRET|PASSKEY|PRIVATE|CREDENTIAL|API_KEY|AUTH|SSID|CERT|IDENTITY|USERNAME|USER_NAME|KEY|PIN|PSK|SEED|COOKIE|SERIAL_NUMBER|MAC_ADDRESS).*");

    private EvidenceSanitizer() {}

    /** Retains Kconfig keys and safe scalar/target values. Drops source comments and unknown syntax. */
    public static byte[] kconfig(byte[] raw) {
        StringBuilder out = new StringBuilder();
        for (String line : new String(raw, StandardCharsets.UTF_8).split("\\R", -1)) {
            var set = ASSIGN.matcher(line);
            var absent = NOT_SET.matcher(line);
            if (set.matches()) {
                String key = set.group(1), value = set.group(2).strip();
                if (PRIVATE_NAME.matcher(key).matches()) value = "\"<redacted>\"";
                else if (!SCALAR.matcher(value).matches()) {
                    String plain = value.length() >= 2 && value.charAt(0) == '"'
                            && value.charAt(value.length() - 1) == '"'
                            ? value.substring(1, value.length() - 1) : "";
                    if (isPublicIdentifier(key) && PUBLIC_ID.matcher(plain).matches()) value = "\"" + plain + "\"";
                    else value = "\"<redacted>\"";
                }
                out.append(key).append('=').append(value);
            } else if (absent.matches()) {
                out.append("# ").append(absent.group(1)).append(" is not set");
            }
            out.append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isPublicIdentifier(String key) {
        return key.equals("CONFIG_BOARD") || key.equals("CONFIG_BOARD_TARGET")
                || key.equals("CONFIG_SOC") || key.equals("CONFIG_KERNEL_BIN_NAME");
    }

    /**
     * Summarise resolved hardware without including arbitrary vendor binding
     * strings, user comments, passwords, byte arrays or source locations.
     * This is NOT a compilable or complete Devicetree.
     */
    public static byte[] devicetreeInventory(String resolved) {
        StringBuilder out = new StringBuilder("# REDACTED DEVICE INVENTORY — NOT COMPILABLE DTS\n")
                .append("# Derived from generated zephyr.dts; no physical package/pin verification.\n")
                .append("path\tcompatible\tstatus\treg\n");
        for (var device : DevicetreeHardware.inspect(resolved)) {
            out.append(publicCell(device.path())).append('\t').append(publicCell(device.compatible()))
                    .append('\t').append(publicCell(device.status()))
                    .append('\t').append(publicCell(device.reg())).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String publicCell(String value) {
        if (value == null || value.isBlank()) return "(not specified)";
        String clean = value.strip().replace('"', ' ').strip();
        if (clean.length() <= 180 && clean.matches("[A-Za-z0-9_./,@+<>\\- ]+")
                && !PRIVATE_NAME.matcher(clean).matches()) return clean;
        return "<redacted>";
    }

    public static byte[] diagnosis(String report, String application, String build) {
        String result = report;
        if (build != null && !build.isBlank()) result = result.replace(build, "<build>");
        if (application != null && !application.isBlank()) result = result.replace(application, "<application>");
        return result.getBytes(StandardCharsets.UTF_8);
    }
}
