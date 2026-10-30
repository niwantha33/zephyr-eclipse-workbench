// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A deliberately conservative, read-only *source text* GPIO call inventory.
 * It is NOT compiler AST analysis, executed code, or live pin configuration.
 * Never resolve a controller from a coincidental numeric suffix (GPIO_0 != PA).
 */
public final class GpioSourceEvidence {
    private GpioSourceEvidence() {}

    public record Call(String sourceFile, int line, String variable, String bindingName,
                       int bit, String flags, String controllerPath) {
        public boolean resolved() { return !controllerPath.isBlank(); }
        public String description() {
            return sourceFile + ":" + line + "  gpio_pin_configure(" + variable + ", "
                    + bit + ", " + flags + ")  via device_get_binding(\"" + bindingName
                    + "\")  ->  " + (resolved() ? controllerPath : "UNRESOLVED DEVICE NAME");
        }
    }

    private static final Pattern BINDING = Pattern.compile(
            "\\b(?:const\\s+)?struct\\s+device\\s*\\*\\s*([A-Za-z_]\\w*)\\s*=\\s*"
            + "device_get_binding\\s*\\(\\s*\"([A-Za-z_0-9@.,+-]{1,96})\"\\s*\\)\\s*;");
    private static final Pattern CONFIGURE = Pattern.compile(
            "\\bgpio_pin_configure\\s*\\(\\s*([A-Za-z_]\\w*)\\s*,\\s*"
            + "(0[xX][0-9a-fA-F]+|[0-9]{1,3})\\s*,\\s*"
            + "([A-Za-z_][A-Za-z_0-9| \\t()]{0,120})\\s*\\)\\s*;");
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int MAX_TOTAL_BYTES = 1024 * 1024;
    private static final int MAX_FILES = 80;

    /** Inspect bounded application source files; never traverse build artifacts. */
    public static List<Call> scanProject(Path root, List<DevicetreeHardware.Device> devices) throws IOException {
        if (root == null || !Files.isDirectory(root)) return List.of();
        Path base = root.toAbsolutePath().normalize();
        var results = new ArrayList<Call>();
        int consumed = 0;
        try (var files = Files.walk(base, 5)) {
            var candidates = files.filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> sourceFile(base.relativize(path)))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(MAX_FILES).toList();
            for (var file : candidates) {
                long size = Files.size(file);
                if (size < 1 || size > MAX_FILE_BYTES || consumed + size > MAX_TOTAL_BYTES) continue;
                consumed += (int) size;
                var text = Files.readString(file, StandardCharsets.UTF_8);
                results.addAll(inspect(base.relativize(file).toString().replace('\\', '/'), text, devices));
                if (results.size() >= 128) break;
            }
        }
        return List.copyOf(results.subList(0, Math.min(128, results.size())));
    }

    private static boolean sourceFile(Path relative) {
        if (relative == null) return false;
        for (var part : relative) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (name.startsWith(".") || name.equals("build") || name.startsWith("build_")
                    || name.equals("zephyr") || name.equals("modules") || name.equals("venv")
                    || name.equals("out") || name.equals("twister-out") || name.equals("generated"))
                return false;
        }
        String filename = relative.getFileName().toString().toLowerCase(Locale.ROOT);
        return filename.endsWith(".c") || filename.endsWith(".cpp")
                || filename.endsWith(".cc") || filename.endsWith(".cxx");
    }

    /** Inspect text from one source file, including the user-provided example. */
    public static List<Call> inspect(String sourceFile, String source,
                                     List<DevicetreeHardware.Device> devices) {
        if (source == null || source.isBlank() || source.length() > MAX_FILE_BYTES || devices == null)
            return List.of();
        // Preserve newlines and quoted strings while discarding comments.
        String code = DevicetreeParameters.withoutComments(source);
        var assignments = new ArrayList<Binding>();
        var bindingMatches = BINDING.matcher(code);
        while (bindingMatches.find()) {
            assignments.add(new Binding(bindingMatches.start(), bindingMatches.end(),
                    bindingMatches.group(1), bindingMatches.group(2)));
        }
        var calls = new ArrayList<Call>();
        var matches = CONFIGURE.matcher(code);
        while (matches.find() && calls.size() < 64) {
            String variable = matches.group(1);
            Binding binding = null;
            for (var candidate : assignments) {
                if (candidate.start() >= matches.start()) break;
                if (candidate.variable().equals(variable)) binding = candidate;
            }
            if (binding == null) continue;
            // A later assignment could redirect this pointer. Never assert
            // that a device resolution survives a possible reassignment.
            String between = code.substring(binding.end(), matches.start());
            boolean reassigned = Pattern.compile("\\b" + Pattern.quote(variable)
                    + "\\s*=(?!=)").matcher(between).find();
            int bit;
            try {
                bit = Integer.decode(matches.group(2));
            } catch (NumberFormatException invalid) { continue; }
            if (bit < 0 || bit > 63) continue;
            String path = "";
            final String deviceName = binding.name();
            if (!reassigned) {
                var eligible = devices.stream().filter(DevicetreeHardware.Device::enabled)
                        .filter(d -> HardwarePeripheralIndex.category(d).equals("GPIO"))
                        // DEVICE_DT_NAME uses label if present, otherwise node full name.
                        .filter(d -> deviceName.equals(d.label()) || deviceName.equals(d.name()))
                        .toList();
                if (eligible.size() == 1) path = eligible.get(0).path();
            }
            int line = 1;
            for (int n = 0; n < matches.start(); n++) if (code.charAt(n) == '\n') line++;
            calls.add(new Call(sourceFile, line, variable, binding.name(), bit,
                    matches.group(3).trim().replaceAll("\\s+", " "), path));
        }
        return List.copyOf(calls);
    }

    private record Binding(int start, int end, String variable, String name) {}
}
