// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Faithful generated-DTS text fixtures; no hardware, DTC or compiler claim. */
public final class GpioUsageRegressionTests {
    private static int checks;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static final String DTS = """
            / {
                aliases {
                    led0 = &blue_led;
                };
                soc {
                    pin-controller@400a0000 {
                        gpioa: gpio_a: gpio@400a0000 {
                            compatible = "ti,mspm0-gpio";
                            gpio-controller;
                            #gpio-cells = < 0x2 >;
                            status = "okay";
                        };
                        gpiob: gpio@400a2000 {
                            compatible = "ti,mspm0-gpio";
                            gpio-controller;
                            #gpio-cells = < 0x2 >;
                            status = "okay";
                        };
                    };
                };
                leds {
                    compatible = "gpio-leds";
                    blue_led: led_0 {
                        gpios = < &gpiob 0x16 0x0 >;
                    };
                };
                spi_device {
                    cs-gpios = < &gpioa 0x8 0x1 >, < &gpiob 0x7 0x0 >;
                };
            };
            """;
    private interface GpioAction { void run(); }
    private static void rejectsGpio(GpioAction action) {
        try { action.run(); throw new AssertionError("invalid GPIO request accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        var nodes = DevicetreeHardware.inspect(DTS);
        var properties = DevicetreeParameters.inspect(DTS);
        var labels = DevicetreeHardware.nodeLabels(DTS);
        check(labels.get("gpioa").equals("/soc/pin-controller@400a0000/gpio@400a0000")
                && labels.get("gpio_a").equals(labels.get("gpioa")), "all exact node labels retained, separate from label property");
        var a = nodes.stream().filter(n -> n.name().equals("gpio@400a0000")).findFirst().orElseThrow();
        var b = nodes.stream().filter(n -> n.name().equals("gpio@400a2000")).findFirst().orElseThrow();
        var bankA = GpioBankEvidence.inspect(a, properties, labels);
        var bankB = GpioBankEvidence.inspect(b, properties, labels);
        check(bankA.uses().size() == 1 && bankA.uses().get(0).bit() == 8, "symbolic generated DTS resolves GPIO A without numeric phandle");
        check(bankB.uses().size() == 2 && bankB.uses().stream().anyMatch(u -> u.bit() == 22), "MSPM0 LED consumer correctly belongs to GPIO B");
        check(bankB.resolvedPhandle(), "exact symbolic controller identity counts as resolved");
        String unknown = DTS.replace("< &gpioa 0x8 0x1 >", "< &unknown 0x8 0x1 >");
        check(GpioBankEvidence.inspect(b, DevicetreeParameters.inspect(unknown), DevicetreeHardware.nodeLabels(unknown)).uses().size() == 1,
                "unknown controller discards the whole mixed specifier list");
        String corrupt = DTS.replace("< &gpioa 0x8 0x1 >", "< &gpioa &unknown 0x1 >");
        check(GpioBankEvidence.inspect(b, DevicetreeParameters.inspect(corrupt), DevicetreeHardware.nodeLabels(corrupt)).uses().size() == 1,
                "symbolic argument cannot produce a partial false reference on another controller");
        String impossibleBit = DTS.replace("0x16 0x0", "0x28 0x0");
        check(GpioBankEvidence.inspect(b, DevicetreeParameters.inspect(impossibleBit), DevicetreeHardware.nodeLabels(impossibleBit)).uses().size() == 1,
                "MSPM0 register range cannot fabricate a GPIO B index 40");
        String code = """
                #define LED0_NODE DT_ALIAS(led0)
                static const struct gpio_dt_spec led = GPIO_DT_SPEC_GET(LED0_NODE, gpios);
                int main(void) {
                    if (gpio_pin_configure_dt(&led, GPIO_OUTPUT_INACTIVE) < 0) return -1;
                    const struct device *gpio_dev = DEVICE_DT_GET(DT_NODELABEL(gpioa));
                    if (gpio_pin_configure(gpio_dev, 15, GPIO_OUTPUT_ACTIVE) < 0) return -1;
                    const struct device *unknown = device_get_binding("GPIO_0");
                    gpio_pin_configure(unknown, 9, GPIO_OUTPUT_ACTIVE);
                }
                """;
        var calls = GpioSourceEvidence.inspect("src/main.c", code, DTS);
        check(calls.size() == 3, "condition-wrapped direct and _dt configure calls are not silently omitted");
        check(calls.get(0).resolved() && calls.get(0).bit() == 22 && calls.get(0).controllerPath().equals(b.path())
                && calls.get(0).expression().startsWith("gpio_pin_configure_dt(&led"), "DT_ALIAS gpio_dt_spec resolved through generated DTS");
        check(calls.get(1).resolved() && calls.get(1).bit() == 15 && calls.get(1).controllerPath().equals(a.path()),
                "exact DEVICE_DT_GET(DT_NODELABEL(gpioa)) candidate resolved");
        check(!calls.get(2).resolved(), "GPIO_0 remains unresolved without an exact built device-name match");
        check(GpioSourceEvidence.inspect("src/main.c", code, DTS.replace("led0 = &blue_led;", "led0 = \"/leds/led_0\";")).get(0).resolved(),
                "compiled absolute-path alias strings resolve to an existing exact consumer");
        check(!GpioSourceEvidence.inspect("src/main.c", code.replace("DT_ALIAS(led0)", "DT_ALIAS(missing)"), DTS).get(0).resolved(),
                "missing alias never guessed to another LED");
        check(!GpioSourceEvidence.inspect("src/main.c", code.replace("if (gpio_pin_configure_dt", "led = other; if (gpio_pin_configure_dt"), DTS).get(0).resolved(),
                "source spec reassignment invalidates controller candidate");
        String template = GpioExampleCode.selectedIndex(bankA, 15, labels);
        check(template.contains("DEVICE_DT_GET(DT_NODELABEL(gpio_a))") || template.contains("DEVICE_DT_GET(DT_NODELABEL(gpioa))"),
                "template uses exact existing controller label");
        check(template.contains("device_is_ready") && template.contains("gpio_pin_configure(gpio_dev, 15, GPIO_OUTPUT_INACTIVE)")
                && template.contains("return err"), "copyable template has readiness and GPIO return checks");
        check(GpioSourceEvidence.inspect("template.c", template, DTS).stream().anyMatch(c -> c.resolved() && c.bit() == 15),
                "actual selected-index template is detected by the source inventory");
        String example = Files.readString(Path.of("examples/gpio_usage_demo/src/main.c"));
        var exampleCalls = GpioSourceEvidence.inspect("src/main.c", example, DTS);
        check(exampleCalls.size() == 2 && exampleCalls.get(0).bit() == 22 && exampleCalls.get(1).bit() == 15,
                "shipped example exposes default board LED plus optional exact-bank output as saved source candidates");
        check(Files.readString(Path.of("examples/gpio_usage_demo/Kconfig")).contains("default n"), "extra driven output is opt-in");
        check(GpioBankEvidence.consumerNames(bankB, properties, labels).get(22).equals("led0"),
                "used GPIO B bit 22 carries the exact compiled led0 alias, not a fabricated GPIO A label");
        var propertyLabelDts = DTS.replace("led0 = &blue_led;", "").replace("gpios = < &gpiob 0x16 0x0 >;", "gpios = < &gpiob 0x16 0x0 >;\nlabel = \"Status Green\";");
        check(GpioBankEvidence.consumerNames(bankB, DevicetreeParameters.inspect(propertyLabelDts), DevicetreeHardware.nodeLabels(propertyLabelDts)).get(22).equals("Status Green"),
                "exact label property is displayed when no alias exists");
        check(GpioBankEvidence.consumerNames(bankA, properties, labels).get(8).equals("spi_device"),
                "unlabelled consumer falls back to its actual node name");
        String output = GpioOverlayDraft.generate(DTS, a.path(), 15, "user_led", GpioOverlayDraft.Direction.OUTPUT,
                GpioOverlayDraft.Polarity.ACTIVE_LOW, "", List.of());
        check(output.contains("compatible = \"gpio-leds\"") && output.contains("user_led: user_led")
                && output.contains("user-led = &user_led;") && output.contains("15 GPIO_ACTIVE_LOW>"), "output request uses cross-SoC gpio-leds, safe name/alias and chosen polarity");
        String input = GpioOverlayDraft.generate(DTS, a.path(), 15, "user_button", GpioOverlayDraft.Direction.INPUT,
                GpioOverlayDraft.Polarity.ACTIVE_HIGH, "", List.of());
        check(input.contains("compatible = \"gpio-keys\"") && input.contains("15 GPIO_ACTIVE_HIGH>")
                && !input.contains("zephyr,code"), "input request uses gpio-keys without inventing an optional event code");
        check(output.contains("#include <zephyr/dt-bindings/gpio/gpio.h>"), "GPIO flag macros get their official binding header");
        for (String invalid : List.of("", "MyGPIO", "gpio; status", "../gpio", "gpio-name", "a".repeat(33))) {
            rejectsGpio(() -> GpioOverlayDraft.generate(DTS, a.path(), 15, invalid, GpioOverlayDraft.Direction.OUTPUT,
                    GpioOverlayDraft.Polarity.ACTIVE_LOW, "", List.of()));
        }
        rejectsGpio(() -> GpioOverlayDraft.generate(DTS, b.path(), 22, "another_led", GpioOverlayDraft.Direction.OUTPUT,
                GpioOverlayDraft.Polarity.ACTIVE_LOW, "", List.of()));
        rejectsGpio(() -> GpioOverlayDraft.generate(DTS, a.path(), 15, "user_led", GpioOverlayDraft.Direction.OUTPUT,
                GpioOverlayDraft.Polarity.ACTIVE_LOW, "", calls));
        rejectsGpio(() -> GpioOverlayDraft.generate(DTS, a.path(), 15, "led0", GpioOverlayDraft.Direction.OUTPUT,
                GpioOverlayDraft.Polarity.ACTIVE_LOW, "", List.of()));
        rejectsGpio(() -> GpioOverlayDraft.generate(DTS, a.path(), 15, "user_led", GpioOverlayDraft.Direction.OUTPUT,
                GpioOverlayDraft.Polarity.ACTIVE_LOW, "/ { user_led: user_led {}; };", List.of()));
        rejectsGpio(() -> GpioOverlayDraft.generate(DTS, a.path(), 15, "different_name", GpioOverlayDraft.Direction.OUTPUT,
                GpioOverlayDraft.Polarity.ACTIVE_LOW, output, List.of()));
        rejectsGpio(() -> GpioOverlayDraft.generate(DTS.replace("gpioa: gpio_a: ", ""), a.path(), 15, "user_led", GpioOverlayDraft.Direction.OUTPUT,
                GpioOverlayDraft.Polarity.ACTIVE_LOW, "", List.of()));
        rejectsGpio(() -> GpioOverlayDraft.generate(DTS, a.path(), 40, "user_led", GpioOverlayDraft.Direction.OUTPUT,
                GpioOverlayDraft.Polarity.ACTIVE_LOW, "", List.of()));
        String disabled = DTS.replace("gpioa: gpio_a: gpio@400a0000 {", "gpioa: gpio_a: gpio@400a0000 {\nstatus = \"disabled\";").replace("status = \"okay\";", "");
        rejectsGpio(() -> GpioOverlayDraft.generate(disabled, a.path(), 15, "user_led", GpioOverlayDraft.Direction.OUTPUT,
                GpioOverlayDraft.Polarity.ACTIVE_LOW, "", List.of()));
        Path overlayApp = Files.createTempDirectory("gpio draft source ");
        try {
            Path overlay = overlayApp.resolve("app.overlay");
            Files.writeString(overlay, "/* retained existing source */\r\n");
            var preview = DevicetreeOverlayChanges.previewGenerated(overlayApp, overlay, original ->
                    GpioOverlayDraft.generate(DTS, a.path(), 15, "user_led", GpioOverlayDraft.Direction.OUTPUT,
                            GpioOverlayDraft.Polarity.ACTIVE_LOW, original, List.of()));
            check(Files.readString(overlay).equals("/* retained existing source */\r\n")
                    && preview.updated().startsWith(preview.original()) && preview.addition().contains("\r\n"),
                    "source preview is read-only and preserves prior content/newlines");
            Path backup = DevicetreeOverlayChanges.commit(overlayApp, preview);
            check(backup != null && Files.readString(backup).equals(preview.original())
                    && Files.readString(overlay).equals(preview.updated()), "confirmed source commit uses existing backup/atomic workflow");
            var changed = DevicetreeOverlayChanges.previewGenerated(overlayApp, overlay, original -> "/* second reviewed addition */\n");
            Files.writeString(overlay, "/* concurrent source edit */\n");
            try { DevicetreeOverlayChanges.commit(overlayApp, changed); throw new AssertionError("concurrent overlay overwritten"); }
            catch (java.io.IOException expected) { checks++; }
        } finally {
            try (var files = Files.walk(overlayApp)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        System.out.println("GpioUsageRegressionTests PASS: " + checks + " checks");
    }
}
