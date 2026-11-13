// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.dom.ast.*;
import org.eclipse.cdt.core.index.IndexFilter;
import org.eclipse.cdt.core.model.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.equinox.app.*;
import org.eclipse.ui.PlatformUI;
import io.github.niwantha33.zew.core.EditorSettings;

/** Test-only bundle: exercise the installed plug-in through real CDT APIs. */
public final class EditorSmoke implements IApplication {
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); System.out.println("PASS CDT: " + name); }
    private static Object studioField(Object view, Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(view);
    }
    private static void verifyStudioNavigation(Object view, Class<?> type, java.lang.reflect.Field snapshotField,
            Object original, org.eclipse.swt.widgets.Table features, org.eclipse.swt.widgets.Tree hardware,
            Path fixture) throws Exception {
        String dts = """
                /* Generated fixture */
                / {
                    soc {
                        adc@4000 {
                            compatible = "ti,mspm0-adc";
                            status = "disabled";
                            pinctrl-0 = <0x10>;
                            channel@0 {
                                reg = <0>;
                                zephyr,gain = "ADC_GAIN_1";
                                zephyr,reference = "ADC_REF_INTERNAL";
                                zephyr,resolution = <12>;
                            };
                        };
                        uart@5000 {
                            compatible = "ti,mspm0-uart";
                            status = "okay";
                        };
                        pin-controller@400a0000 {
                            compatible = "ti,mspm0-pinctrl";
                            gpio@400a0000 {
                                compatible = "ti,mspm0-gpio";
                                status = "okay";
                                reg = <0x400a0000 0x2000>;
                                gpio-controller;
                                #gpio-cells = <2>;
                                phandle = <0x2a>;
                                label = "GPIO_0";
                            };
                            gpio@400a2000 {
                                compatible = "ti,mspm0-gpio";
                                status = "okay";
                                reg = <0x400a2000 0x2000>;
                                gpio-controller;
                                #gpio-cells = <2>;
                                phandle = <0x2b>;
                            };
                            gpio-default {
                                pinmux = <0x23>;
                            };
                        };
                    };
                    pinctrl {
                        adc-default {
                            phandle = <0x10>;
                            group1 {
                                pinmux = <0x1234>;
                            };
                        };
                    };
                    leds {
                        compatible = "gpio-leds";
                        led_0 {
                            gpios = <0x2b 0x16 0x0>;
                            label = "Blue LED";
                        };
                    };
                };
                """;
        var data = new io.github.niwantha33.zew.core.ConfigurationStudioFiles.Snapshot(java.util.List.of(
                new io.github.niwantha33.zew.core.ConfigurationStudioFiles.Option("ADC", "n", null, 0),
                new io.github.niwantha33.zew.core.ConfigurationStudioFiles.Option("GPIO", "y", null, 0),
                new io.github.niwantha33.zew.core.ConfigurationStudioFiles.Option("LOG", "y", "y", 1)), dts, "Navigation fixture", null);
        var search = (org.eclipse.swt.widgets.Text) studioField(view, type, "search");
        var hardwareSearch = (org.eclipse.swt.widgets.Text) studioField(view, type, "hardwareSearch");
        var hardwareState = (org.eclipse.swt.widgets.Combo) studioField(view, type, "hardwareState");
        var source = (org.eclipse.swt.widgets.Text) studioField(view, type, "tree");
        var clear = type.getDeclaredMethod("clearFeatureFilters"); clear.setAccessible(true);
        var refresh = type.getDeclaredMethod("refreshHardware"); refresh.setAccessible(true);
        var reveal = type.getDeclaredMethod("revealHardwareSource"); reveal.setAccessible(true);
        var history = type.getDeclaredMethod("navigateHistory", int.class); history.setAccessible(true);
        var gpioSource = fixture.resolve("src/gpio_studio_source_smoke.c");
        Files.createDirectories(gpioSource.getParent());
        Files.writeString(gpioSource, """
                const struct device *gpio_dev = device_get_binding("GPIO_0");
                gpio_pin_configure(gpio_dev, 15, GPIO_OUTPUT_ACTIVE);
                const struct device *unknown = device_get_binding("UNKNOWN_GPIO");
                gpio_pin_configure(unknown, 12, GPIO_OUTPUT_ACTIVE);
                """);
        // This test injects a *synthetic generated* DTS. The workbench may
        // have a stale flag from the unrelated real test application; do not
        // let that flag override the controlled fixture's expected status.
        // Production continues to fail closed on stale generated outputs.
        var staleField = type.getDeclaredField("buildOutdated");
        staleField.setAccessible(true);
        boolean originalStale = staleField.getBoolean(view);
        try {
            snapshotField.set(view, data);
            staleField.setBoolean(view, false);
            source.setText(dts);
            var domains = (org.eclipse.swt.widgets.List) studioField(view, type, "peripheralCategories");
            domains.setSelection(1);
            domains.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
            search.setText("CONFIG_GPIO");
            check(features.getItemCount() == 1 && features.getItem(0).getText().contains("CONFIG_GPIO"),
                    "global CONFIG_ search finds settings outside the selected peripheral tab");
            clear.invoke(view);
            features.select(0); features.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
            features.select(1); features.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
            history.invoke(view, -1);
            check(features.getSelection()[0].getText().contains("CONFIG_ADC"), "Back returns to the previously inspected symbol");
            history.invoke(view, 1);
            check(features.getSelection()[0].getText().contains("CONFIG_GPIO"), "Forward restores the next inspected symbol");
            hardwareSearch.setText("mspm0");
            hardwareState.select(2);
            hardwareState.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
            check(hardware.getItemCount() == 1 && hardware.getItem(0).getText().equals("/")
                    && hardware.getItem(0).getItem(0).getText().equals("soc")
                    && hardware.getItem(0).getItem(0).getItem(0).getText().equals("adc@4000"),
                    "filtered Devicetree retains real root and bus ancestors");
            hardware.setSelection(hardware.getItem(0).getItem(0).getItem(0));
            reveal.invoke(view);
            check(source.getSelectionText().contains("adc@4000 {"), "Reveal in source selects the exact generated DTS node line");
            domains.select(1);
            domains.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
            var parametersAction = (org.eclipse.swt.widgets.Button)
                    studioField(view, type, "peripheralParameters");
            parametersAction.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
            var parameterTable = (org.eclipse.swt.widgets.Table) studioField(view, type, "hardwareParameters");
            var gain = java.util.Arrays.stream(parameterTable.getItems())
                    .filter(row -> row.getText(0).contains("zephyr,gain")).findFirst().orElseThrow();
            check(gain.getText(1).equals("\"ADC_GAIN_1\"") && gain.getText(2).endsWith("/channel@0"),
                    "ADC Parameters & pins opens real channel gain rather than generic feature help");
            var chip = studioField(view, type, "chipPins");
            check(((Integer) chip.getClass().getMethod("routeCount").invoke(chip)) == 1,
                    "native chip canvas contains the selected controller's resolved pinctrl route");
            var plannerCanvas = (org.eclipse.swt.widgets.Canvas) chip;
            var plannerScroll = (org.eclipse.swt.custom.ScrolledComposite) plannerCanvas.getParent();
            check(plannerCanvas.getToolTipText() == null && plannerScroll.getMinWidth() <= 540
                    && plannerScroll.getMinHeight() <= 250,
                    "small-route planner avoids obscuring tooltips and needless horizontal/vertical scrolling");
            // The redesigned native Hardware workspace must have real
            // peripheral navigation, planner modes and a separate evidence dock.
            var grouped = (org.eclipse.swt.widgets.Tree) studioField(view, type, "hardwarePeripheralTree");
            var groupedDescription = new java.util.ArrayList<String>();
            boolean adcControllerInGroup = false, adcChannelInGroup = false;
            for (var group : grouped.getItems()) {
                groupedDescription.add(group.getText() + ":" + group.getItemCount());
                for (var controller : group.getItems()) {
                    groupedDescription.add("  " + controller.getText() + ":" + controller.getItemCount());
                    if (controller.getText().startsWith("adc@4000")) {
                        adcControllerInGroup = true;
                        for (var child : controller.getItems()) {
                            groupedDescription.add("    " + child.getText());
                            if (child.getText().startsWith("channel@0")) adcChannelInGroup = true;
                        }
                    }
                }
            }
            check(grouped.getItemCount() == 1
                    && grouped.getItem(0).getText().startsWith("ADC")
                    && grouped.getItem(0).getItemCount() == 1
                    && adcControllerInGroup && adcChannelInGroup,
                    "ADC peripheral-first tree retains controller and channel: " + groupedDescription);
            var dock = (org.eclipse.swt.widgets.TabFolder) studioField(view, type, "hardwareDetails");
            check(dock.getItemCount() == 8
                    && dock.getItem(5).getText().equals("Start peripheral")
                    && dock.getItem(0).getText().startsWith("Changes")
                    && dock.getItem(1).getText().startsWith("Validation")
                    && dock.getItem(2).getText().startsWith("Generated DTS")
                    && dock.getItem(3).getText().equals("GPIO Evidence")
                    && dock.getItem(4).getText().equals("GPIO Example C")
                    && dock.getItem(6).getText().equals("DTS / DTSI Sources")
                    && dock.getItem(7).getText().equals("Node → Source → Overlay"),
                    "native Studio distinguishes GPIO evidence from runtime usage");
            var sourceList = (org.eclipse.swt.widgets.Table) studioField(view, type, "sourceFileList");
            var sourceContent = (org.eclipse.swt.widgets.Text) studioField(view, type, "sourceFileContent");
            var sourceNotice = (org.eclipse.swt.widgets.Label) studioField(view, type, "sourceFileNotice");
            check(sourceList.getColumnCount() == 3 && !sourceContent.getEditable()
                    && !sourceNotice.getText().isBlank(),
                    "native cross-vendor source browser is visible and vendor DTS/DTSI cannot be edited");
            var validation = (org.eclipse.swt.widgets.Text) studioField(view, type, "hardwareValidation");
            check(validation.getText().contains("PHYSICAL PINOUT: NOT VERIFIED"),
                    "physical package map is never invented from pinmux cells");
            var preview = (org.eclipse.swt.widgets.Text) studioField(view, type, "hardwarePreview");
            check(!preview.getEditable() && preview.getText().contains("No proposed changes"),
                    "hardware overlay draft starts read-only with no implicit edits");
            var changePage = (org.eclipse.swt.widgets.Composite) dock.getItem(0).getControl();
            var changeActions = (org.eclipse.swt.widgets.Composite) changePage.getChildren()[0];
            check(Arrays.stream(changeActions.getChildren())
                    .anyMatch(child -> child instanceof org.eclipse.swt.widgets.Button button
                            && button.getText().equals("Test unsaved draft with CMake…"))
                    && studioField(view, type, "reviewedGuidedDraft") == null,
                    "installed SWT exposes opt-in unsaved draft preflight but creates no automatic source intent");
            check(Arrays.stream(changeActions.getChildren())
                    .anyMatch(child -> child instanceof org.eclipse.swt.widgets.Button button
                            && button.getText().equals("Save verified draft with backup…"))
                    && studioField(view, type, "verifiedGuidedPreflight") == null,
                    "installed SWT offers reviewed backed-up source save without auto-committing");

            var mode = chip.getClass().getDeclaredClasses()[0];
            var chooseMode = chip.getClass().getMethod("setMode", mode);
            var packageMode = java.util.Arrays.stream(mode.getEnumConstants())
                    .filter(value -> value.toString().equals("PACKAGE")).findFirst().orElseThrow();
            chooseMode.invoke(chip, packageMode);
            check(chip.getClass().getMethod("mode").invoke(chip).toString().equals("PACKAGE"),
                    "unverified package placeholder is a selectable real SWT planner mode");
            var signalsMode = java.util.Arrays.stream(mode.getEnumConstants())
                    .filter(value -> value.toString().equals("SIGNALS")).findFirst().orElseThrow();
            chooseMode.invoke(chip, signalsMode);
            parameterTable.setSelection(gain);
            var revealParameter = type.getDeclaredMethod("revealParameterSource"); revealParameter.setAccessible(true);
            revealParameter.invoke(view);
            check(source.getSelectionText().contains("zephyr,gain"), "ADC parameter reveals its exact DTS declaration");
            var kind = (org.eclipse.swt.widgets.Combo) studioField(view, type, "hardwareKind");
            check(kind.indexOf("ADC") >= 0 && kind.indexOf("Other") >= 0,
                    "installed hardware explorer exposes controller category navigation");
            hardwareState.select(0);
            hardwareSearch.setText("");
            // Silicon catalogue is source-qualified and isolated from generated DTS.
            var siliconSettingsField = type.getDeclaredField("inspectionSettings");
            siliconSettingsField.setAccessible(true);
            Object previousSiliconSettings = siliconSettingsField.get(view);
            try {
                var exactSiliconSettings = new java.util.Properties();
                exactSiliconSettings.setProperty("board", "lp_mspm0g3507/mspm0g3507");
                siliconSettingsField.set(view, exactSiliconSettings);
                refresh.invoke(view);
                var siliconTable = (org.eclipse.swt.widgets.Table) studioField(view, type, "vendorFeatures");
                var siliconMessage = (org.eclipse.swt.widgets.Label) studioField(view, type, "vendorFeatureStatus");
                check(siliconTable.getItemCount() >= 10
                        && siliconMessage.getText().contains("Official TI specification")
                        && java.util.Arrays.stream(siliconTable.getItems()).anyMatch(row ->
                            row.getText(0).equals("ADC")
                                && row.getText(1).startsWith("2 ")
                                && row.getText(2).equals("1 DTS nodes"))
                        && java.util.Arrays.stream(siliconTable.getItems()).anyMatch(row ->
                            row.getText(0).equals("I2C")
                                && row.getText(1).startsWith("2 ")
                                && row.getText(2).equals("0 DTS nodes")),
                        "installed silicon tab distinguishes official TI ADC/I2C silicon from actual DTS nodes");
                var i2cSilicon = java.util.Arrays.stream(siliconTable.getItems())
                        .filter(row -> row.getText(0).equals("I2C")).findFirst().orElseThrow();
                siliconTable.setSelection(i2cSilicon);
                siliconTable.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
                check(((org.eclipse.swt.widgets.Text) studioField(view, type, "vendorFeatureDescription"))
                        .getText().contains("do not invent an overlay"),
                        "missing Zephyr I2C binding does not become a fabricated configuration");
            } finally {
                siliconSettingsField.set(view, previousSiliconSettings);
                refresh.invoke(view);
            }
            kind.select(kind.indexOf("ADC"));
            kind.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
            // Native workbench must guide from the real controller and never
            // synthesize ADC code for a disabled / missing controller.
            var startGuideMethod = type.getDeclaredMethod("openStartGuide", String.class);
            startGuideMethod.setAccessible(true);
            startGuideMethod.invoke(view, "ADC");
            var guideTabs = (org.eclipse.swt.widgets.TabFolder) studioField(view, type, "hardwareDetails");
            var guideStatus = (org.eclipse.swt.widgets.Label) studioField(view, type, "startGuideStatus");
            var guideSteps = (org.eclipse.swt.widgets.Text) studioField(view, type, "startGuideSteps");
            var guideCode = (org.eclipse.swt.widgets.Text) studioField(view, type, "startGuideC");
            var copyCode = (org.eclipse.swt.widgets.Button) studioField(view, type, "copyStartCode");
            check(guideTabs.getItem(5).getText().equals("Start peripheral")
                    && guideTabs.getSelectionIndex() == 5
                    && guideStatus.getText().contains("DTS_DISABLED")
                    && guideSteps.getText().contains("adc@4000")
                    && guideSteps.getText().contains("CONFIG_ADC")
                    && !copyCode.getEnabled()
                    && !guideCode.getText().contains("int main(void)"),
                    "installed ADC starter locates real disabled controller but does not offer fake working code"
                    + " [tab=" + guideTabs.getSelectionIndex()
                    + ", state=" + guideStatus.getText()
                    + ", steps=" + guideSteps.getText().substring(0, Math.min(650, guideSteps.getText().length()))
                    + ", copy=" + copyCode.getEnabled()
                    + ", generatedCode=" + guideCode.getText().substring(0, Math.min(160, guideCode.getText().length()))
                    + ", selectedController=" + (hardware.getSelectionCount() == 1 ? hardware.getSelection()[0].getText() : "(none)")
                    + "]");
            startGuideMethod.invoke(view, "I2C");
            check(guideStatus.getText().contains("NOT_IN_DTS")
                    && !copyCode.getEnabled()
                    && guideSteps.getText().contains("upstream-compatible DTS"),
                    "installed starter explains missing Zephyr I2C node across SoCs without guessing pinmux");
            startGuideMethod.invoke(view, "UART");
            check(guideStatus.getText().contains("INSPECT_BINDING")
                    && guideSteps.getText().contains("/soc/uart@5000"),
                    "installed UART path follows compiled controller rather than ADC-specific assumptions");
            check(hardware.getItemCount() > 0
                    && hardware.getItem(0).getItem(0).getItem(0).getText().equals("adc@4000")
                    && hardware.getItem(0).getItem(0).getItem(0).getItemCount() == 1
                    && hardware.getItem(0).getItem(0).getItem(0).getItem(0).getText().equals("channel@0"),
                    "ADC category keeps child channels available for configuration");
            var peripheralCoverage = (org.eclipse.swt.widgets.Label) studioField(view, type, "hardwareSummary");
            var peripheralDescription = (org.eclipse.swt.widgets.Text) studioField(view, type, "hardwareDescription");
            kind.select(kind.indexOf("I2C"));
            kind.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
            check(grouped.getItemCount() == 1 && grouped.getItem(0).getText().equals("I2C (0)")
                    && peripheralDescription.getText().contains("NOT DESCRIBED IN SELECTED BUILD DTS")
                    && peripheralDescription.getText().contains("does not mean the MCU lacks")
                    && peripheralCoverage.getText().contains("NOT DESCRIBED"),
                    "missing I2C node is explicitly distinguished from silicon capability");
            kind.select(kind.indexOf("UART"));
            kind.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
            check(grouped.getItemCount() == 1
                    && grouped.getItem(0).getText().equals("UART (1)")
                    && grouped.getItem(0).getItem(0).getText().contains("[DTS okay]")
                    && peripheralCoverage.getText().contains("UART in generated DTS: 1"),
                    "real UART is enumerated and status-sourced from built DTS");
            kind.select(kind.indexOf("ADC"));
            hardwareState.select(hardwareState.indexOf("Okay/default"));
            kind.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
            check(peripheralCoverage.getText().contains("filters hide"),
                    "disabled ADC is not mislabeled as an absent silicon ADC");
            hardwareState.select(0);
            kind.select(kind.indexOf("GPIO"));
            kind.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
            check(grouped.getItemCount() == 1
                    && grouped.getItem(0).getText().startsWith("GPIO (2)")
                    && grouped.getItem(0).getItemCount() == 2,
                    "GPIO explorer discovers both compatible-bearing MSPM0 controllers nested under pinctrl");
            var gpioController = grouped.getItem(0).getItem(0);
            check(gpioController.getText().startsWith("gpio@400a0000"),
                    "GPIO A is shown as a real controller, not a pinctrl group");
            grouped.setSelection(gpioController);
            grouped.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
            check(java.util.Arrays.stream(parameterTable.getItems())
                    .anyMatch(item -> item.getText(0).contains("gpio-controller"))
                    && java.util.Arrays.stream(parameterTable.getItems())
                    .anyMatch(item -> item.getText(0).contains("#gpio-cells")),
                    "selecting GPIO exposes compiled controller parameters rather than an empty inspector");
            check(!((org.eclipse.swt.widgets.Button) studioField(view, type, "showDtsGpioDeclarations")).getSelection()
                    && !((org.eclipse.swt.widgets.Button) studioField(view, type, "showUnverifiedSourcePatterns")).getSelection()
                    && !(Boolean) chip.getClass().getMethod("showingGpioDeclarations").invoke(chip)
                    && !(Boolean) chip.getClass().getMethod("showingSourcePatterns").invoke(chip),
                    "no board DTS name or uncompiled C pattern is shown as GPIO use by default");
            check(validation.getText().contains("gpio@400a0000"),
                    "GPIO selection updates the evidence dock to the actual built controller");
            check(((Integer) chip.getClass().getMethod("gpioBitPositions").invoke(chip)) == 32
                    && ((Integer) chip.getClass().getMethod("gpioConsumerCount").invoke(chip)) == 0
                    && ((Integer) chip.getClass().getMethod("sourceCandidateCount").invoke(chip)) == 1,
                    "GPIO A has one separately identified C source candidate at bit 15");
            check(validation.getText().contains("gpio_studio_source_smoke.c")
                    && validation.getText().contains("UNRESOLVED DEVICE NAME")
                    && validation.getText().contains("15, GPIO_OUTPUT_ACTIVE"),
                    "evidence shows C-call provenance and unknown device lookup without guessing its bank");
            var gpioUsage = (org.eclipse.swt.widgets.Table) studioField(view, type, "gpioUsageTable");
            var gpioUsageSummary = (org.eclipse.swt.widgets.Label) studioField(view, type, "gpioUsageSummary");
            check(dock.getSelectionIndex() == 3
                    && gpioUsage.getItemCount() == 3
                    && gpioUsageSummary.getText().contains("GPIO RUNTIME USE: UNKNOWN")
                    && gpioUsageSummary.getText().contains("Board DTS declarations: 1")
                    && gpioUsageSummary.getText().contains("Unresolved source bindings: 1"),
                    "GPIO usage overview immediately lists all banks and unresolved source code");
            check(java.util.Arrays.stream(gpioUsage.getItems()).anyMatch(row ->
                        row.getText(0).equals("PB22") && row.getText(1).equals("Board DTS only"))
                    && java.util.Arrays.stream(gpioUsage.getItems()).anyMatch(row ->
                        row.getText(0).equals("PA15") && row.getText(1).equals("Saved C pattern"))
                    && java.util.Arrays.stream(gpioUsage.getItems()).anyMatch(row ->
                        row.getText(0).equals("Unresolved bit 12")
                            && row.getText(1).equals("Unresolved source")),
                    "cross-bank inventory separates PB22 DTS, PA15 C candidate, and unassigned C bit 12");
            captureNativeStudioScreenshot("gpio-used-overview.png");
            var unassigned = java.util.Arrays.stream(gpioUsage.getItems())
                    .filter(row -> row.getText(0).equals("Unresolved bit 12")).findFirst().orElseThrow();
            gpioUsage.setSelection(unassigned);
            gpioUsage.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
            check(((org.eclipse.swt.widgets.Text) studioField(view, type, "hardwareInspector"))
                    .getText().contains("Not allocated to any bank"),
                    "clicking unresolved C source cannot invent a GPIO bank");
            check(chip.getClass().getMethod("gpioDisplayLabel", int.class) != null,
                    "GPIO planner exposes actual bit plus compiled consumer display labels");
            var blueLedUsage = java.util.Arrays.stream(gpioUsage.getItems())
                    .filter(row -> row.getText(0).equals("PB22")).findFirst().orElseThrow();
            gpioUsage.setSelection(blueLedUsage);
            gpioUsage.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
            check(chip.getClass().getMethod("gpioDisplayLabel", int.class).invoke(chip, 22).equals("PB22"),
                    "board default led0 must NOT appear on logical GPIO cell by default");
            var showBoardDts = (org.eclipse.swt.widgets.Button) studioField(view, type, "showDtsGpioDeclarations");
            showBoardDts.setSelection(true);
            showBoardDts.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
            check(chip.getClass().getMethod("gpioDisplayLabel", int.class).invoke(chip, 22)
                    .toString().startsWith("PB22 / ")
                    && (Boolean) chip.getClass().getMethod("showingGpioDeclarations").invoke(chip),
                    "board declarations may be inspected explicitly but are never marked occupied");
            showBoardDts.setSelection(false);
            showBoardDts.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
            check(chip.getClass().getMethod("gpioDisplayLabel", int.class).invoke(chip, 22).equals("PB22"),
                    "turning board DTS layer off removes led0 without changing source or hardware");
            check(((Integer) chip.getClass().getMethod("gpioConsumerCount").invoke(chip)) == 1
                    && dock.getSelectionIndex() == 3,
                    "selecting PB22 evidence navigates to GPIO B without losing the Usage tab");
            var gpioB = grouped.getItem(0).getItem(1);
            grouped.setSelection(gpioB);
            grouped.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
            check(((Integer) chip.getClass().getMethod("gpioBitPositions").invoke(chip)) == 32
                    && ((Integer) chip.getClass().getMethod("sourceCandidateCount").invoke(chip)) == 0
                    && ((Integer) chip.getClass().getMethod("gpioConsumerCount").invoke(chip)) == 1
                    && validation.getText().contains("Board DTS-only declarations (NOT used pins): 1")
                    && java.util.Arrays.stream(parameterTable.getItems()).anyMatch(
                            item -> item.getText(0).contains("gpios") && item.getText(2).contains("/leds/led_0")),
                    "GPIO B visual bank resolves the compiled Blue LED GPIO reference");
            ((org.eclipse.swt.widgets.TabFolder) studioField(view, type, "tabs")).setSelection(1);
            dock.setSelection(1);
            captureNativeStudioScreenshot("gpio-bank-evidence.png");
            grouped.setSelection(gpioController);
            grouped.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
            dock.setSelection(1);
            captureNativeStudioScreenshot("gpio-source-candidate.png");
            kind.select(kind.indexOf("Other"));
            kind.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
            var soc = hardware.getItem(0).getItem(0);
            check(java.util.Arrays.stream(soc.getItems()).noneMatch(
                    node -> node.getText().equals("uart@5000")),
                    "Other category never shows an unrelated UART via the generic SoC container");
            kind.select(0);
            kind.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
            hardwareSearch.setText("no-such-controller");
            check(hardware.getItemCount() == 0, "hardware search has an honest empty state");
            var guidance = (org.eclipse.swt.widgets.Label) studioField(view, type, "parameterTitle");
            check(guidance.getText().contains("No peripheral matches this filter")
                    && guidance.getText().contains("clear the search")
                    && !guidance.getText().contains("No generated Devicetree"),
                    "search-only empty state explains filter rather than pretending the build is missing");
            var cancel = new org.eclipse.swt.widgets.Event(); cancel.detail = org.eclipse.swt.SWT.ICON_CANCEL;
            hardwareSearch.notifyListeners(org.eclipse.swt.SWT.Selection, cancel);
            check(hardwareSearch.getText().isEmpty() && hardware.getItemCount() > 0,
                    "native search clear icon restores the filtered hardware hierarchy");
            var studioTabs = (org.eclipse.swt.widgets.TabFolder) studioField(view, type, "tabs");
            studioTabs.setSelection(2);
            dock.setSelection(1);
            captureNativeStudioScreenshot("visual-hardware-studio-built-fixture.png");

            // Verify the installed SWT source browser with two real input files
            // from a disposable, vendor-neutral build dependency fixture.
            Path sourceRoot = Files.createTempDirectory("zew-ui-dts-source-");
            var buildField = type.getDeclaredField("inspectionBuild");
            buildField.setAccessible(true);
            Object previousBuild = buildField.get(view);
            var refreshSources = type.getDeclaredMethod("refreshSourceBrowser");
            refreshSources.setAccessible(true);
            try {
                Path boardDts = sourceRoot.resolve("boards/fixture/board.dts");
                Path socDtsi = sourceRoot.resolve("dts/vendor/soc.dtsi");
                Path generated = sourceRoot.resolve("build/zephyr");
                Files.createDirectories(boardDts.getParent());
                Files.createDirectories(socDtsi.getParent());
                Files.createDirectories(generated);
                Files.writeString(boardDts, "/dts-v1/;\n/ { model = \"CI board source\"; };\n");
                Files.writeString(socDtsi, "/ { soc { compatible = \"fixture,controller\"; }; };\n");
                Files.writeString(generated.resolve("zephyr.dts.d"),
                        generated.resolve("zephyr.dts.pre") + ": "
                        + boardDts.toString().replace(" ", "\\ ") + " "
                        + socDtsi.toString().replace(" ", "\\ ") + "\n");
                buildField.set(view, sourceRoot.resolve("build"));
                refreshSources.invoke(view);
                // The browser may also include an existing app.overlay as a
                // source-only candidate; it must not be counted as a build input.
                // Verify the exact dependency-backed rows by identity rather
                // than assuming there can only ever be two visible sources.
                boolean foundBoardDts = false;
                int socDtsiRow = -1;
                var discoveredSources = new java.util.ArrayList<String>();
                for (int row = 0; row < sourceList.getItemCount(); row++) {
                    var entry = sourceList.getItem(row);
                    discoveredSources.add(entry.getText(0) + " / " + entry.getText(1)
                            + " / " + entry.getText(2));
                    if (entry.getText(0).equals("board.dts")
                            && entry.getText(1).equals("Board / shield DTS")
                            && entry.getText(2).equals("Build dependency"))
                        foundBoardDts = true;
                    if (entry.getText(0).equals("soc.dtsi")
                            && entry.getText(1).equals("SoC / architecture DTSI")
                            && entry.getText(2).equals("Build dependency"))
                        socDtsiRow = row;
                }
                check(foundBoardDts && socDtsiRow >= 0,
                        "installed source browser lists exact board and SoC DTS/DTSI build dependencies"
                        + " [rows=" + discoveredSources
                        + ", notice=" + sourceNotice.getText() + "]");
                sourceList.setSelection(socDtsiRow);
                sourceList.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
                check(sourceContent.getText().contains("fixture,controller")
                        && !sourceContent.getEditable(),
                        "installed native source browser reveals exact read-only SoC DTSI content");
                studioTabs.setSelection(2);
                dock.setSelection(6);
                dock.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
                // Process the same pending SWT resize/paint cycle as a user
                // interacting with a live workbench. This must be independent
                // of optional screenshot capture (absent on main publishing).
                settleNativeStudioLayout();
                var sourceDock = (org.eclipse.swt.custom.SashForm) dock.getParent();
                check(sourceDock.getMaximizedControl() == dock
                        && dock.getSelectionIndex() == 6
                        && sourceContent.isVisible()
                        && sourceContent.getBounds().height >= 80,
                        "DTS/DTSI browser is actually visible, not clipped to an empty tab strip"
                        + " (maximized=" + (sourceDock.getMaximizedControl() == dock)
                        + ", selected=" + dock.getSelectionIndex()
                        + ", visible=" + sourceContent.isVisible()
                        + ", contentHeight=" + sourceContent.getBounds().height
                        + ", sourcePaneHeight=" + sourceContent.getParent().getBounds().height
                        + ", tabHeight=" + dock.getBounds().height
                        + ", verticalHeight=" + sourceDock.getBounds().height + ")");
                captureNativeStudioScreenshot("devicetree-dtsi-source-browser.png");
                var traceButton = type.getDeclaredMethod("showSourceTrace");
                traceButton.setAccessible(true);
                traceButton.invoke(view);
                settleNativeStudioLayout();
                var traceTable = (org.eclipse.swt.widgets.Table) studioField(view, type, "sourceTraceMatches");
                var traceSource = (org.eclipse.swt.widgets.Text) studioField(view, type, "sourceTraceContent");
                var traceBuilt = (org.eclipse.swt.widgets.Text) studioField(view, type, "sourceTraceComparison");
                check(dock.getSelectionIndex() == 7
                        && !traceSource.getEditable() && !traceBuilt.getEditable()
                        && traceBuilt.getText().contains("BUILT / GENERATED")
                        && traceSource.isVisible(),
                        "integrated native source trace is visible, built values read-only and source safe");
                captureNativeStudioScreenshot("devicetree-integrated-source-trace.png");
                String screenshots = System.getenv("ZEW_SCREENSHOT_DIR");
                if (screenshots != null && !screenshots.isBlank()) {
                    check(!java.util.Arrays.equals(
                                    Files.readAllBytes(Path.of(screenshots).resolve("devicetree-dtsi-source-browser.png")),
                                    Files.readAllBytes(Path.of(screenshots).resolve("gpio-used-overview.png"))),
                            "SWT screenshot evidence actually reflects different Hardware views");
                }
            } finally {
                dock.setSelection(1);
                dock.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
                buildField.set(view, previousBuild);
                refreshSources.invoke(view);
                try (var cleanup = Files.walk(sourceRoot)) {
                    for (Path file : cleanup.sorted(java.util.Comparator.reverseOrder()).toList())
                        Files.deleteIfExists(file);
                }
            }

        } finally {
            Files.deleteIfExists(gpioSource);
            snapshotField.set(view, original);
            staleField.setBoolean(view, originalStale);
            clear.invoke(view);
            hardwareSearch.setText(""); hardwareState.select(0);
            source.setText(((io.github.niwantha33.zew.core.ConfigurationStudioFiles.Snapshot) original).devicetree());
            refresh.invoke(view);
        }
    }
    /** Capture a real installed-workbench screen, not a mockup or generated illustration. */
    private static void captureNativeStudioScreenshot() throws Exception {
        captureNativeStudioScreenshot("configuration-studio-source-only.png");
    }
    /**
     * A smoke check changes TabFolder selection from syncExec. SWT does not
     * allocate all previously hidden tab contents until the workbench event
     * queue receives its resize/paint events. Flush that UI cycle explicitly,
     * regardless of screenshot configuration, before inspecting real bounds.
     */
    /** Trace actual SWT parent sizes to distinguish an unlaid-out view from a child layout bug. */
    private static String swtBoundsChain(org.eclipse.swt.widgets.Control child) {
        var chain = new StringBuilder();
        org.eclipse.swt.widgets.Control current = child;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (depth != 0) chain.append(" <- ");
            chain.append(current.getClass().getSimpleName())
                    .append("[").append(current.getBounds())
                    .append(";visible=").append(current.isVisible()).append("]");
            current = current.getParent();
        }
        return chain.toString();
    }

    private static void settleNativeStudioLayout() {
        var window = org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) throw new IllegalStateException("No active native Eclipse window to inspect.");
        var shell = window.getShell();
        shell.layout(true, true);
        shell.redraw();
        shell.update();
        for (int flush = 0; flush < 128 && shell.getDisplay().readAndDispatch(); flush++) {
            shell.update();
        }
        shell.layout(true, true);
        shell.update();
    }

    /**
     * These are screenshots of the REAL installed Eclipse SWT view, rendered
     * with explicitly marked CI fixture evidence — NOT physical hardware.
     */
    private static void verifyStudioProblemMarkers() throws Exception {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("zew-studio-marker-fixture");
        project.create(null); project.open(null);
        try {
            Path app = project.getLocation().toFile().toPath();
            Files.writeString(app.resolve("prj.conf"), "# recorded fixture\nCONFIG_ADC=y\n");
            Path build = app.resolve("build"); Files.createDirectories(build.resolve("zephyr"));
            Files.writeString(build.resolve("zephyr/.config"), "# CONFIG_ADC is not set\n");
            project.refreshLocal(IResource.DEPTH_INFINITE, null);
            IMarker unrelated = project.createMarker(IMarker.PROBLEM);
            var report = io.github.niwantha33.zew.core.ConfigurationStudioDiagnostics.inspect(app, build);
            var bundle = org.eclipse.core.runtime.Platform.getBundle("io.github.niwantha33.zew.ui");
            var publisher = bundle.loadClass("io.github.niwantha33.zew.ui.ConfigurationStudioProblems");
            var replace = publisher.getMethod("replace", IProject.class,
                    io.github.niwantha33.zew.core.ConfigurationStudioDiagnostics.Report.class,
                    org.eclipse.core.runtime.IProgressMonitor.class);
            replace.invoke(null, project, report, new org.eclipse.core.runtime.NullProgressMonitor());
            var markers = project.findMarkers("io.github.niwantha33.zew.ui.studioProblem", false, IResource.DEPTH_INFINITE);
            check(markers.length == 1 && markers[0].getResource().equals(project.getFile("prj.conf"))
                    && markers[0].getAttribute(IMarker.LINE_NUMBER, 0) == 2,
                    "Studio Problems navigate only to the exact recorded source line");
            check(unrelated.exists(), "Studio source diagnostics preserve unrelated Problems");
            Files.writeString(app.resolve("prj.conf"), "CONFIG_ADC=n\n");
            replace.invoke(null, project, report, new org.eclipse.core.runtime.NullProgressMonitor());
            markers = project.findMarkers("io.github.niwantha33.zew.ui.studioProblem", false, IResource.DEPTH_INFINITE);
            check(markers.length == 1 && markers[0].getResource().equals(project)
                    && markers[0].getAttribute(IMarker.LINE_NUMBER, 0) == 0,
                    "Changed source evidence cannot publish a guessed navigation line");
        } finally { project.delete(true, true, null); }
    }

    private static void verifyThreeSectionStudio(IProject project) throws Exception {
        var win = org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        var view = win.getActivePage().showView(
                "io.github.niwantha33.zew.ui.configurationHome");
        var cls = view.getClass();
        cls.getMethod("displayProject", IProject.class).invoke(view, project);
        var tabs = (org.eclipse.swt.widgets.TabFolder) studioField(view, cls, "primary");
        var initialInformation = (org.eclipse.swt.widgets.Label)
                studioField(view, cls, "overviewInfo");
        check(!initialInformation.getText().isBlank(),
                "Configuration Studio opens with a useful explicit state, never a blank screen");
        check(tabs.getItemCount() == 3
                && tabs.getItem(0).getText().equals("Overview")
                && tabs.getItem(1).getText().equals("Hardware")
                && tabs.getItem(2).getText().equals("Build & System")
                && tabs.getSelectionIndex() == 0,
                "real installed Configuration Studio has EXACTLY three primary sections");
        // The no-project launch must show a useful empty state, rather than
        // dismissing the command or rendering the legacy blank Start Here.
        cls.getMethod("displayMissingProject").invoke(view);
        var noProjectHeader = (org.eclipse.swt.widgets.Label) studioField(view, cls, "header");
        var noProjectMessage = (org.eclipse.swt.widgets.Label) studioField(view, cls, "overviewInfo");
        var noProjectTable = (org.eclipse.swt.widgets.Table) studioField(view, cls, "peripheralRows");
        check(noProjectHeader.getText().contains("No Zephyr project selected")
                && noProjectMessage.getText().contains("Project Explorer")
                && noProjectTable.getItemCount() == 0,
                "no project opens a nonblank, actionable Eclipse Overview");
        cls.getMethod("openSection", String.class).invoke(view, "hardware");
        check(tabs.getSelectionIndex() == 1,
                "overlay context opens Hardware in the same three-tab view");
        cls.getMethod("openSection", String.class).invoke(view, "system");
        check(tabs.getSelectionIndex() == 2,
                "prj.conf context opens Build & System in the same three-tab view");
        cls.getMethod("displayProject", IProject.class).invoke(view, project);

        // Cancel asynchronous project refresh before installing a controlled
        // fixture, to avoid a race between the smoke and actual files.
        var generation = (java.util.concurrent.atomic.AtomicInteger)
                studioField(view, cls, "generation");
        generation.incrementAndGet();
        var job = (org.eclipse.core.runtime.jobs.Job) studioField(view, cls, "refreshJob");
        if (job != null) job.cancel();

        String dts = """
                /dts-v1/;
                / {
                    #address-cells = <1>;
                    #size-cells = <1>;
                    memory@20000000 {
                        compatible = "mmio-sram";
                        reg = <0x20000000 0x8000>;
                    };
                    soc {
                        #address-cells = <1>;
                        #size-cells = <1>;
                        ranges;
                        flash@0 {
                            compatible = "soc-nv-flash";
                            reg = <0x0 0x20000>;
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
        String config = "CONFIG_ADC=y\nCONFIG_GPIO=y\n";
        var overview = io.github.niwantha33.zew.core.FocusedConfigurationModel.inspect(
                "lp_mspm0g3507/mspm0g3507", "CI synthetic fixture",
                dts, config, false);
        var type = Arrays.stream(cls.getDeclaredClasses())
                .filter(c -> c.getSimpleName().equals("Evidence"))
                .findFirst().orElseThrow();
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var field = cls.getDeclaredField("evidence"); field.setAccessible(true);
        var render = cls.getDeclaredMethod("render"); render.setAccessible(true);
        Object sample = constructor.newInstance("lp_mspm0g3507/mspm0g3507",
                "CI synthetic fixture", null, dts, config, new java.util.Properties(),
                false, "CI FIXTURE: synthetic DTS; no physical hardware verified.", overview,
                io.github.niwantha33.zew.core.DevicetreeHardware.inspect(dts));
        field.set(view, sample); render.invoke(view);
        settleNativeStudioLayout();
        var table = (org.eclipse.swt.widgets.Table) studioField(view, cls, "peripheralRows");
        var flash = (org.eclipse.swt.widgets.Label) studioField(view, cls, "capacityFlash");
        var ram = (org.eclipse.swt.widgets.Label) studioField(view, cls, "capacityRam");
        check(table.getItemCount() >= 7 && flash.getText().equals("128 KiB")
                && ram.getText().equals("32 KiB")
                && Arrays.stream(table.getItems()).anyMatch(t ->
                        t.getText(0).equals("ADC") && t.getText(1).startsWith("Configured"))
                && Arrays.stream(table.getItems()).anyMatch(t ->
                        t.getText(0).equals("GPIO") && t.getText(1).equals("Disabled")),
                "actual Overview uses build-backed memory capacities and precise peripheral states");
        captureNativeStudioScreenshot("configuration-overview-ci-fixture.png");

        tabs.setSelection(1);
        settleNativeStudioLayout();
        var controller = (org.eclipse.swt.widgets.Table) studioField(view, cls, "hardwareNodes");
        var searchBox = (org.eclipse.swt.widgets.Text) studioField(view, cls, "hardwareSearch");
        var texts = (org.eclipse.swt.widgets.Text[]) studioField(view, cls, "pageText");
        check(controller.getItemCount() == 1
                && controller.getItem(0).getText(0).contains("adc@4000")
                && controller.getSelectionCount() == 1,
                "two-pane navigator selects the exact built ADC node");
        awaitFocusedDetail(view, cls);
        var next = (org.eclipse.swt.widgets.Button) studioField(view, cls, "next");
        next.notifyListeners(org.eclipse.swt.SWT.Selection,
                new org.eclipse.swt.widgets.Event());
        settleNativeStudioLayout();
        check(texts[1].getText().contains("PIN & CHANNEL")
                && texts[1].getText().contains("Physical pad")
                && texts[1].getText().contains("zephyr,resolution"),
                "ADC pin/channel step shows source-backed logical channels, not invented pads");
        captureNativeStudioScreenshot("configuration-adc-setup-ci-fixture.png");

        next.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
        next.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
        settleNativeStudioLayout();
        var apply = (org.eclipse.swt.widgets.Button) studioField(view, cls, "validateApply");
        check(texts[3].getText().contains("REVIEW — ADC")
                && texts[3].getText().contains("No source change has been proposed")
                && texts[3].getText().contains("No files will be written"),
                "review never claims a diff or source write without a proposal");
        captureNativeStudioScreenshot("configuration-review-ci-fixture.png");

        // Cross-category navigation: real generated nodes for EVERY category
        // in the selected Zephyr hardware index. This is an explicitly
        // synthetic DTS fixture, not physical hardware support evidence.
        String addedNodes = """
                dac@6100 {
                    compatible = "test,dac";
                    status = "okay";
                };
                uart@6200 {
                    compatible = "test,uart";
                    status = "okay";
                    current-speed = <115200>;
                };
                i2c@6300 {
                    compatible = "test,i2c";
                    status = "okay";
                    clock-frequency = <100000>;
                };
                spi@6400 {
                    compatible = "test,spi";
                    status = "okay";
                    cs-gpios = <0x2 3 0>;
                    serial-flash@0 {
                        compatible = "jedec,spi-nor";
                        reg = <0>;
                        status = "okay";
                    };
                };
                i2s@6500 {
                    compatible = "test,i2s";
                    status = "okay";
                };
                pwm@6600 {
                    compatible = "test,pwm";
                    status = "okay";
                    #pwm-cells = <3>;
                };
                timer@6700 {
                    compatible = "test,timer";
                    status = "okay";
                };
                dma@6800 {
                    compatible = "test,dma";
                    status = "okay";
                    #dma-cells = <2>;
                };
                can@6900 {
                    compatible = "test,can";
                    status = "okay";
                };
                usb@6a00 {
                    compatible = "test,usb";
                    status = "okay";
                };
                rtc@6b00 {
                    compatible = "test,rtc";
                    status = "okay";
                };
                watchdog@6c00 {
                    compatible = "test,watchdog";
                    status = "okay";
                };
                ethernet@6d00 {
                    compatible = "test,ethernet";
                    status = "okay";
                };
                pinctrl@6e00 {
                    compatible = "test,pinctrl";
                    status = "okay";
                    pinmux = <0x10>;
                };
                custom@6f00 {
                    compatible = "test,unique-device";
                    status = "okay";
                };
                """;
        String extendedDts = dts.replace("gpio@5000 {", addedNodes + "\n gpio@5000 {");
        check(!extendedDts.equals(dts), "multi-peripheral fixture extends the real node tree");
        var allOverview = io.github.niwantha33.zew.core.FocusedConfigurationModel.inspect(
                "lp_mspm0g3507/mspm0g3507", "CI all-category fixture",
                extendedDts, config, false);
        var allEvidence = constructor.newInstance("lp_mspm0g3507/mspm0g3507",
                "CI all-category fixture", null, extendedDts, config,
                new java.util.Properties(), false,
                "CI FIXTURE: synthetic test nodes, not actual peripheral hardware.", allOverview,
                io.github.niwantha33.zew.core.DevicetreeHardware.inspect(extendedDts));
        field.set(view, allEvidence);
        render.invoke(view);
        tabs.setSelection(0);
        settleNativeStudioLayout();
        var availableKinds = io.github.niwantha33.zew.core.PeripheralWorkflow.categories();
        var overviewFilter = (org.eclipse.swt.widgets.Text)
                studioField(view, cls, "overviewSearch");
        overviewFilter.setText("DMA");
        check(table.getItemCount() == 1
                && table.getItem(0).getText(0).equals("DMA"),
                "Overview keyword search shows only matching generated peripheral summary");
        overviewFilter.setText("");
        check(table.getItemCount() == availableKinds.size()
                && Arrays.stream(table.getItems()).anyMatch(t ->
                        t.getText(0).equals("DMA") && t.getText(1).equals("Setup incomplete"))
                && Arrays.stream(table.getItems()).anyMatch(t ->
                        t.getText(0).equals("Flash / Storage"))
                && Arrays.stream(table.getItems()).anyMatch(t ->
                        t.getText(0).equals("Other")),
                "all peripheral categories, including DMA and vendor-specific devices, have overview rows");
        captureNativeStudioScreenshot("configuration-all-peripherals-ci-fixture.png");
        tabs.setSelection(1);
        var kinds = (org.eclipse.swt.widgets.Combo) studioField(view, cls, "categories");
        check(kinds.getItemCount() == availableKinds.size()
                && Arrays.asList(kinds.getItems()).containsAll(availableKinds),
                "Hardware selector contains EVERY Zephyr categorized peripheral family");
        for (String kind : availableKinds) {
            kinds.select(kinds.indexOf(kind));
            kinds.notifyListeners(org.eclipse.swt.SWT.Selection,
                    new org.eclipse.swt.widgets.Event());
            awaitFocusedDetail(view, cls);
            check(controller.getItemCount() >= 1
                    && controller.getSelectionCount() == 1
                    && texts[0].getText().contains("CONTROLLER")
                    && texts[1].getText().contains(
                            io.github.niwantha33.zew.core.PeripheralWorkflow
                                    .forCategory(kind).connectionTitle()
                                    .toUpperCase(java.util.Locale.ROOT))
                    && texts[2].getText().contains("SETTINGS — " + kind)
                    && texts[3].getText().contains(
                            "REVIEW — " + kind),
                    "installed UI retains 4 tailored steps for " + kind);
        }
        kinds.select(kinds.indexOf("ADC"));
        kinds.notifyListeners(org.eclipse.swt.SWT.Selection,
                new org.eclipse.swt.widgets.Event());
        kinds.select(kinds.indexOf("SPI"));
        kinds.notifyListeners(org.eclipse.swt.SWT.Selection,
                new org.eclipse.swt.widgets.Event());
        awaitFocusedDetail(view, cls);
        check(texts[0].getText().contains("Exact generated node")
                && !texts[0].getText().contains("ADC channel"),
                "old ADC background result never replaces newly selected SPI details");
        next.notifyListeners(org.eclipse.swt.SWT.Selection,
                new org.eclipse.swt.widgets.Event());
        check(texts[1].getText().contains("chip-select")
                && !texts[1].getText().contains("ADC_GAIN"),
                "SPI guidance is bus/chip-select specific, not recycled ADC instructions");
        settleNativeStudioLayout();
        captureNativeStudioScreenshot("configuration-spi-setup-ci-fixture.png");
        var shell = win.getShell();
        var originalBounds = shell.getBounds();
        try {
            // Geometry test of actual installed SWT controls; does not claim
            // Windows 125/150 percent scaling (separate physical UI check).
            for (int[] viewport : new int[][] {{1366, 768}, {1920, 1080}}) {
                shell.setSize(viewport[0], viewport[1]);
                settleNativeStudioLayout();
                var searchControl = (org.eclipse.swt.widgets.Text)
                        studioField(view, cls, "hardwareSearch");
                check(searchControl.getBounds().width > 120
                        && controller.getBounds().width > 120
                        && next.getBounds().width > 60,
                        "native hardware view is usable at "
                            + viewport[0] + "x" + viewport[1]);
            }
        } finally {
            shell.setBounds(originalBounds);
            settleNativeStudioLayout();
        }
        kinds.select(kinds.indexOf("Flash / Storage"));
        kinds.notifyListeners(org.eclipse.swt.SWT.Selection,
                new org.eclipse.swt.widgets.Event());
        awaitFocusedDetail(view, cls);
        check(controller.getItemCount() >= 1
                && Arrays.stream(controller.getItems()).anyMatch(row ->
                    row.getText(0).contains("serial-flash@0"))
                && texts[1].getText().contains("storage"),
                "SPI-NOR client appears under Storage, never under UART");
        // Search is a selection filter: the right pane must follow it.
        kinds.select(kinds.indexOf("SPI"));
        kinds.notifyListeners(org.eclipse.swt.SWT.Selection,
                new org.eclipse.swt.widgets.Event());
        searchBox.setText("absent-no-such-peripheral");
        check(controller.getItemCount() == 0
                && texts[0].getText().contains("Select an actual controller"),
                "empty hardware search clears stale controller details");
        searchBox.setText("");
        awaitFocusedDetail(view, cls);

        var unknown = io.github.niwantha33.zew.core.FocusedConfigurationModel.inspect(
                "lp_mspm0g3507/mspm0g3507", "CI missing-data fixture", "", "", false);
        Object missing = constructor.newInstance("lp_mspm0g3507/mspm0g3507",
                "CI missing-data fixture", null, "", "", new java.util.Properties(),
                true, "CI FIXTURE: missing build data; no hardware validated.", unknown,
                java.util.List.of());
        field.set(view, missing); render.invoke(view);
        tabs.setSelection(0);
        settleNativeStudioLayout();
        check(flash.getText().equals("Unknown")
                && Arrays.stream(table.getItems()).allMatch(t ->
                        t.getText(1).equals("Unknown")),
                "missing build state never claims capacity or configured hardware");
        captureNativeStudioScreenshot("configuration-missing-data-ci-fixture.png");
    }

    private static void awaitFocusedDetail(Object view, Class<?> cls) throws Exception {
        var status = (org.eclipse.swt.widgets.Label) studioField(view, cls, "guideState");
        var display = org.eclipse.swt.widgets.Display.getCurrent();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (status.getText().startsWith("Loading exact")) {
            if (System.nanoTime() >= deadline)
                throw new AssertionError("Selected controller evidence remained loading after 20s: "
                        + status.getText());
            while (display.readAndDispatch()) { }
            Thread.sleep(15);
        }
        check(!status.getText().contains("Loading exact"),
                "async controller detail finishes for selected node");
    }

    private static void captureNativeStudioScreenshot(String screenshotName) throws Exception {
        String directory = System.getenv("ZEW_SCREENSHOT_DIR");
        if (directory == null || directory.isBlank()) return;
        settleNativeStudioLayout();
        var window = org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        var shell = window.getShell();
        var screen = shell.getDisplay().getPrimaryMonitor().getBounds();
        var area = shell.getBounds().intersection(screen);
        if (area.width < 400 || area.height < 200)
            throw new IllegalStateException("Workbench window is too small for screenshot evidence.");
        var output = Path.of(directory).resolve(screenshotName);
        Files.createDirectories(output.getParent());
        var image = new org.eclipse.swt.graphics.Image(shell.getDisplay(), area.width, area.height);
        try {
            var gc = new org.eclipse.swt.graphics.GC(shell.getDisplay());
            try { gc.copyArea(image, area.x, area.y); }
            finally { gc.dispose(); }
            var writer = new org.eclipse.swt.graphics.ImageLoader();
            writer.data = new org.eclipse.swt.graphics.ImageData[] {image.getImageData()};
            writer.save(output.toString(), org.eclipse.swt.SWT.IMAGE_PNG);
        } finally { image.dispose(); }
        if (Files.size(output) < 2048)
            throw new IllegalStateException("Installed workbench screenshot is unexpectedly empty: " + output);
        System.out.println("Native Eclipse screenshot captured: " + output);
    }

    @Override public Object start(IApplicationContext context) throws Exception {
        String[] args = (String[]) context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
        Path fixture = Path.of(args[0]);
        var workspace = ResourcesPlugin.getWorkspace();
        var wd = workspace.getDescription(); wd.setAutoBuilding(false); workspace.setDescription(wd);
        var project = workspace.getRoot().getProject("HelloZephyrEditorSmoke");
        if (!project.exists()) {
            // Eclipse must see a fully configured project description at
            // creation. Mutating the build spec immediately after open can
            // race CDT's initial C-model/PDOM setup on the native UI runner:
            // the indexer sees a transient nonexistent project and retains a
            // PDOMProxy instead of a writable index fragment.
            var description = workspace.newProjectDescription(project.getName());
            description.setLocationURI(fixture.toUri());
            var command = description.newCommand();
            command.setBuilderName("io.github.niwantha33.zew.ui.zephyrBuilder");
            description.setBuildSpec(new ICommand[] {command});
            project.create(description, null);
        }
        project.open(null); project.refreshLocal(IResource.DEPTH_INFINITE, null);
        var ui = Platform.getBundle("io.github.niwantha33.zew.ui");
        ui.start();

        // This must use IDE.openEditor without an editor ID: Project Explorer's
        // default file association is the actual acceptance path.
        if (Boolean.getBoolean("zew.nativeFlow")) {
            Path overlayPath = fixture.resolve("app.overlay");
            Files.writeString(overlayPath,
                    "/* Application hardware overlay. Validate against this board's bindings. */\n"
                    + "{\n    soc {\n        adc:adc0@0 {\n        };\n    };\n};\n");
            project.refreshLocal(IResource.DEPTH_INFINITE, null);
            var opened = new java.util.concurrent.atomic.AtomicReference<org.eclipse.ui.texteditor.ITextEditor>();
            org.eclipse.swt.widgets.Display.getDefault().syncExec(() -> {
                try {
                    var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                    var part = org.eclipse.ui.ide.IDE.openEditor(window.getActivePage(),
                            project.getFile("app.overlay"), true);
                    check(part.getClass().getName().equals(
                            "io.github.niwantha33.zew.ui.DevicetreeEditor"),
                            "Project Explorer opens installed native Devicetree editor by default");
                    opened.set((org.eclipse.ui.texteditor.ITextEditor) part);
                } catch (Exception e) { throw new IllegalStateException(e); }
            });
            // Actual installed .overlay editor: no completion or hover hints,
            // even when Ctrl+Space is invoked by an Eclipse key binding.
            var editorClass = opened.get().getClass();
            var configurationType = Class.forName(editorClass.getName() + "$Configuration",
                    true, editorClass.getClassLoader());
            var constructor = configurationType.getDeclaredConstructor(editorClass);
            constructor.setAccessible(true);
            var silentConfiguration = (org.eclipse.jface.text.source.SourceViewerConfiguration)
                    constructor.newInstance(opened.get());
            check(silentConfiguration.getContentAssistant(null) == null
                    && silentConfiguration.getTextHover(null, org.eclipse.jface.text.IDocument.DEFAULT_CONTENT_TYPE) == null
                    && silentConfiguration.getAnnotationHover(null) == null,
                    "installed native Devicetree editor disables proposals and hover hints");
            // JFace presentation setup asks SWT for theme colours; like the
            // actual editor, invoke it only from the Eclipse display thread.
            org.eclipse.swt.widgets.Display.getDefault().syncExec(() ->
                    check(silentConfiguration.getPresentationReconciler(null) != null,
                            "Devicetree highlighting remains enabled when suggestions are disabled"));

            // Navigation stays available without forcing completion/hover on.
            check(silentConfiguration.getHyperlinkDetectors(null) != null
                    && silentConfiguration.getHyperlinkDetectors(null).length == 1,
                    "native Devicetree editor registers verified Ctrl+click source navigation");
            Path editorPrefs = fixture.resolve(".zew/devicetree-editor.properties");
            Files.createDirectories(editorPrefs.getParent());
            try {
                Files.writeString(editorPrefs, "completion=true\nhover=true\n");
                org.eclipse.swt.widgets.Display.getDefault().syncExec(() -> {
                    check(silentConfiguration.getContentAssistant(null) != null
                            && silentConfiguration.getTextHover(null,
                                org.eclipse.jface.text.IDocument.DEFAULT_CONTENT_TYPE) != null,
                            "project opt-in enables manual DTS completion and node hover");
                });
            } finally {
                Files.deleteIfExists(editorPrefs);
            }

            // Brackets in strings/comments are never coloured; matching open
            // and close tokens keep their level even across partial repaints.
            var bracketMethod = editorClass.getDeclaredMethod("bracketLevels", String.class);
            bracketMethod.setAccessible(true);
            String colourfulSource = "/ { values = <(1) [2]>; /* { ignored } */"
                    + " name = \"{ string }\"; child { }; };";
            byte[] levels = (byte[]) bracketMethod.invoke(null, colourfulSource);
            int rootOpen = colourfulSource.indexOf('{');
            int rootClose = colourfulSource.lastIndexOf('}');
            int cellsOpen = colourfulSource.indexOf('<');
            int cellsClose = colourfulSource.indexOf('>');
            int parenOpen = colourfulSource.indexOf('(');
            int parenClose = colourfulSource.indexOf(')');
            int squareOpen = colourfulSource.indexOf('[');
            int squareClose = colourfulSource.indexOf(']');
            int blockOpen = colourfulSource.indexOf('{', colourfulSource.indexOf("child"));
            int blockClose = colourfulSource.indexOf('}', blockOpen);
            check(levels[rootOpen] == 0 && levels[rootClose] == 0
                    && levels[cellsOpen] == 1 && levels[cellsClose] == 1
                    && levels[parenOpen] == 2 && levels[parenClose] == 2
                    && levels[squareOpen] == 2 && levels[squareClose] == 2
                    && levels[blockOpen] == 1 && levels[blockClose] == 1
                    && levels[colourfulSource.indexOf('{', colourfulSource.indexOf("/*"))] == -1
                    && levels[colourfulSource.indexOf('{', colourfulSource.indexOf("name ="))] == -1,
                    "balanced rainbow brackets match by nesting and skip comments and strings");
            boolean errorShown = false;
            for (int attempt = 0; attempt < 50 && !errorShown; attempt++) {
                var visible = new java.util.concurrent.atomic.AtomicBoolean();
                org.eclipse.swt.widgets.Display.getDefault().syncExec(() -> {
                    var editor = opened.get();
                    var annotations = editor.getDocumentProvider().getAnnotationModel(editor.getEditorInput());
                    if (annotations == null) return;
                    for (var iterator = annotations.getAnnotationIterator(); iterator.hasNext();) {
                        Object annotation = iterator.next();
                        if (annotation instanceof org.eclipse.jface.text.source.Annotation item
                                && item.getText() != null
                                && item.getText().contains("Bare '{'")) {
                            visible.set(true);
                            break;
                        }
                    }
                });
                errorShown = visible.get();
                if (!errorShown) Thread.sleep(100);
            }
            check(errorShown, "Invalid root is underlined in the running Eclipse editor");
        }

        // Test contributions from the actually installed p2 feature, not just
        // plugin.xml in the checkout.
        var registry = Platform.getExtensionRegistry();
        var studioViews = Arrays.stream(registry.getConfigurationElementsFor("org.eclipse.ui.views"));
        check(studioViews.anyMatch(e -> e.getContributor().getName().equals("io.github.niwantha33.zew.ui")
                && "io.github.niwantha33.zew.ui.configurationStudio".equals(e.getAttribute("id"))
                && "false".equals(e.getAttribute("restorable"))),
                "legacy seven-tab Studio is available for explicit advanced use but not restored as landing");
        check(Arrays.stream(registry.getConfigurationElementsFor("org.eclipse.ui.views"))
                .anyMatch(e -> e.getContributor().getName().equals("io.github.niwantha33.zew.ui")
                        && "io.github.niwantha33.zew.ui.configurationHome".equals(e.getAttribute("id"))),
                "new three-section Configuration Studio registered in actual installed Eclipse");
        var studioMenus = Arrays.stream(registry.getConfigurationElementsFor("org.eclipse.ui.menus"))
                .filter(e -> e.getContributor().getName().equals("io.github.niwantha33.zew.ui"))
                .filter(e -> "popup:org.eclipse.ui.popup.any?after=additions".equals(e.getAttribute("locationURI")))
                .flatMap(e -> Arrays.stream(e.getChildren("command")))
                .filter(e -> "io.github.niwantha33.zew.configurationStudio".equals(e.getAttribute("commandId")))
                .toList();
        check(studioMenus.size() == 1 && studioMenus.get(0).getChildren("visibleWhen").length == 1,
                "installed right-click Configuration Studio command is selection-guarded");
        check(Arrays.stream(registry.getConfigurationElementsFor("org.eclipse.ui.propertyPages"))
                .anyMatch(e -> "io.github.niwantha33.zew.projectProperties".equals(e.getAttribute("id"))),
                "Project settings action resolves the registered Zephyr property page");
        var tester = (org.eclipse.core.expressions.PropertyTester) Platform.getBundle(
                "io.github.niwantha33.zew.ui").loadClass(
                        "io.github.niwantha33.zew.ui.ZephyrProjectTester")
                        .getConstructor().newInstance();
        check(tester.test(project.getFile("prj.conf"), "isConfigurationStudioTarget",
                new Object[0], Boolean.TRUE),
                "right-click prj.conf is offered for an unconfigured Zephyr project");
        check(tester.test(project, "isConfigurationStudioTarget", new Object[0], Boolean.TRUE),
                "right-click Zephyr project is offered without SDK configuration");
        check(!tester.test(project.getFile("src/main.c"), "isConfigurationStudioTarget",
                new Object[0], Boolean.TRUE),
                "Configuration Studio action is excluded from unrelated project source files");
        check(!tester.test(workspace.getRoot().getProject("UnmanagedSmoke"), "isConfigurationStudioTarget",
                new Object[0], Boolean.TRUE),
                "Configuration Studio action is excluded from non-Zephyr projects");
        Path otherLocation = fixture.getParent().resolve("foreign-vendor-zephyr-app");
        Files.createDirectories(otherLocation);
        Files.copy(fixture.resolve("CMakeLists.txt"), otherLocation.resolve("CMakeLists.txt"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(fixture.resolve("prj.conf"), otherLocation.resolve("prj.conf"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var external = workspace.getRoot().getProject("VendorOwnedZephyrApp");
        if (!external.exists()) {
            var description = workspace.newProjectDescription(external.getName());
            description.setLocationURI(otherLocation.toUri());
            external.create(description, null);
            external.open(null);
        }
        var foreignDescription = external.getDescription();
        var foreignBuilder = foreignDescription.newCommand();
        foreignBuilder.setBuilderName("com.vendor.otherBuilder");
        foreignDescription.setBuildSpec(new ICommand[] {foreignBuilder});
        external.setDescription(foreignDescription, null);
        external.refreshLocal(IResource.DEPTH_INFINITE, null);
        check(tester.test(external.getFile("prj.conf"), "isConfigurationStudioTarget",
                new Object[0], Boolean.TRUE),
                "right-click source configuration is available without replacing vendor build ownership");
        check(!(Boolean) ui.loadClass("io.github.niwantha33.zew.ui.ZephyrProjectLifecycle")
                .getMethod("supports", IProject.class).invoke(null, external),
                "foreign Eclipse builder is not taken over for West build");
        var popupVisibility = org.eclipse.core.expressions.ExpressionConverter.getDefault().perform(
                studioMenus.get(0).getChildren("visibleWhen")[0].getChildren()[0]);
        var popupContext = new org.eclipse.core.expressions.EvaluationContext(null, project.getFile("prj.conf"));
        popupContext.addVariable("activeMenuSelection", java.util.List.of(project.getFile("prj.conf")));
        check(popupVisibility.evaluate(popupContext) == org.eclipse.core.expressions.EvaluationResult.TRUE,
                "installed Eclipse popup expression shows Configuration Studio for prj.conf");
        popupContext.addVariable("activeMenuSelection", java.util.List.of(project.getFile("src/main.c")));
        check(popupVisibility.evaluate(popupContext) == org.eclipse.core.expressions.EvaluationResult.FALSE,
                "installed Eclipse popup expression hides Configuration Studio for unrelated C file");

        if (Boolean.getBoolean("zew.nativeFlow")) {
            var studioFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            org.eclipse.swt.widgets.Display.getDefault().syncExec(() -> {
                try {
                    var windows = org.eclipse.ui.PlatformUI.getWorkbench().getWorkbenchWindows();
                    check(windows.length > 0, "native Eclipse workbench window exists");
                    // Eclipse's rendering engine parks views from non-selected
                    // perspectives in an offscreen "limbo" Shell at y=10000.
                    // A view reference may report active/visible even though
                    // its SWT controls are still in that shell. Start the
                    // actual desktop smoke from a restored resource perspective.
                    var page = windows[0].getActivePage();
                    var resourcesPerspective = org.eclipse.ui.PlatformUI.getWorkbench()
                            .getPerspectiveRegistry().findPerspectiveWithId("org.eclipse.ui.resourcePerspective");
                    check(resourcesPerspective != null, "installed Eclipse has the resource perspective");
                    // A standard native Memory view must be packaged and
                    // available in our separate hardware debug perspective.
                    var hardwareDebugPerspective = org.eclipse.ui.PlatformUI.getWorkbench()
                            .getPerspectiveRegistry().findPerspectiveWithId(
                                    "io.github.niwantha33.zew.ui.hardwareDebugPerspective");
                    check(hardwareDebugPerspective != null,
                            "Zephyr Hardware Debug perspective is installed");
                    page.setPerspective(hardwareDebugPerspective);
                    // Exercise the actual user command. Eclipse may restore an old
                    // perspective memento with legacy placeholders; the command
                    // must repair it by instantiating each view explicitly.
                    // The smoke application is a separate OSGi bundle: load
                    // the handler from its owning plug-in, not from the smoke
                    // bundle's classloader (which does not import zew.ui internals).
                    var zewUiBundle = org.eclipse.core.runtime.Platform.getBundle(
                            "io.github.niwantha33.zew.ui");
                    check(zewUiBundle != null, "installed Workbench UI bundle is available");
                    var handlerClass = zewUiBundle.loadClass(
                            "io.github.niwantha33.zew.ui.ZephyrDebugWorkspaceHandler");
                    var openHardware = handlerClass.getDeclaredMethod(
                            "open", org.eclipse.ui.IWorkbenchWindow.class);
                    openHardware.setAccessible(true);
                    openHardware.invoke(null, page.getWorkbenchWindow());
                    for (String id : java.util.List.of(
                            "io.github.niwantha33.zew.ui.traceConsole",
                            "io.github.niwantha33.zew.ui.threadTimeline",
                            "io.github.niwantha33.zew.ui.cpuLoad",
                            "io.github.niwantha33.zew.ui.traceCompassIntegration")) {
                        var reference = page.findViewReference(id);
                        check(reference != null && reference.getView(false) != null,
                                "Open Zephyr Hardware Debug Workspace instantiates observability view: " + id);
                    }
                    check(page.findViewReference("org.eclipse.debug.ui.MemoryView") != null,
                            "native target Memory view is part of the hardware debug layout");
                    check(page.showView("org.eclipse.debug.ui.MemoryView") != null,
                            "native target Memory view can be opened without a debug session");
                    page.setPerspective(resourcesPerspective);
                    page.resetPerspective();
                    settleNativeStudioLayout();
                    var view = page.showView(
                            "io.github.niwantha33.zew.ui.configurationStudio");
                    view.getClass().getMethod("displayProject", IProject.class).invoke(view, project);
                    var snapshotField = view.getClass().getDeclaredField("snapshot");
                    snapshotField.setAccessible(true);
                    Object snapshot = snapshotField.get(view);
                    check(snapshot != null, "native Configuration Studio opened unconfigured prj.conf");
                    var entries = (java.util.List<?>) snapshot.getClass().getMethod("options").invoke(snapshot);
                    check(entries.stream().anyMatch(entry -> {
                        try {
                            return "DEBUG_INFO".equals(entry.getClass().getMethod("symbol").invoke(entry));
                        } catch (Exception error) { throw new IllegalStateException(error); }
                    }), "unconfigured Configuration Studio displays invalid source override");
                    // Verify the actual installed native UX, not screenshots or
                    // plugin.xml declarations. This must run on the SWT thread.
                    var cls = view.getClass();
                    try (var packagedProbe = cls.getResourceAsStream("/scripts/kconfig_inspect.py")) {
                        check(packagedProbe != null && packagedProbe.readNBytes(1024).length >= 256,
                                "Kconfiglib inspector is bundled inside installed Eclipse feature");
                    }
                    var tabsField = cls.getDeclaredField("tabs");
                    tabsField.setAccessible(true);
                    var studioTabs = (org.eclipse.swt.widgets.TabFolder) tabsField.get(view);
                    check(studioTabs.getItemCount() == 5
                            && "Start Here".equals(studioTabs.getItem(0).getText())
                            && "Software · Kconfig".equals(studioTabs.getItem(1).getText())
                            && "Hardware · Devicetree".equals(studioTabs.getItem(2).getText())
                            && "Evidence && Diagnostics".equals(studioTabs.getItem(3).getText())
                            && "Generated .config · Diff".equals(studioTabs.getItem(4).getText())
                            && studioTabs.getSelectionIndex() == 0,
                            "native Configuration Studio opens task-first Start here with read-only generated .config Diff tab");
                    var diffPanelField = cls.getDeclaredField("configDiffPanel");
                    diffPanelField.setAccessible(true);
                    check(diffPanelField.get(view) != null,
                            "installed native generated .config Diff panel is created");
                    // Real view activation calls setFocus; never allow it to
                    // activate Software merely because prj.conf is selected.
                    view.getClass().getMethod("setFocus").invoke(view);
                    check(studioTabs.getSelectionIndex() == 0,
                            "activating an existing Configuration Studio keeps its visual start page");
                    var handlerType = view.getClass().getClassLoader()
                            .loadClass("io.github.niwantha33.zew.ui.ConfigurationStudioHandler");
                    var route = handlerType.getDeclaredMethod("navigationFor",
                            boolean.class, String.class);
                    route.setAccessible(true);
                    check(route.invoke(null, false, "prj.conf").toString().equals("START")
                            && route.invoke(null, false, "app.overlay").toString().equals("START")
                            && route.invoke(null, true, "prj.conf").toString().equals("PROJECT_OVERRIDES")
                            && route.invoke(null, true, "app.overlay").toString().equals("HARDWARE"),
                            "ordinary Zephyr menu ignores selected config file; explicit context popup deep-links");
                    var routeEnum = java.util.Arrays.stream(handlerType.getDeclaredClasses())
                            .filter(c -> c.getSimpleName().equals("Entry"))
                            .findFirst().orElseThrow();
                    var target = handlerType.getDeclaredMethod("targetViewId", routeEnum);
                    var section = handlerType.getDeclaredMethod("initialSection", routeEnum);
                    target.setAccessible(true);
                    section.setAccessible(true);
                    var menuEntry = route.invoke(null, false, "app.overlay");
                    var overlayEntry = route.invoke(null, true, "app.overlay");
                    var projectEntry = route.invoke(null, true, "prj.conf");
                    check(target.invoke(null, menuEntry).equals("io.github.niwantha33.zew.ui.configurationHome")
                            && target.invoke(null, overlayEntry).equals("io.github.niwantha33.zew.ui.configurationHome")
                            && target.invoke(null, projectEntry).equals("io.github.niwantha33.zew.ui.configurationHome")
                            && section.invoke(null, menuEntry).equals("overview")
                            && section.invoke(null, overlayEntry).equals("hardware")
                            && section.invoke(null, projectEntry).equals("system"),
                            "menu and explicit file context all target ONE three-section Studio");
                    // The three-section Studio and legacy advanced Studio may share an
                    // Eclipse view stack. Menu/navigation checks above can select the
                    // other part; an obscured SWT tab has a zero-sized viewport.
                    // Activate the actual advanced view before asserting real bounds.
                    // The legacy advanced view is a dockable side pane in the stock
                    // resource perspective. A full-width 960px diagram cannot be
                    // visibility-tested while its containing view stack is only
                    // ~400px wide or minimized. Expand the actual workbench part,
                    // not the SWT child widgets, before testing painted geometry.
                    var nativePage = windows[0].getActivePage();
                    var advancedRef = nativePage.getReference(view);
                    check(advancedRef != null, "advanced Studio has a real Eclipse part reference");
                    // An unopened resource perspective can park added views in
                    // an invisible trim Shell (commonly at y=10000). Merely
                    // calling activate() or MAXIMIZED leaves that parked SWT
                    // hierarchy at zero size. Restore the stack first.
                    int stateBefore = nativePage.getPartState(advancedRef);
                    nativePage.setPartState(advancedRef, org.eclipse.ui.IWorkbenchPage.STATE_RESTORED);
                    nativePage.showView("io.github.niwantha33.zew.ui.configurationStudio",
                            null, org.eclipse.ui.IWorkbenchPage.VIEW_ACTIVATE);
                    nativePage.setPartState(advancedRef, org.eclipse.ui.IWorkbenchPage.STATE_MAXIMIZED);
                    nativePage.activate(view);
                    settleNativeStudioLayout();
                    var activeStudioShell = windows[0].getShell();
                    check(nativePage.isPartVisible(view),
                            "expanded advanced Studio is registered visible in Eclipse"
                            + " [before=" + stateBefore + ", after="
                            + nativePage.getPartState(advancedRef) + "]");
                    check(studioTabs.getShell() == activeStudioShell
                            && activeStudioShell.isVisible(),
                            "advanced Studio control is attached to the displayed workbench shell"
                            + " [tabShell=" + studioTabs.getShell().getBounds()
                            + ", shell=" + activeStudioShell.getBounds()
                            + ", tabs=" + swtBoundsChain(studioTabs) + "]");
                    var beginnerPage = (org.eclipse.swt.widgets.Composite)
                            studioTabs.getItem(0).getControl();
                    var beginnerBody = (org.eclipse.swt.widgets.Composite)
                            studioField(view, cls, "beginnerBody");
                    var scroll = (org.eclipse.swt.custom.ScrolledComposite)
                            studioField(view, cls, "beginnerScroll");
                    var canvas = (org.eclipse.swt.widgets.Canvas)
                            studioField(view, cls, "beginnerFlow");
                    settleNativeStudioLayout();
                    check(canvas.getClientArea().width >= 900
                            && canvas.getClientArea().height >= 300
                            && scroll.getContent() == beginnerBody,
                            "colourful configuration flow is actually laid out inside scrollable Start Here"
                            + " [canvasArea=" + canvas.getClientArea()
                            + ", canvasBounds=" + canvas.getBounds()
                            + ", visible=" + canvas.isVisible()
                            + ", bodySize=" + beginnerBody.getSize()
                            + ", scrollArea=" + scroll.getClientArea()
                            + ", scrollMinHeight=" + scroll.getMinHeight()
                            + ", scrollContentMatches=" + (scroll.getContent() == beginnerBody)
                            + ", tab=" + studioTabs.getSelectionIndex()
                            + ", shell=" + org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell().getBounds()
                            + ", canvasParents=" + swtBoundsChain(canvas)
                            + ", tabParents=" + swtBoundsChain(studioTabs) + "]");
                    check(scroll.getExpandVertical()
                            && beginnerBody.getSize().y >= scroll.getMinHeight()
                            && beginnerBody.getSize().y >= scroll.getClientArea().height
                            && canvas.isVisible(),
                            "Start Here content has a real height, not just a minimum scroll range");
                    var contentPoint = canvas.toDisplay(10, 10);
                    var viewportPoint = scroll.toControl(contentPoint);
                    check(scroll.getClientArea().contains(viewportPoint),
                            "Start Here diagram intersects the visible viewport on first open");
                    var originalScrollSize = scroll.getSize();
                    try {
                        scroll.setSize(640, 240);
                        scroll.setOrigin(0, scroll.getMinHeight());
                        check(scroll.getOrigin().y > 0
                                && beginnerBody.getSize().y >= scroll.getMinHeight(),
                                "Start Here retains content height and scrolls in a small viewport");
                    } finally {
                        scroll.setOrigin(0, 0);
                        scroll.setSize(originalScrollSize);
                        settleNativeStudioLayout();
                    }
                    check(((Integer) canvas.getClass().getMethod("blockCount").invoke(canvas)) == 8,
                            "eight visual blocks correspond to real workflow tools");
                    var flowBlock = java.util.Arrays.stream(canvas.getClass().getDeclaredClasses())
                            .filter(c -> c.getSimpleName().equals("Block"))
                            .findFirst().orElseThrow();
                    var boundMethod = canvas.getClass().getMethod("boundsOf", flowBlock);
                    for (Object block : flowBlock.getEnumConstants()) {
                        var bounds = (org.eclipse.swt.graphics.Rectangle)
                                boundMethod.invoke(canvas, block);
                        check(bounds != null && bounds.width >= 100 && bounds.height >= 95,
                                "visual flow has real clickable and non-overlapping size for " + block);
                    }
                    var softwareBlock = java.util.Arrays.stream(flowBlock.getEnumConstants())
                            .filter(b -> b.toString().equals("KCONFIG")).findFirst().orElseThrow();
                    var softwareBounds = (org.eclipse.swt.graphics.Rectangle)
                            boundMethod.invoke(canvas, softwareBlock);
                    var flowClick = new org.eclipse.swt.widgets.Event();
                    flowClick.button = 1;
                    flowClick.x = softwareBounds.x + softwareBounds.width / 2;
                    flowClick.y = softwareBounds.y + softwareBounds.height / 2;
                    canvas.notifyListeners(org.eclipse.swt.SWT.MouseDown, flowClick);
                    check(studioTabs.getSelectionIndex() == 1,
                            "clicking coloured Driver options block navigates to actual Kconfig editor");
                    studioTabs.setSelection(0);
                    studioTabs.notifyListeners(org.eclipse.swt.SWT.Selection,
                            new org.eclipse.swt.widgets.Event());
                    settleNativeStudioLayout();
                    var simpleGoals = (org.eclipse.swt.widgets.Composite) beginnerBody.getChildren()[5];
                    var goals = java.util.Arrays.stream(simpleGoals.getChildren())
                            .filter(c -> c instanceof org.eclipse.swt.widgets.Button)
                            .map(c -> (org.eclipse.swt.widgets.Button) c).toList();
                    check(goals.size() == 6 && goals.stream().anyMatch(b ->
                                    b.getText().equals("Read a voltage")),
                            "beginners choose an understandable job rather than CONFIG symbols");
                    var voltage = goals.stream().filter(b -> b.getText().equals("Read a voltage"))
                            .findFirst().orElseThrow();
                    voltage.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
                    var beginnerHeadline = (org.eclipse.swt.widgets.Label)
                            studioField(view, cls, "beginnerHeadline");
                    var beginnerNext = (org.eclipse.swt.widgets.Label)
                            studioField(view, cls, "beginnerNext");
                    var beginnerSteps = (org.eclipse.swt.widgets.Text)
                            studioField(view, cls, "beginnerSteps");
                    var expertMetrics = (org.eclipse.swt.widgets.Composite)
                            studioField(view, cls, "expertMetrics");
                    check(beginnerHeadline.getText().contains("Read a voltage")
                            && beginnerNext.getText().contains("Build & validate")
                            && beginnerSteps.getText().contains("No pins") == false
                            && !beginnerSteps.getEditable()
                            && !expertMetrics.getVisible(),
                            "beginner mode explains missing build without exposing a sea of settings");
                    captureNativeStudioScreenshot("beginner-first-configuration-studio.png");
                    var actions = (org.eclipse.swt.widgets.Composite) beginnerBody.getChildren()[8];
                    var findHardware = java.util.Arrays.stream(actions.getChildren())
                            .filter(c -> c instanceof org.eclipse.swt.widgets.Button)
                            .map(c -> (org.eclipse.swt.widgets.Button) c)
                            .filter(b -> b.getText().equals("1. Find my hardware"))
                            .findFirst().orElseThrow();
                    findHardware.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
                    check(studioTabs.getSelectionIndex() == 2 && expertMetrics.getVisible(),
                            "beginner hardware step opens real hardware navigator and restores advanced chrome");
                    studioTabs.setSelection(0);
                    studioTabs.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
                    var hardwareField = cls.getDeclaredField("hardwareDevices");
                    hardwareField.setAccessible(true);
                    var hw = (org.eclipse.swt.widgets.Tree) hardwareField.get(view);
                    check(hw.getColumnCount() == 3
                            && "Device / hierarchy".equals(hw.getColumn(0).getText()),
                            "installed Hardware workspace has a compiled-Devicetree device inventory");
                    var peripheralField = cls.getDeclaredField("peripheralCategories");
                    peripheralField.setAccessible(true);
                    var peripheralCategories = (org.eclipse.swt.widgets.List) peripheralField.get(view);
                    var names = java.util.Arrays.asList(peripheralCategories.getItems());
                    check(names.containsAll(java.util.List.of(
                            "ADC", "GPIO", "I²C", "SPI", "UART", "CAN", "Debug", "RTOS", "Network", "More")),
                            "native vertical peripheral categories are present");
                    var editorField = cls.getDeclaredField("inlineToggleEditor");
                    editorField.setAccessible(true);
                    var inlineEditor = (org.eclipse.swt.custom.TableEditor) editorField.get(view);
                    check(inlineEditor != null && !inlineEditor.getClass().getName().isBlank(),
                            "native value column owns a reusable table-row toggle editor");
                    for (String fieldName : new String[]{"enableButton", "disableButton"}) {
                        var field = cls.getDeclaredField(fieldName);
                        field.setAccessible(true);
                        var button = (org.eclipse.swt.widgets.Button) field.get(view);
                        String expected = fieldName.equals("enableButton") ? "Enable" : "Disable";
                        check(expected.equals(button.getText()) && !button.getEnabled(),
                                "unconfigured Kconfig Studio exposes a safe " + expected + " action");
                    }
                    var listField = cls.getDeclaredField("options");
                    listField.setAccessible(true);
                    var featureTable = (org.eclipse.swt.widgets.Table) listField.get(view);
                    check(featureTable.getColumnCount() == 5
                            && featureTable.getMenu() != null
                            && featureTable.getItemCount() > 0,
                            "native configuration feature table supports a context menu");
                    check(featureTable.getColumn(0).getText().equals("Kconfig symbol")
                            && featureTable.getItem(0).getText(0).startsWith("CONFIG_")
                            && featureTable.getColumn(1).getText().equals("Feature")
                            && featureTable.getColumn(2).getText().contains("Enable / Disable"),
                            "symbol comes first, followed by feature and editable value");
                    var overridesField = cls.getDeclaredField("changesMetric");
                    overridesField.setAccessible(true);
                    var overridesCount = (org.eclipse.swt.widgets.Label) overridesField.get(view);
                    check(!"—".equals(overridesCount.getText()),
                            "live project override summary is populated");
                    verifyStudioNavigation(view, cls, snapshotField, snapshot, featureTable, hw, fixture);
                    verifyThreeSectionStudio(project);
                    verifyStudioProblemMarkers();
                    windows[0].getActivePage().activate(view);
                    // Reproduce the user's screenshot: opening via a prj.conf
                    // context used to switch to Kconfig with the overview still
                    // hidden. A deliberate context link must restore chrome.
                    cls.getMethod("focusProjectChanges").invoke(view);
                    check(studioTabs.getSelectionIndex() == 1 && expertMetrics.getVisible(),
                            "explicit prj.conf context shows Kconfig WITH its summary and edit toolbar");
                    view.getClass().getMethod("setFocus").invoke(view);
                    check(studioTabs.getSelectionIndex() == 1,
                            "setFocus preserves deliberately selected Software view");
                    var visualGuide = (org.eclipse.swt.widgets.Button)
                            studioField(view, cls, "visualGuideButton");
                    check(visualGuide.getVisible() && visualGuide.getEnabled(),
                            "visual guide is always available above advanced Kconfig");
                    visualGuide.notifyListeners(org.eclipse.swt.SWT.Selection,
                            new org.eclipse.swt.widgets.Event());
                    settleNativeStudioLayout();
                    check(studioTabs.getSelectionIndex() == 0
                            && !expertMetrics.getVisible()
                            && !((org.eclipse.swt.widgets.Composite)
                                    studioField(view, cls, "expertToolbar")).getVisible()
                            && ((org.eclipse.swt.widgets.Canvas)
                                    studioField(view, cls, "beginnerFlow")).isVisible(),
                            "Visual guide button restores colourful flow and hides advanced controls");
                    view.getClass().getMethod("setFocus").invoke(view);
                    check(studioTabs.getSelectionIndex() == 0,
                            "native reactivation keeps visual flow after Kconfig deep link");
                    captureNativeStudioScreenshot("visual-guide-after-kconfig-context.png");
                    // The map is a real registered Eclipse ViewPart, not a
                    // screenshot of a mock. Verify its decoded address rows
                    // and canvas on the installed packaged feature.
                    var mapPart = windows[0].getActivePage().showView(
                            "io.github.niwantha33.zew.ui.socMemoryMap");
                    var mapClass = mapPart.getClass();
                    String memoryDts = """
                            /dts-v1/;
                            / {
                                #address-cells = <1>;
                                #size-cells = <1>;
                                memory@20000000 {
                                    compatible = "mmio-sram";
                                    reg = <0x20000000 0x8000>;
                                };
                                soc {
                                    #address-cells = <1>;
                                    #size-cells = <1>;
                                    ranges;
                                    flash@0 {
                                        compatible = "soc-nv-flash";
                                        reg = <0x0 0x20000>;
                                    };
                                    uart@40000000 {
                                        compatible = "ti,mspm0-uart";
                                        reg = <0x40000000 0x1000>;
                                        status = "disabled";
                                    };
                                };
                            };
                            """;
                    mapClass.getMethod("displayProject", IProject.class,
                            String.class, java.nio.file.Path.class, boolean.class)
                            .invoke(mapPart, project, memoryDts, fixture.resolve(".zew-build/first"), false);
                    var mapped = (org.eclipse.swt.widgets.Table)
                            studioField(mapPart, mapClass, "regions");
                    var visual = (org.eclipse.swt.widgets.Canvas)
                            studioField(mapPart, mapClass, "diagram");
                    var mapDetails = (org.eclipse.swt.widgets.Text)
                            studioField(mapPart, mapClass, "detail");
                    var mapSummary = (org.eclipse.swt.widgets.Label)
                            studioField(mapPart, mapClass, "summary");
                    check(mapped.getItemCount() == 3
                            && visual.getSize().x >= 900
                            && visual.getSize().y >= 150
                            && !mapDetails.getEditable()
                            && mapSummary.getText().contains("3 address region"),
                            "installed SoC Memory Map renders real RAM/Flash/MMIO data, not decorative UI");
                    check(java.util.Arrays.stream(mapped.getItems()).anyMatch(item ->
                            item.getText(0).equals("RAM") && item.getText(1).equals("0x20000000")
                                    && item.getText(2).equals("32 KiB"))
                            && java.util.Arrays.stream(mapped.getItems()).anyMatch(item ->
                            item.getText(0).equals("FLASH") && item.getText(1).equals("0x00000000")
                                    && item.getText(2).equals("128 KiB"))
                            && java.util.Arrays.stream(mapped.getItems()).anyMatch(item ->
                            item.getText(0).equals("PERIPHERAL")
                                    && item.getText(1).equals("0x40000000")),
                            "all hex bases/sizes are backed by actual generated reg/ranges values");
                    var mapFilter = (org.eclipse.swt.widgets.Combo)
                            studioField(mapPart, mapClass, "filter");
                    mapFilter.select(mapFilter.indexOf("RAM"));
                    mapFilter.notifyListeners(org.eclipse.swt.SWT.Selection,
                            new org.eclipse.swt.widgets.Event());
                    check(mapped.getItemCount() == 1
                            && mapped.getItem(0).getText(0).equals("RAM")
                            && mapDetails.getText().contains("End (exclusive)") == false
                            && mapDetails.getText().contains("0x20008000"),
                            "RAM filter retains precise end-exclusive and selected-row evidence");
                    captureNativeStudioScreenshot("soc-memory-map-native.png");
                    var topology = (org.eclipse.swt.widgets.Tree)
                            studioField(mapPart, mapClass, "topology");
                    check(topology.getItemCount() == 1
                            && topology.getItem(0).getItemCount() == 2,
                            "generated DTS hierarchy includes root, RAM and SoC bus");
                    var socNode = java.util.Arrays.stream(topology.getItem(0).getItems())
                            .filter(item -> item.getText(0).equals("soc"))
                            .findFirst().orElseThrow();
                    var uartNode = java.util.Arrays.stream(socNode.getItems())
                            .filter(item -> item.getText(0).equals("uart@40000000"))
                            .findFirst().orElseThrow();
                    check(uartNode.getText(1).equals("disabled")
                            && uartNode.getText(2).contains("0x40000000"),
                            "disabled UART is visible under its real bus with decoded address");
                    topology.setSelection(uartNode);
                    topology.notifyListeners(org.eclipse.swt.SWT.Selection,
                            new org.eclipse.swt.widgets.Event());
                    check(mapDetails.getText().contains("Node status: disabled")
                            && mapDetails.getText().contains("/soc/uart@40000000"),
                            "hierarchy selection opens the exact generated node evidence");
                    var mapTabs = (org.eclipse.swt.widgets.TabFolder) topology.getParent();
                    mapTabs.setSelection(1);
                    captureNativeStudioScreenshot("devicetree-hierarchy-native.png");
                    // PR1: the installed Eclipse view must instantiate a real
                    // SWT Canvas, not a placeholder TableViewer or JSON-only panel.
                    var profiler = windows[0].getActivePage().showView(
                            "io.github.niwantha33.zew.ui.memoryProfiler");
                    var profilerType = profiler.getClass();
                    var profilerCanvas = (org.eclipse.swt.widgets.Canvas)
                            studioField(profiler, profilerType, "canvas");
                    var profilerDetails = (org.eclipse.swt.widgets.Text)
                            studioField(profiler, profilerType, "detail");
                    check(profilerCanvas.getSize().x >= 900 && profilerCanvas.getSize().y >= 90
                            && !profilerDetails.getEditable(),
                            "installed Memory Profiler renders real read-only SWT Canvas");
                    captureNativeStudioScreenshot("memory-profiler-native.png");
                    windows[0].getActivePage().activate(view);
                } catch (Throwable error) { studioFailure.set(error); }
            });
            if (studioFailure.get() != null)
                throw new IllegalStateException("Native Studio smoke failed", studioFailure.get());
        }
        var parsed = EditorSettings.parse(Files.readString(fixture.resolve(".zew-build/first/editor-settings.tsv"), StandardCharsets.UTF_8));
        OpenOcdMetadataSmoke.run(ui, fixture);
        if (args.length < 2 || !args[1].equals("verify")) ProjectImportSmoke.run(ui, fixture.getParent());
        if (Boolean.getBoolean("zew.nativeFlow") && (args.length < 2 || !args[1].equals("verify")))
            StandardProjectFlowSmoke.run(ui, fixture.getParent());
        if (Boolean.getBoolean("zew.nativeFlow")) {
            if (args.length < 2 || !args[1].equals("verify")) CoreBuildCompatibilitySmoke.run(ui);
            else CoreBuildCompatibilitySmoke.verifyRestored();
        }
        // Query native shortcuts without starting a debugger or programming hardware.
        var flashShortcut = (org.eclipse.debug.ui.ILaunchShortcut2) ui.loadClass(
                "io.github.niwantha33.zew.ui.ZephyrFlashShortcut").getConstructor().newInstance();
        var debugShortcut = (org.eclipse.debug.ui.ILaunchShortcut2) ui.loadClass(
                "io.github.niwantha33.zew.ui.ZephyrDebugShortcut").getConstructor().newInstance();
        var selection = new org.eclipse.jface.viewers.StructuredSelection(project.getFile("src/main.c"));
        var setupProperty = new QualifiedName("io.github.niwantha33.zew.ui", "setupRoot");
        String savedSetup = project.getPersistentProperty(setupProperty);
        var launchManager = org.eclipse.debug.core.DebugPlugin.getDefault().getLaunchManager();
        var type = launchManager.getLaunchConfigurationType("io.github.niwantha33.zew.ui.launchType");
        org.eclipse.debug.core.ILaunchConfiguration flashConfig = null, debugConfig = null;
        try {
            project.setPersistentProperty(setupProperty, null);
            check(flashShortcut.getLaunchableResource(selection) == null,
                    "ordinary C project is not treated as an imported Zephyr Flash target");
            project.setPersistentProperty(setupProperty, fixture.toString());
            DebugLaunchSelectionSmoke.run(ui, project);
            check(Platform.getAdapterManager().hasAdapter(project, "org.eclipse.debug.ui.actions.ILaunchable"),
                    "managed project participates in native launch selection");
            check(project.equals(flashShortcut.getLaunchableResource(selection)),
                    "Flash shortcut resolves editor source selection to imported project");
            var flashCopy = type.newInstance(null, launchManager.generateLaunchConfigurationName("Smoke Flash"));
            flashCopy.setAttribute("io.github.niwantha33.zew.ui.launch.project", project.getName());
            flashCopy.setAttribute("io.github.niwantha33.zew.ui.launch.action", "flash");
            flashConfig = flashCopy.doSave();
            var debugCopy = type.newInstance(null, launchManager.generateLaunchConfigurationName("Smoke Debug"));
            debugCopy.setAttribute("io.github.niwantha33.zew.ui.launch.project", project.getName());
            debugCopy.setAttribute("io.github.niwantha33.zew.ui.launch.action", "debug");
            debugConfig = debugCopy.doSave();
            var launch = new org.eclipse.debug.core.Launch(debugConfig, "debug", null);
            var frame = (org.eclipse.debug.core.model.IDebugElement) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {org.eclipse.debug.core.model.IDebugElement.class},
                    (proxy, method, arguments) -> method.getName().equals("getLaunch") ? launch : null);
            var debugSelection = new org.eclipse.jface.viewers.StructuredSelection(frame);
            check(project.equals(debugShortcut.getLaunchableResource(debugSelection)),
                    "Debug view stack-frame context resolves the managed project without a source editor");
            check(project.equals(flashShortcut.getLaunchableResource(new org.eclipse.jface.viewers.StructuredSelection(launch))),
                    "Flash resolves the selected managed launch instead of guessing a project");
            check(Platform.getAdapterManager().hasAdapter(frame, "org.eclipse.debug.ui.actions.ILaunchable"),
                    "managed debug element is launchable by native toolbar");
            var handlers = Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.handlers");
            var nativeBuild = Arrays.stream(handlers).filter(element -> element.getContributor().getName()
                    .equals("io.github.niwantha33.zew.ui") && "org.eclipse.ui.project.buildProject".equals(
                            element.getAttribute("commandId"))).findFirst().orElseThrow();
            var expression = org.eclipse.core.expressions.ExpressionConverter.getDefault().perform(
                    nativeBuild.getChildren("activeWhen")[0].getChildren()[0]);
            var contextForBuild = new org.eclipse.core.expressions.EvaluationContext(null, frame);
            contextForBuild.addVariable("selection", java.util.List.of(frame));
            check(expression.evaluate(contextForBuild) == org.eclipse.core.expressions.EvaluationResult.TRUE,
                    "native Build handler activates for the managed Debug view selection");
            contextForBuild.addVariable("selection", java.util.List.of(workspace.getRoot().getProject("UnmanagedSmoke")));
            check(expression.evaluate(contextForBuild) == org.eclipse.core.expressions.EvaluationResult.FALSE,
                    "native Build handler does not replace other projects' normal behavior");
            var toolbarCommands = Arrays.stream(Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.menus"))
                    .filter(element -> element.getContributor().getName().equals("io.github.niwantha33.zew.ui")
                            && element.getAttribute("locationURI").startsWith("toolbar:"))
                    .flatMap(element -> Arrays.stream(element.getChildren("toolbar")))
                    .flatMap(element -> Arrays.stream(element.getChildren("command")))
                    .map(element -> element.getAttribute("commandId")).toList();
            check(toolbarCommands.contains("org.eclipse.ui.project.buildProject")
                    && toolbarCommands.contains("io.github.niwantha33.zew.flash"),
                    "installed toolbar contributes visible native Build and explicit Flash commands");
            check(toolbarCommands.contains("io.github.niwantha33.zew.flashDebug")
                    && toolbarCommands.stream().filter(
                            id -> id.equals("io.github.niwantha33.zew.flashDebug")).count() == 1,
                    "installed Zephyr native Flash-and-Debug toolbar icon is visible exactly once");
            check(Arrays.stream(handlers).anyMatch(element -> element.getContributor().getName()
                            .equals("io.github.niwantha33.zew.ui")
                            && "io.github.niwantha33.zew.debug".equals(element.getAttribute("commandId"))
                            && "io.github.niwantha33.zew.ui.ZephyrDebugHandler".equals(element.getAttribute("class"))),
                    "native Zephyr Debug menu action resolves to the project-aware handler");
            check(Arrays.stream(handlers).anyMatch(element -> element.getContributor().getName()
                            .equals("io.github.niwantha33.zew.ui")
                            && "io.github.niwantha33.zew.attach".equals(element.getAttribute("commandId"))
                            && "io.github.niwantha33.zew.ui.ZephyrDebugHandler".equals(element.getAttribute("class"))),
                    "explicit attach-only menu action remains available for advanced users");
            var flashes = Arrays.asList(flashShortcut.getLaunchConfigurations(selection));
            var debugs = Arrays.asList(debugShortcut.getLaunchConfigurations(selection));
            check(flashes.contains(flashConfig) && !flashes.contains(debugConfig),
                    "Run Flash discovery excludes native Debug configurations");
            check(debugs.isEmpty(),
                    "native Debug As never reuses a saved attach-only configuration in place of verified Flash-and-Debug");
        } finally {
            if (flashConfig != null) flashConfig.delete();
            if (debugConfig != null) debugConfig.delete();
            project.setPersistentProperty(setupProperty, savedSetup);
        }
        var apply = ui.loadClass("io.github.niwantha33.zew.ui.CdtEditor").getMethod("apply", IProject.class, EditorSettings.class, IProgressMonitor.class);
        // The dedicated post-build refresh uses the workspace root rule.
        if (args.length < 2 || !args[1].equals("verify")) workspace.run(m -> {
            try { apply.invoke(null, project, parsed, m); }
            catch (Exception error) { throw new CoreException(new Status(IStatus.ERROR, "zew.editor.smoke", "CDT apply failed", error)); }
        }, workspace.getRoot(), 0, new NullProgressMonitor());
        check(project.hasNature("org.eclipse.cdt.core.cnature"), "C nature installed");
        check(project.hasNature("org.eclipse.cdt.core.ccnature"), "C++ nature installed");
        check(project.getDescription().getBuildSpec().length == 1 && project.getDescription().getBuildSpec()[0].getBuilderName().endsWith(".zephyrBuilder"), "native West builder preserved; no Make builder added");
        var info = CCorePlugin.getDefault().getScannerInfoProvider(project).getScannerInformation(project.getFile("src/main.c"));
        System.out.println("Scanner provider: " + CCorePlugin.getDefault().getScannerInfoProvider(project).getClass().getName() + "; macros=" + (info == null ? "null" : info.getDefinedSymbols().size()));
        var diagnostics = CoreModel.getDefault().getProjectDescription(project, false);
        System.out.println("Active=" + diagnostics.getActiveConfiguration().getId() + "; default=" + diagnostics.getDefaultSettingConfiguration().getId());
        for (var cfg : diagnostics.getConfigurations()) {
            System.out.println("Config=" + cfg.getId() + "; provider=" + cfg.getBuildSystemId());
            for (var lang : cfg.getRootFolderDescription().getLanguageSettings()) System.out.println("Root language=" + lang.getLanguageId() + "; macros=" + lang.getSettingEntries(org.eclipse.cdt.core.settings.model.ICSettingEntry.MACRO).length);
            var file = (org.eclipse.cdt.core.settings.model.ICFileDescription)cfg.getResourceDescription(new org.eclipse.core.runtime.Path("src/main.c"), true);
            System.out.println("File=" + file + "; language=" + (file == null ? "missing" : file.getLanguageSetting().getLanguageId()));
            var keeper = (org.eclipse.cdt.core.language.settings.providers.ILanguageSettingsProvidersKeeper)cfg;
            for (var p : keeper.getLanguageSettingProviders()) {
                var values = p.getSettingEntries(cfg, project.getFile("src/main.c"), file.getLanguageSetting().getLanguageId());
                System.out.println("LSP=" + p.getId() + "; entries=" + (values == null ? "null" : values.size()));
            }
        }
        check(info != null && "73".equals(info.getDefinedSymbols().get("CONFIG_ZEW_TEST")), "generated Kconfig macro visible");
        check(Arrays.stream(info.getIncludePaths()).anyMatch(p -> p.endsWith("/include") && p.contains("headers")), "compiler include paths visible");
        var cpp = CCorePlugin.getDefault().getScannerInfoProvider(project).getScannerInformation(project.getFile("src/helper.cpp"));
        check("201703L".equals(cpp.getDefinedSymbols().get("__cplusplus")), "C++17 compiler mode visible");
        check(!cpp.getDefinedSymbols().containsKey("__EXCEPTIONS") && !cpp.getDefinedSymbols().containsKey("__GXX_RTTI"), "C++ exception/RTTI compiler flags preserved");
        check("11".equals(cpp.getDefinedSymbols().get("CPP_ONLY")) && !info.getDefinedSymbols().containsKey("CPP_ONLY"), "per-file C/C++ macros remain separate");
        // Refresh resources after CDT Core Build transitions and preserve the
        // upstream job synchronisation before verifying persisted index entries.
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        var manager = CCorePlugin.getIndexManager();
        // Native configuration jobs may have switched the active configuration during the import tests.
        org.eclipse.core.runtime.jobs.Job.getJobManager().join(
                org.eclipse.cdt.debug.core.launch.CoreBuildLaunchBarTracker.JOB_FAMILY_CORE_BUILD_LAUNCH_BAR_TRACKER,
                new NullProgressMonitor());
        // Import/configuration jobs may invalidate the index immediately after
        // the first reindex. CDT can report an idle indexer before a queued
        // model refresh has installed the project's source entries. Require real
        // expected bindings, with bounded retries; never accept an empty PDOM.
        var cproject = CoreModel.getDefault().create(project);
        var index = manager.getIndex(cproject);
        boolean hasHeader = false, hasCpp = false;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        int passes = 0;
        do {
            manager.reindex(cproject);
            org.eclipse.core.runtime.jobs.Job.getJobManager().join(manager, new NullProgressMonitor());
            check(manager.joinIndexer(5000, new NullProgressMonitor()), "indexer completed");
            index.acquireReadLock();
            try {
                hasHeader = index.findBindings(Pattern.compile("k_sleep"), false, IndexFilter.ALL,
                        new NullProgressMonitor()).length > 0;
                hasCpp = index.findBindings(Pattern.compile("cpp_user"), false, IndexFilter.ALL,
                        new NullProgressMonitor()).length > 0;
            } finally { index.releaseReadLock(); }
            if (hasHeader && hasCpp) break;
            System.out.println("CDT index not populated yet (pass " + ++passes
                    + ", header=" + hasHeader + ", C++=" + hasCpp + "); retry after model jobs");
            Thread.sleep(200);
        } while (System.nanoTime() < deadline);
        check(hasHeader, "header function indexed for symbol navigation");
        check(hasCpp, "C++ source indexed");
        index.acquireReadLock();
        try {
            var tu = (ITranslationUnit) CoreModel.getDefault().create(project.getFile("src/main.c"));
            var ast = tu.getAST(index, 0); check(ast != null, "editor AST created");
            check(Arrays.stream(ast.getIncludeDirectives()).allMatch(IASTPreprocessorIncludeStatement::isResolved), "Zephyr header resolved");
            int[] problems = {0};
            ast.accept(new ASTVisitor() {
                { shouldVisitNames = true; }
                @Override public int visit(IASTName name) { if (name.resolveBinding() instanceof IProblemBinding) { System.out.println("Problem name: " + name); problems[0]++; } return PROCESS_CONTINUE; }
            });
            check(problems[0] == 0, "source bindings resolve, including forced header types");
        } finally { index.releaseReadLock(); }
        check(Files.isRegularFile(fixture.resolve(".cproject")), "CDT settings persisted");
        workspace.save(true, new NullProgressMonitor());
        System.out.println("HEADLESS CDT EDITOR CHECKS PASSED");
        if (Boolean.getBoolean("zew.nativeFlow")) RemoteDebugSmoke.run(fixture);
        return IApplication.EXIT_OK;
    }
    @Override public void stop() {}
}
