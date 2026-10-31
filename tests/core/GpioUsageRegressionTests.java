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
        System.out.println("GpioUsageRegressionTests PASS: " + checks + " checks");
    }
}
