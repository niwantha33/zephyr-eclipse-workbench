// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.FocusedConfigurationModel;
import io.github.niwantha33.zew.core.PeripheralStartGuide;
import io.github.niwantha33.zew.core.PeripheralWorkflow;
import io.github.niwantha33.zew.core.HardwarePeripheralIndex;
import io.github.niwantha33.zew.core.DevicetreeHardware;
import java.util.Arrays;

public final class FocusedConfigurationModelTests {
    private static int tests;
    private static void check(boolean yes, String reason) {
        if (!yes) throw new AssertionError(reason);
        tests++;
    }
    private static final String BUILT = """
            /dts-v1/;
            / {
                #address-cells = <1>;
                #size-cells = <1>;
                memory@20000000 {
                    compatible = "mmio-sram";
                    reg = <0x20000000 0x8000>;
                    status = "okay";
                };
                soc {
                    #address-cells = <1>;
                    #size-cells = <1>;
                    ranges;
                    flash@0 {
                        compatible = "soc-nv-flash";
                        reg = <0x0 0x20000>;
                        status = "okay";
                    };
                    gpio@5000 {
                        compatible = "vendor,gpio";
                        status = "disabled";
                    };
                    adc@4000 {
                        compatible = "vendor,adc";
                        status = "okay";
                        phandle = <0x22>;
                        #io-channel-cells = <1>;
                        channel@0 {
                            reg = <0>;
                            zephyr,gain = "ADC_GAIN_1";
                            zephyr,reference = "ADC_REF_INTERNAL";
                            zephyr,acquisition-time = <10>;
                            zephyr,resolution = <12>;
                        };
                    };
                };
                zephyr,user {
                    io-channels = <0x22 0>;
                };
            };
            """;

    public static void main(String[] args) {
        var empty = FocusedConfigurationModel.inspect("board/target", "debug", "", "", false);
        check(empty.freshness() == FocusedConfigurationModel.Freshness.NO_BUILD
                && empty.flash().value().equals("Unknown")
                && empty.peripherals().stream().allMatch(
                        r -> r.state() == FocusedConfigurationModel.State.UNKNOWN),
                "no build: no peripheral or memory guessing");
        check(empty.issues().stream().anyMatch(i -> i.title().contains("missing")),
                "missing build has actionable issue");

        var report = FocusedConfigurationModel.inspect("board/target", "debug",
                BUILT, "CONFIG_ADC=y\nCONFIG_GPIO=y\n", false);
        check(report.flash().value().equals("128 KiB")
                && report.ram().value().equals("32 KiB"),
                "only exact decoded and mapped capacities, NOT memory consumption");
        var gpio = report.peripherals().stream().filter(r -> r.category().equals("GPIO"))
                .findFirst().orElseThrow();
        check(gpio.state() == FocusedConfigurationModel.State.DISABLED,
                "disabled node never called configured despite driver Kconfig");
        var adc = report.peripherals().stream().filter(r -> r.category().equals("ADC"))
                .findFirst().orElseThrow();
        check(adc.state() == FocusedConfigurationModel.State.CONFIGURED
                && adc.explanation().contains("Physical pins")
                && !adc.explanation().contains("actively used"),
                "built ADC wiring and driver evidence only, not physical/runtime claim");
        check(report.peripherals().stream().filter(r -> r.category().equals("CAN"))
                .allMatch(r -> r.state() == FocusedConfigurationModel.State.UNKNOWN),
                "missing CAN node is UNKNOWN, not silicon unsupported");
        var stale = FocusedConfigurationModel.inspect("board/target", "debug",
                BUILT, "CONFIG_ADC=y", true);
        check(stale.freshness() == FocusedConfigurationModel.Freshness.STALE_SNAPSHOT
                && stale.peripherals().stream().filter(r -> r.category().equals("ADC"))
                    .allMatch(r -> r.state() == FocusedConfigurationModel.State.UNKNOWN)
                && stale.flash().value().contains("stale"),
                "stale configuration never represented as current");
        String parentDisabled = BUILT.replace("ranges;",
                "ranges;\n                    status = \"disabled\";");
        var inherited = FocusedConfigurationModel.inspect("board/target", "debug",
                parentDisabled, "CONFIG_ADC=y", false);
        check(inherited.peripherals().stream().filter(r -> r.category().equals("ADC"))
                    .allMatch(r -> r.state() == FocusedConfigurationModel.State.DISABLED),
                "disabled parent vetoes enabled child");
        var noConsumer = FocusedConfigurationModel.inspect("board/target", "debug",
                BUILT.replace("io-channels = <0x22 0>;", "status = \"okay\";"),
                "CONFIG_ADC=y", false);
        check(noConsumer.peripherals().stream().filter(r -> r.category().equals("ADC"))
                .allMatch(r -> r.state() == FocusedConfigurationModel.State.SETUP_INCOMPLETE),
                "missing application consumer never configured");
        var ambiguous = FocusedConfigurationModel.inspect("board/target", "debug",
                BUILT.replace("io-channels = <0x22 0>;", "io-channels = <0x22 0>, <0x22 0>;"),
                "CONFIG_ADC=y", false);
        check(ambiguous.peripherals().stream().filter(r -> r.category().equals("ADC"))
                .allMatch(r -> r.state() == FocusedConfigurationModel.State.SETUP_INCOMPLETE),
                "conflicting/duplicate ADC consumer references block code");
        var guide = PeripheralStartGuide.inspect(BUILT, "ADC", "/soc/adc@4000",
                "CONFIG_ADC=y", false);
        check(FocusedConfigurationModel.canOfferAdcCode(guide, false, "CONFIG_ADC=y")
                && !FocusedConfigurationModel.canOfferAdcCode(guide, true, "CONFIG_ADC=y")
                && !FocusedConfigurationModel.canOfferAdcCode(guide, false, "CONFIG_ADC=n"),
                "code requires exact generated channel plus enabled driver and nonstale build");
        check(FocusedConfigurationModel.pinEvidence(guide).contains("Physical pad")
                && FocusedConfigurationModel.pinEvidence(null).contains("Unknown"),
                "logical ADC input cannot become a physical package pin");
        // All recognized hardware families have dedicated, non-ADC-specific
        // connection/settings/review guidance and are selectable in the UI.
        var full = PeripheralWorkflow.categories();
        check(full.size() == HardwarePeripheralIndex.FILTERS.size() - 1
                && full.containsAll(HardwarePeripheralIndex.FILTERS.stream()
                        .filter(k -> !HardwarePeripheralIndex.ALL.equals(k)).toList())
                && full.containsAll(java.util.List.of(
                        "ADC", "DAC", "GPIO", "UART", "I2C", "SPI", "I2S",
                        "PWM", "Timers", "DMA", "CAN", "USB", "RTC",
                        "Watchdog", "Ethernet", "Pinctrl", "Flash / Storage", "Other")),
                "every categorized Zephyr peripheral has a task-focused workflow");
        for (String kind : full) {
            var plan = PeripheralWorkflow.forCategory(kind);
            check(plan.steps().size() == 4
                    && plan.steps().get(0).equals("Controller")
                    && plan.steps().get(3).equals("Review")
                    && !plan.connectionTitle().isBlank()
                    && plan.connectionHelp().length() > 50
                    && plan.settingsHelp().length() > 50
                    && plan.reviewHelp().length() > 40,
                    "no empty/decorative workflow for " + kind);
        }
        check(PeripheralWorkflow.forCategory("Flash / Storage").connectionHelp().contains("partition")
                && PeripheralWorkflow.forCategory("I2C").connectionHelp().contains("pull-ups")
                && PeripheralWorkflow.forCategory("SPI").connectionHelp().contains("chip-select")
                && PeripheralWorkflow.forCategory("DMA").connectionHelp().contains("request")
                && PeripheralWorkflow.forCategory("PWM").connectionHelp().contains("#pwm-cells")
                && PeripheralWorkflow.forCategory("USB").connectionHelp().contains("VBUS"),
                "peripheral-specific workflows do not recycle ADC wiring advice");
        String[] nodeNames = {
            "gpio@100", "adc@200", "dac@300", "uart@400", "i2c@500", "spi@600",
            "i2s@700", "pwm@800", "timer@900", "dma@a00", "can@b00",
            "usb@c00", "rtc@d00", "watchdog@e00", "ethernet@f00",
            "pinctrl@1000", "flash@1200", "custom@1100"
        };
        var allDts = new StringBuilder("/dts-v1/;\n/ {\n soc {\n");
        for (String node : nodeNames) allDts.append(node)
                .append(" {\ncompatible = \"test,")
                .append(node.substring(0, node.indexOf('@')).replace("custom", "special"))
                .append("\";\nstatus = \"okay\";\n};\n");
        allDts.append("};\n};\n");
        var allNodes = DevicetreeHardware.inspect(allDts.toString());
        for (String kind : full) {
            check(allNodes.stream().anyMatch(d -> PeripheralWorkflow.selectable(d, kind)),
                    "real generated DTS node discoverable for " + kind);
        }
        var extended = FocusedConfigurationModel.inspect("board/target", "debug",
                allDts.toString(), "", false);
        check(extended.peripherals().size() == full.size()
                && extended.peripherals().stream().noneMatch(
                        r -> r.state() == FocusedConfigurationModel.State.CONFIGURED)
                && extended.peripherals().stream().allMatch(
                        r -> r.state() != FocusedConfigurationModel.State.UNSUPPORTED),
                "all hardware categories shown; DTS-only is never called configured/unsupported");
        var serialFlash = new DevicetreeHardware.Device(
                "/soc/spi@3000/serial-flash@0", "serial-flash@0", "",
                "jedec,spi-nor", "okay", "<0>", 3);
        var uart = new DevicetreeHardware.Device(
                "/soc/serial@4000", "serial@4000", "", "ns16550", "okay", "", 4);
        var spiBus = new DevicetreeHardware.Device(
                "/soc/spi@3000", "spi@3000", "", "vendor,spi-controller", "okay", "", 5);
        var ambiguousSerial = new DevicetreeHardware.Device(
                "/soc/serial-flash@1", "serial-flash@1", "", "vendor,unknown", "", "", 6);
        check(HardwarePeripheralIndex.category(serialFlash).equals("Flash / Storage")
                && !HardwarePeripheralIndex.category(serialFlash).equals("UART")
                && HardwarePeripheralIndex.category(uart).equals("UART")
                && HardwarePeripheralIndex.category(spiBus).equals("SPI")
                && HardwarePeripheralIndex.category(ambiguousSerial).equals("Other"),
                "serial-flash is storage not UART; real serial controller and SPI bus retained");
        check(PeripheralWorkflow.overviewCategories(java.util.List.of()).equals(
                        FocusedConfigurationModel.COMMON)
                && !PeripheralWorkflow.selectable(
                        new DevicetreeHardware.Device("/soc", "soc", "", "", "", "", 1),
                        "Other"),
                "missing build only lists common entrypoints; structural nodes do not invent peripherals");
        var structuralOnly = PeripheralStartGuide.inspect(BUILT, "Other", "",
                "CONFIG_ADC=y", false);
        check(structuralOnly.state() == PeripheralStartGuide.State.NOT_IN_DTS,
                "unclassified memory/flash nodes never become phantom Other peripherals");
        var spi = allNodes.stream().filter(d ->
                PeripheralWorkflow.selectable(d, "SPI")).findFirst().orElseThrow();
        check(PeripheralWorkflow.describeProperties(allDts.toString(), spi.path(), "SPI")
                        .contains("status =")
                && !PeripheralWorkflow.describeProperties("", spi.path(), "SPI")
                        .contains("status ="),
                "settings are generated-build observations, not templates masquerading as values");
        System.out.println("PASS focused three-section config: " + tests);
    }
}
