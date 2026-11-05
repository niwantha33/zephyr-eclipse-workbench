// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.FocusedConfigurationModel;
import io.github.niwantha33.zew.core.PeripheralStartGuide;
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
        String parentDisabled = BUILT.replace("                    ranges;",
                "                    ranges;\\n                    status = \\"disabled\\";");
        var inherited = FocusedConfigurationModel.inspect("board/target", "debug",
                parentDisabled, "CONFIG_ADC=y", false);
        check(inherited.peripherals().stream().filter(r -> r.category().equals("ADC"))
                    .allMatch(r -> r.state() == FocusedConfigurationModel.State.DISABLED),
                "disabled parent vetoes enabled child");
        var noConsumer = FocusedConfigurationModel.inspect("board/target", "debug",
                BUILT.replace("io-channels = <0x22 0>;", "status = \\"okay\\";"),
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
        System.out.println("PASS focused three-section config: " + tests);
    }
}
