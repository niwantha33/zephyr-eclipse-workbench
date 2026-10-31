// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.List;
import java.util.regex.Pattern;

/** A source-only GPIO consumer request. Zephyr must validate it on the next build. */
public final class GpioOverlayDraft {
    private GpioOverlayDraft() {}
    public enum Direction { INPUT, OUTPUT }
    public enum Polarity { ACTIVE_HIGH, ACTIVE_LOW }

    public static String validateName(String name) {
        return name != null && name.matches("[a-z][a-z0-9_]{0,31}") ? null
                : "Use 1–32 lowercase letters, digits or underscores, starting with a letter.";
    }

    public static String generate(String generatedDts, String controllerPath, int bit, String name,
            Direction direction, Polarity polarity, String existingOverlay, List<GpioSourceEvidence.Call> sourceCandidates) {
        String invalid = validateName(name);
        if (invalid != null) throw new IllegalArgumentException(invalid);
        if (direction == null || polarity == null) throw new IllegalArgumentException("Choose input/output and polarity.");
        if (existingOverlay == null || existingOverlay.length() > 2_000_000)
            throw new IllegalArgumentException("Missing or oversized source overlay.");
        var nodes = DevicetreeHardware.inspect(generatedDts);
        var controller = nodes.stream().filter(n -> n.path().equals(controllerPath)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("The selected controller is not in this built DTS."));
        if (nodes.stream().anyMatch(n -> !n.enabled() && (controllerPath.equals(n.path())
                || controllerPath.startsWith(n.path() + "/"))))
            throw new IllegalArgumentException("The selected controller or an ancestor is disabled in this build.");
        var properties = DevicetreeParameters.inspect(generatedDts);
        var labels = DevicetreeHardware.nodeLabels(generatedDts);
        var bank = GpioBankEvidence.inspect(controller, properties, labels);
        if (bank == null || bit < 0 || bit >= bank.bitPositions())
            throw new IllegalArgumentException("The selected logical index is outside a known GPIO register range.");
        boolean twoCells = properties.stream().anyMatch(p -> p.path().equals(controllerPath) && p.name().equals("#gpio-cells")
                && p.value().matches("<\\s*(?:0[xX]0*2|0*2)\\s*>"));
        if (!twoCells) throw new IllegalArgumentException("This GPIO consumer template requires exactly two GPIO cells (pin and flags).");
        String label = labels.entrySet().stream().filter(e -> e.getValue().equals(controllerPath))
                .map(java.util.Map.Entry::getKey).filter(k -> k.matches("[A-Za-z_][A-Za-z_0-9]*"))
                .sorted().findFirst().orElseThrow(() -> new IllegalArgumentException("No verified controller node label; no GPIO reference was guessed."));
        if (bank.uses().stream().anyMatch(use -> use.bit() == bit)
                || sourceCandidates != null && sourceCandidates.stream().anyMatch(call -> call.resolved()
                        && controllerPath.equals(call.controllerPath()) && call.bit() == bit))
            throw new IllegalArgumentException("This index already has a built DTS reference or exact saved-source candidate. Inspect that usage instead.");
        String alias = name.replace('_', '-');
        String container = "zew-" + alias + "-" + direction.name().toLowerCase(java.util.Locale.ROOT);
        if (labels.containsKey(name) || nodes.stream().anyMatch(n -> n.name().equals(name) || n.name().equals(container))
                || properties.stream().anyMatch(p -> p.path().equals("/aliases") && p.name().equals(alias)))
            throw new IllegalArgumentException("The requested name, alias or consumer container already exists in the built DTS.");
        String cleanOverlay = DevicetreeParameters.withoutComments(existingOverlay);
        for (var controllerLabel : labels.entrySet()) if (controllerLabel.getValue().equals(controllerPath)) {
            String bitValue = "(?:" + bit + "|0[xX]0*" + Integer.toHexString(bit) + ")";
            if (Pattern.compile("&" + Pattern.quote(controllerLabel.getKey()) + "\\s+" + bitValue + "(?=[\\s>])")
                    .matcher(cleanOverlay).find())
                throw new IllegalArgumentException("This index is already referenced in the selected source overlay; rebuild and inspect it first.");
        }
        for (String token : List.of(name, alias, container)) {
            if (Pattern.compile("(?<![A-Za-z_0-9,-])" + Pattern.quote(token) + "\\s*(?::|=|\\{)").matcher(cleanOverlay).find())
                throw new IllegalArgumentException("The requested name already exists in this source overlay; choose another name.");
        }
        String flag = polarity == Polarity.ACTIVE_LOW ? "GPIO_ACTIVE_LOW" : "GPIO_ACTIVE_HIGH";
        String compatible = direction == Direction.INPUT ? "gpio-keys" : "gpio-leds";
        return "#include <zephyr/dt-bindings/gpio/gpio.h>\n\n"
                + "/* Requested logical GPIO " + bank.bitLabel(bit) + "; verify package, circuit and pin ownership. */\n"
                + "/ {\n    aliases {\n        " + alias + " = &" + name + ";\n    };\n\n"
                + "    " + container + " {\n        compatible = \"" + compatible + "\";\n"
                + "        " + name + ": " + name + " {\n            gpios = <&" + label + " " + bit + " " + flag + ">;\n"
                + "            label = \"" + name + "\";\n"
                + "        };\n    };\n};\n";
    }
}
