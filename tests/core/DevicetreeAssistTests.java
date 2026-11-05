// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

/** Headless regression tests of the cursor engine used by the real editor. */
public final class DevicetreeAssistTests {
    private static int passed;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        passed++;
    }
    private static String line(String kind, String... args) {
        var fields = new java.util.ArrayList<>(List.of(kind));
        for (String arg : args) fields.add(Base64.getEncoder().encodeToString(arg.getBytes(StandardCharsets.UTF_8)));
        return String.join("\t", fields) + "\n";
    }
    private static DevicetreeIndex.Snapshot sample() {
        var nodes = List.of(
            new DevicetreeIndex.Node("uart0", "/soc/serial@40001000", "disabled", "ti,mspm0-uart", 14),
            new DevicetreeIndex.Node("gpio0", "/soc/gpio@40000000", "okay", "ti,mspm0-gpio", 23)
        );
        var properties = List.of(
            new DevicetreeIndex.Property("/soc/serial@40001000", "current-speed", "int",
                    "Serial baud rate", false, false, List.of("9600", "115200"), "", "uart-controller.yaml"),
            new DevicetreeIndex.Property("/soc/serial@40001000", "hw-flow-control", "boolean",
                    "Enable hardware flow control", false, false, List.of(), "", "uart-controller.yaml"),
            new DevicetreeIndex.Property("/soc/serial@40001000", "parity", "string",
                    "UART parity", false, false, List.of("none", "even", "odd"), "", "uart.yaml")
        );
        return new DevicetreeIndex.Snapshot("built", "fixture", Path.of("zephyr.dts"),
                nodes, properties, List.of(new DevicetreeIndex.Compatible("ti,mspm0-uart", "", "TI UART")));
    }
    public static void main(String[] args) {
        var snapshot = sample();
        var starter = DevicetreeAssist.suggest("", 0, snapshot);
        check(starter.suggestions().size() == 7, "Five simple starting actions and two real board controllers");
        check(starter.suggestions().stream().anyMatch(p -> p.replacement().startsWith("&uart0 {")),
                "First completion directly offers a verified UART controller");
        check(starter.suggestions().stream().anyMatch(p -> p.replacement().startsWith("&gpio0 {")),
                "First completion directly offers a verified GPIO controller");
        String bareRoot = "/* Application hardware overlay */\n{";
        var repair = DevicetreeAssist.suggest(bareRoot, bareRoot.length(), snapshot);
        check(repair.start() == bareRoot.length() - 1 && repair.length() == 1
                && repair.suggestions().stream().anyMatch(p -> p.replacement().equals("/ {")),
                "Bare root offers a targeted one-character repair");

        String commentedOverlay = "/* Application hardware overlay. Validate against this board's bindings. */\n";
        check(DevicetreeAssist.suggest(commentedOverlay, commentedOverlay.length(), snapshot)
                .suggestions().size() == 7, "Starter actions and verified controllers after Configuration Studio header");
        check(starter.suggestions().stream().noneMatch(p -> p.replacement().contains("&adc")),
                "No imaginary ADC or controller");
        var reference = DevicetreeAssist.suggest("&u", 2, snapshot);
        check(reference.suggestions().size() == 1 &&
                reference.suggestions().getFirst().replacement().equals("uart0"),
                "Only real node references");
        check(reference.suggestions().getFirst().help().contains("disabled"),
                "Disabled controllers remain discoverable");
        var property = DevicetreeAssist.suggest("&uart0 {\n    cur", 16, snapshot);
        check(property.suggestions().stream().anyMatch(p -> p.replacement().equals("current-speed = ")),
                "Binding-aware property in a real override");
        var booleanProperty = DevicetreeAssist.suggest("&uart0 {\n    hw", 15, snapshot);
        check(booleanProperty.suggestions().stream().anyMatch(p -> p.replacement().equals("hw-flow-control;")),
                "Boolean properties do not get values");
        var value = "&uart0 {\n    parity = \"e";
        var enumChoice = DevicetreeAssist.suggest(value, value.length(), snapshot);
        check(enumChoice.suggestions().stream().anyMatch(p -> p.replacement().equals("even")),
                "String enum insertion inside quotes");
        String baud = "&uart0 {\n    current-speed = ";
        var numeric = DevicetreeAssist.suggest(baud, baud.length(), snapshot);
        check(numeric.suggestions().stream().anyMatch(p -> p.replacement().equals("<9600>")),
                "Integer enum is a cell, never a quoted string");
        String cell = "&uart0 {\n    current-speed = <9";
        var insideCell = DevicetreeAssist.suggest(cell, cell.length(), snapshot);
        check(insideCell.suggestions().stream().anyMatch(p -> p.replacement().equals("9600")),
                "An integer inside a cell list is not wrapped twice");
        check(DevicetreeAssist.suggest("&adc", 4, snapshot).suggestions().isEmpty(),
                "Unlisted peripherals not fabricated");
        check(DevicetreeAssist.suggest("/* &u", 5, snapshot).suggestions().isEmpty(),
                "No misleading assist inside comments");
        check(DevicetreeAssist.suggest("&", 1, DevicetreeIndex.empty("no-build", "configure first"))
                .suggestions().isEmpty(), "No board facts without a build");
        String protocol = "ZEW-DTASSIST-1\n" + line("S", "built", "test")
                + line("N", "gpio0", "/soc/gpio@1234", "disabled", "vendor,gpio", "5")
                + line("P", "/soc/gpio@1234", "ngpios", "int", "GPIO count", "no", "no", "", "", "gpio.yaml");
        var indexed = DevicetreeIndex.parse(protocol, Path.of("result.dts"));
        check(indexed.built() && indexed.label("gpio0") != null && indexed.properties().size() == 1,
                "Bounded protocol roundtrip");
        check(!DevicetreeIndex.parse("INVALID\n" + protocol, null).built(),
                "Reject malformed protocol");
        System.out.println("Passed " + passed + " Devicetree editor checks.");
    }
}
