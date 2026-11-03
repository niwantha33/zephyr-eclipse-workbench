// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.BeginnerHardwareGuide;

public final class BeginnerHardwareGuideTests {
    private static int checks;
    private static void check(boolean pass, String reason) {
        if (!pass) throw new AssertionError(reason);
        checks++;
    }

    public static void main(String[] args) {
        var adc = BeginnerHardwareGuide.GOALS.get(1);
        var gpio = BeginnerHardwareGuide.GOALS.get(0);
        String generated = """
                /dts-v1/;
                / {
                    soc {
                        adc@4000 {
                            compatible = "ti,mspm0-adc";
                            status = "disabled";
                        };
                        gpio@5000 {
                            compatible = "ti,mspm0-gpio";
                            status = "okay";
                        };
                    };
                };
                """;
        var missingBuild = BeginnerHardwareGuide.inspect("", false, adc);
        check(missingBuild.described() == 0
                && missingBuild.headline().contains("build")
                && missingBuild.evidence().contains("No controller or pin has been inferred"),
                "a missing build is not a guessed controller");
        var adcFound = BeginnerHardwareGuide.inspect(generated, false, adc);
        check(adcFound.described() == 1 && adcFound.markedOkay() == 0
                && adcFound.nextAction().contains("not marked enabled")
                && adcFound.evidence().contains("/soc/adc@4000"),
                "disabled real ADC only described, not claimed usable");
        var gpioFound = BeginnerHardwareGuide.inspect(generated, false, gpio);
        check(gpioFound.described() == 1 && gpioFound.markedOkay() == 1
                && gpioFound.nextAction().contains("NOT a tested hardware connection"),
                "okay status is not proof of runtime use");
        var stale = BeginnerHardwareGuide.inspect(generated, true, gpio);
        check(stale.nextAction().contains("Build & validate")
                && stale.evidence().contains("outdated"),
                "stale source always requires reconfigure before guidance");
        var can = BeginnerHardwareGuide.GOALS.get(5);
        var noCan = BeginnerHardwareGuide.inspect(generated, false, can);
        check(noCan.described() == 0 && noCan.nextAction().contains("NOT proof"),
                "absence in DTS is never equated to absent silicon");
        check(BeginnerHardwareGuide.GOALS.stream().map(BeginnerHardwareGuide.Goal::category)
                .distinct().count() == 6, "simple goals map to separate actual categories");
        System.out.println("PASS beginner task-first hardware guidance: " + checks);
    }
}
