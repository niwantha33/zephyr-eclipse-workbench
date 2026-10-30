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
                       int bit, String flags, String controllerPath, String api) {
        public Call(String sourceFile, int line, String variable, String bindingName,
                    int bit, String flags, String controllerPath) {
            this(sourceFile, line, variable, bindingName, bit, flags, controllerPath, "gpio_pin_configure");
        }
        public String expression() {
            return api + "(" + variable + (api.endsWith("_dt") ? "" : ", " + bit) + ", " + flags + ")";
        }
        public boolean resolved() { return !controllerPath.isBlank(); }
        public String description() {
            return sourceFile + ":" + line + "  " + expression() + "  via " + bindingName
                    + "  ->  " + (resolved() ? controllerPath : "UNRESOLVED DEVICE NAME");
        }
    }

    private static final Pattern BINDING = Pattern.compile(
            "\\b(?:const\\s+)?struct\\s+device\\s*\\*\\s*([A-Za-z_]\\w*)\\s*=\\s*"
            + "device_get_binding\\s*\\(\\s*\"([A-Za-z_0-9@.,+-]{1,96})\"\\s*\\)\\s*;");
    private static final Pattern DT_BINDING = Pattern.compile(
            "\\b(?:const\\s+)?struct\\s+device\\s*\\*\\s*([A-Za-z_]\\w*)\\s*=\\s*"
            + "DEVICE_DT_GET\\s*\\(\\s*DT_NODELABEL\\s*\\(\\s*([A-Za-z_]\\w*)\\s*\\)\\s*\\)\\s*;");
    private static final Pattern CONFIGURE = Pattern.compile(
            "\\bgpio_pin_configure\\s*\\(\\s*([A-Za-z_]\\w*)\\s*,\\s*"
            + "(0[xX][0-9a-fA-F]+|[0-9]{1,3})\\s*,\\s*"
            + "([A-Za-z_][A-Za-z_0-9| \\t()]{0,120})\\s*\\)");
    private static final Pattern DT_SPEC = Pattern.compile(
            "\\bstruct\\s+gpio_dt_spec\\s+([A-Za-z_]\\w*)\\s*=\\s*GPIO_DT_SPEC_GET\\s*\\(\\s*"
            + "(DT_(?:ALIAS|NODELABEL)\\s*\\(\\s*[A-Za-z_]\\w*\\s*\\)|[A-Za-z_]\\w*)\\s*,\\s*(gpios|[A-Za-z_]\\w*-gpios)\\s*\\)");
    private static final Pattern DT_CONFIGURE = Pattern.compile(
            "\\bgpio_pin_configure_dt\\s*\\(\\s*&\\s*([A-Za-z_]\\w*)\\s*,\\s*"
            + "([A-Za-z_][A-Za-z_0-9| \\t()]{0,120})\\s*\\)");
    private static final Pattern DT_SELECTOR = Pattern.compile("DT_(ALIAS|NODELABEL)\\s*\\(\\s*([A-Za-z_]\\w*)\\s*\\)");
    private static final Pattern NODE_MACRO = Pattern.compile("(?m)^\\s*#\\s*define\\s+([A-Za-z_]\\w*)\\s+(DT_(?:ALIAS|NODELABEL)\\s*\\(\\s*[A-Za-z_]\\w*\\s*\\))\\s*$");
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int MAX_TOTAL_BYTES = 1024 * 1024;
    private static final int MAX_FILES = 80;

    /** Inspect bounded application source files; never traverse build artifacts. */
    public static List<Call> scanProject(Path root, List<DevicetreeHardware.Device> devices) throws IOException {
        return scanProject(root, devices, "");
    }

    public static List<Call> scanProject(Path root, String generatedDts) throws IOException {
        return scanProject(root, DevicetreeHardware.inspect(generatedDts), generatedDts);
    }

    private static List<Call> scanProject(Path root, List<DevicetreeHardware.Device> devices,
                                          String generatedDts) throws IOException {
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
                results.addAll(inspect(base.relativize(file).toString().replace('\\', '/'), text, devices, generatedDts));
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
        return inspect(sourceFile, source, devices, "");
    }

    public static List<Call> inspect(String sourceFile, String source, String generatedDts) {
        return inspect(sourceFile, source, DevicetreeHardware.inspect(generatedDts), generatedDts);
    }

    private static List<Call> inspect(String sourceFile, String source,
                                      List<DevicetreeHardware.Device> devices, String generatedDts) {
        if (source == null || source.isBlank() || source.length() > MAX_FILE_BYTES || devices == null)
            return List.of();
        // Preserve newlines and quoted strings while discarding comments.
        String code = DevicetreeParameters.withoutComments(source);
        var assignments = new ArrayList<Binding>();
        var bindingMatches = BINDING.matcher(code);
        while (bindingMatches.find()) {
            assignments.add(new Binding(bindingMatches.start(), bindingMatches.end(),
                    bindingMatches.group(1), bindingMatches.group(2), ""));
        }
        if (!generatedDts.isBlank() && code.contains("DEVICE_DT_GET")) {
            var labels = DevicetreeHardware.nodeLabels(generatedDts);
            var direct = DT_BINDING.matcher(code);
            while (direct.find()) {
                String path = labels.getOrDefault(direct.group(2), "");
                String resolved = devices.stream().anyMatch(device -> device.path().equals(path)
                        && device.enabled() && HardwarePeripheralIndex.category(device).equals("GPIO")) ? path : "";
                assignments.add(new Binding(direct.start(), direct.end(), direct.group(1),
                        "DEVICE_DT_GET(DT_NODELABEL(" + direct.group(2) + "))", resolved));
            }
            assignments.sort(Comparator.comparingInt(Binding::start));
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
            if (!reassigned && !binding.controllerPath().isBlank()) path = binding.controllerPath();
            else if (!reassigned && !binding.name().startsWith("DEVICE_DT_GET(")) {
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
        if (!generatedDts.isBlank() && code.contains("gpio_pin_configure_dt"))
            addDtCalls(sourceFile, code, generatedDts, devices, calls);
        calls.sort(Comparator.comparingInt(Call::line));
        return List.copyOf(calls);
    }

    private static void addDtCalls(String sourceFile, String code, String generatedDts,
            List<DevicetreeHardware.Device> devices, List<Call> calls) {
        var labels = DevicetreeHardware.nodeLabels(generatedDts);
        var properties = DevicetreeParameters.inspect(generatedDts);
        var macros = new java.util.HashMap<String, String>();
        var macroMatches = NODE_MACRO.matcher(code);
        while (macroMatches.find()) {
            String name = macroMatches.group(1);
            if (macros.containsKey(name)) macros.put(name, "");
            else macros.put(name, macroMatches.group(2));
        }
        var specs = new java.util.HashMap<String, DtSpec>();
        var specMatches = DT_SPEC.matcher(code);
        while (specMatches.find()) {
            String variable = specMatches.group(1), expression = specMatches.group(2);
            if (specs.containsKey(variable)) { specs.put(variable, new DtSpec("", "", -1, -1, -1)); continue; }
            var selector = DT_SELECTOR.matcher(macros.getOrDefault(expression, expression));
            String path = "";
            if (selector.matches()) {
                String name = selector.group(2);
                if (selector.group(1).equals("NODELABEL")) path = labels.getOrDefault(name, "");
                else for (var property : properties) {
                    if (!property.path().equals("/aliases") || !property.name().equals(name.replace('_', '-'))) continue;
                    String reference = property.value().strip();
                    if (reference.startsWith("&")) path = labels.getOrDefault(reference.substring(1), "");
                    else if (reference.startsWith("\"/") && reference.endsWith("\"")) {
                        String absolute = reference.substring(1, reference.length() - 1);
                        if (devices.stream().anyMatch(device -> device.path().equals(absolute))) path = absolute;
                    }
                }
            }
            String controller = ""; int bit = -1;
            var candidates = new ArrayList<java.util.Map.Entry<String, GpioBankEvidence.Usage>>();
            for (var device : devices) {
                var bank = GpioBankEvidence.inspect(device, properties, labels);
                if (bank == null) continue;
                for (var use : bank.uses()) if (use.consumerPath().equals(path)
                        && use.property().equals(specMatches.group(3)))
                    candidates.add(java.util.Map.entry(bank.controllerPath(), use));
            }
            // GPIO_DT_SPEC_GET selects index zero; require one exact specifier
            // rather than sorting/deduplicating and accidentally selecting another.
            if (candidates.size() == 1) {
                controller = candidates.get(0).getKey(); bit = candidates.get(0).getValue().bit();
            }
            specs.put(variable, new DtSpec(expression, controller, bit, specMatches.start(), specMatches.end()));
        }
        var matches = DT_CONFIGURE.matcher(code);
        while (matches.find() && calls.size() < 128) {
            String variable = matches.group(1);
            var spec = specs.get(variable);
            if (spec == null) spec = new DtSpec("GPIO_DT_SPEC_GET unresolved", "", -1, -1, -1);
            if (spec.end() < 0 || spec.end() >= matches.start()
                    || Pattern.compile("\\b" + Pattern.quote(variable) + "\\s*=(?!=)")
                        .matcher(code.substring(spec.end(), matches.start())).find())
                spec = new DtSpec(spec.expression(), "", -1, -1, -1);
            int line = 1;
            for (int i = 0; i < matches.start(); i++) if (code.charAt(i) == '\n') line++;
            calls.add(new Call(sourceFile, line, "&" + variable, spec.expression(), spec.bit(),
                    matches.group(2).trim().replaceAll("\\s+", " "), spec.controller(), "gpio_pin_configure_dt"));
        }
    }

    private record DtSpec(String expression, String controller, int bit, int start, int end) {}
    private record Binding(int start, int end, String variable, String name, String controllerPath) {}
}
