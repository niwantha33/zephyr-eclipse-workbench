// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.ConfigurationStudioFiles;
import java.nio.file.*;
import java.util.Comparator;

public final class ConfigurationStudioTests {
    private static void ok(boolean yes, String why) {
        if (!yes) throw new AssertionError(why);
    }
    private static void rejects(Throwing action) throws Exception {
        try { action.run(); } catch (java.io.IOException expected) { return; }
        throw new AssertionError("Unsafe operation succeeded");
    }
    private interface Throwing { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("zew-studio space ");
        try {
            var encode = java.util.Base64.getEncoder();
            String[] kfields = {"ADC", "bool", "n", "n", "n", "GPIO && SOC_ADC",
                    "n", "Analog converter", "drivers/adc/Kconfig:12", "",
                    "", "Analog input sample engine"};
            StringBuilder message = new StringBuilder("ZEW-KCONFIG-1\n");
            for (int i = 0; i < kfields.length; i++) {
                if (i > 0) message.append('\t');
                message.append(encode.encodeToString(
                        kfields[i].getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            var parsed = io.github.niwantha33.zew.core.KconfigInspector.parse(message.toString());
            ok(parsed.available() && !parsed.details().canRequest("y")
                    && parsed.details().canRequest("n"),
                    "Kconfiglib assignment restrictions block unavailable Enable requests");
            ok(parsed.details().describe().contains("GPIO && SOC_ADC")
                    && parsed.details().describe().contains("drivers/adc/Kconfig:12"),
                    "native help retains actual source and direct dependency expression");
            ok(!io.github.niwantha33.zew.core.KconfigInspector.parse(
                    "ZEW-KCONFIG-UNAVAILABLE\nNo generated build").available(),
                    "missing generated Kconfig never reports invented dependency results");
            rejects(() -> io.github.niwantha33.zew.core.KconfigInspector.parse(
                    "ZEW-KCONFIG-1\nnot-valid"));
            Path app = root.resolve("existing application");
            Path build = app.resolve(".zew-build/debug-target");
            Files.createDirectories(build.resolve("zephyr"));
            Files.writeString(app.resolve("prj.conf"),
                    "# Original notes\r\nCONFIG_LOG=y\r\nCONFIG_LOG=y\r\nCONFIG_DEBUG_INFO=y\r\n");
            Files.writeString(build.resolve("zephyr/.config"),
                    "CONFIG_LOG=y\n# CONFIG_DEBUG_THREAD_INFO is not set\n");
            Files.writeString(build.resolve("zephyr/zephyr.dts"), "/ { model = \"Board\"; };\n");
            var beforeSetup = ConfigurationStudioFiles.inspect(app, null, "Source-only");
            ok(beforeSetup.options().stream().anyMatch(o -> o.symbol().equals("DEBUG_INFO")
                    && "y".equals(o.requested()) && o.effective() == null),
                    "prj.conf remains open for repair before any SDK or board is configured");
            ok(beforeSetup.devicetree().isEmpty(), "source-only mode does not invent hardware");
            var repairWithoutSdk = ConfigurationStudioFiles.plan(app, null, "DEBUG_INFO", null);
            ok(!repairWithoutSdk.updated().contains("CONFIG_DEBUG_INFO"),
                    "remove invalid existing assignment without a configured build");
            rejects(() -> ConfigurationStudioFiles.plan(app, null, "UNVERIFIED_OPTION", "y"));
            var hardware = io.github.niwantha33.zew.core.DevicetreeHardware.inspect("""
                    / {
                        leds {
                            compatible = "gpio-leds";
                            led0: led_0 {
                                gpios = <&gpiob 22 0>;
                                label = "Blue LED";
                                status = "okay";
                            };
                        };
                        adc0: adc@40000000 {
                            compatible = "ti,mspm0-adc";
                            reg = <0x40000000 0x1000>;
                            status = "disabled";
                        };
                    };
                    """);
            ok(hardware.stream().anyMatch(d -> d.path().equals("/leds/led_0")
                    && d.enabled() && "Blue LED".equals(d.label())),
                    "generated Devicetree LED appears in the hardware inventory");
            ok(hardware.stream().anyMatch(d -> d.path().equals("/adc@40000000")
                    && !d.enabled() && d.compatible().contains("mspm0-adc")),
                    "hardware inventory reports disabled ADC without inventing configuration");
            var adcDevice = hardware.stream().filter(d -> d.path().equals("/adc@40000000")).findFirst().orElseThrow();
            ok(adcDevice.line() == 10, "source navigation uses the physical DTS line, not node ordinal");
            ok(io.github.niwantha33.zew.core.DevicetreeHardware.matches(adcDevice, "MSPM0 40000000", "Disabled"),
                    "hardware search combines compatible and address with explicit disabled status");
            ok(!io.github.niwantha33.zew.core.DevicetreeHardware.matches(adcDevice, "adc", "Okay/default"),
                    "hardware status filter excludes explicitly disabled controllers");
            ok(io.github.niwantha33.zew.core.DevicetreeHardware.inspect("").isEmpty(),
                    "missing build must not fabricate hardware");
            var snapshot = ConfigurationStudioFiles.inspect(app, build, "Debug (-Og)");
            var domains = io.github.niwantha33.zew.core.ConfigurationStudioDomains.TABS;
            ok(domains.size() >= 11
                    && domains.stream().anyMatch(d -> d.id().equals("adc"))
                    && domains.stream().anyMatch(d -> d.id().equals("gpio"))
                    && domains.stream().anyMatch(d -> d.id().equals("rtos")),
                    "dedicated peripheral, debug and RTOS tabs are registered");
            var adcSymbol = new ConfigurationStudioFiles.Option("ADC", "y", null, 0);
            var adcOptional = new ConfigurationStudioFiles.Option("ADC_ASYNC", "n", null, 0);
            var gpioSymbol = new ConfigurationStudioFiles.Option("GPIO", "y", null, 0);
            var demo = new ConfigurationStudioFiles.Snapshot(
                    java.util.List.of(adcSymbol, adcOptional, gpioSymbol),
                    "", "Test", null);
            var groups = io.github.niwantha33.zew.core.ConfigurationStudioDomains.TABS;
            ok(io.github.niwantha33.zew.core.ConfigurationStudioDomains.matches(
                    io.github.niwantha33.zew.core.ConfigurationStudioDomains.ADC, adcSymbol),
                    "ADC symbols appear in the ADC tab");
            ok(!io.github.niwantha33.zew.core.ConfigurationStudioDomains.matches(
                    io.github.niwantha33.zew.core.ConfigurationStudioDomains.GPIO, adcSymbol),
                    "ADC does not leak into the GPIO tab");
            ok(io.github.niwantha33.zew.core.ConfigurationStudioDomains.matches(
                    io.github.niwantha33.zew.core.ConfigurationStudioDomains.MORE,
                    new ConfigurationStudioFiles.Option("CUSTOM_FEATURE", "y", null, 0)),
                    "unknown Kconfig symbols remain visible under More");
            String guide = io.github.niwantha33.zew.core.ConfigurationStudioDomains.inspect(
                    io.github.niwantha33.zew.core.ConfigurationStudioDomains.ADC, demo);
            ok(guide.contains("CONFIG_ADC = Enabled") && guide.contains("CONFIG_ADC_ASYNC = Disabled"),
                    "peripheral guide describes actual resolved driver and optional settings");
            ok(!guide.contains("DMA has been enabled"),
                    "peripheral guide cannot fabricate automatic DMA configuration");
            ok(io.github.niwantha33.zew.core.ConfigurationStudioDomains.ADC.example()
                    .contains("ADC_DT_SPEC_GET_BY_IDX"),
                    "ADC code template uses the Devicetree-aware Zephyr ADC API");
            ok(snapshot.options().stream().anyMatch(o -> o.symbol().equals("DEBUG_INFO")
                    && o.unresolved() && o.requestedLine() == 4),
                    "unknown requested symbol is visible even after failed configure");
            ok(snapshot.options().stream().anyMatch(o -> o.symbol().equals("LOG")
                    && "y".equals(o.effective())), "effective value from generated .config");
            ok(snapshot.devicetree().contains("Board"), "compiled devicetree available read-only");
            var noop = ConfigurationStudioFiles.plan(app, build, "DEBUG_INFO", "y");
            // The source has a single DEBUG_INFO assignment; selecting the same
            // value must not reorder the file or create a misleading change.
            ok(!noop.changed(), "same requested value is a true no-op");
            var logPreview = ConfigurationStudioFiles.plan(app, build, "LOG", "n");
            ok(logPreview.updated().startsWith("# Original notes\r\nCONFIG_LOG=n\r\n"),
                    "modified value keeps original line position and CRLF");
            ok(!logPreview.updated().contains("CONFIG_LOG=y"),
                    "duplicate assignment removed safely");
            var remove = ConfigurationStudioFiles.plan(app, build, "DEBUG_INFO", null);
            ok(remove.updated().contains("# Original notes"), "comments retained");
            ok(!remove.updated().contains("CONFIG_DEBUG_INFO"), "problematic line removed from proposed diff");
            var backup = ConfigurationStudioFiles.commit(app, remove);
            ok(Files.readString(backup).contains("CONFIG_DEBUG_INFO=y"), "original file backed up");
            ok(!Files.readString(app.resolve("prj.conf")).contains("CONFIG_DEBUG_INFO"), "repair persisted");
            var before = Files.readString(app.resolve("prj.conf"));
            var changes = ConfigurationStudioFiles.plan(app, build, "LOG", "n");
            Files.writeString(app.resolve("prj.conf"), before + "# external editor change\r\n");
            rejects(() -> ConfigurationStudioFiles.commit(app, changes));
            ok(Files.readString(app.resolve("prj.conf")).contains("external editor change"),
                    "optimistic locking preserves concurrent edits");
            Files.writeString(app.resolve("prj.conf"), before);
            rejects(() -> ConfigurationStudioFiles.plan(app, build, "NOT_A_SYMBOL", "y"));
            rejects(() -> ConfigurationStudioFiles.plan(app, build, "LOG", "y\nCONFIG_INJECT=y"));
            rejects(() -> ConfigurationStudioFiles.plan(app, build, "../CONFIG_LOG", "y"));
            var changed = ConfigurationStudioFiles.plan(app, build, "LOG", "n");
            ok(changed.updated().contains("CONFIG_LOG=n"), "valid effective option editable");
            ok(!changed.updated().contains("CONFIG_LOG=y"), "duplicate requested lines replaced");
            ConfigurationStudioFiles.commit(app, changed);
            ok(Files.readString(app.resolve("prj.conf")).contains("CONFIG_LOG=n"),
                    "edited configuration persists");
            ok(Files.readString(build.resolve("zephyr/.config")).contains("CONFIG_LOG=y"),
                    "build-generated output is never written by GUI");
            Path overlay = app.resolve("app.overlay");
            Files.writeString(overlay, "/* user overlay */\n");
            ConfigurationStudioFiles.inspect(app, build, "Release");
            ok(Files.readString(overlay).equals("/* user overlay */\n"),
                    "hardware configuration is never automatically changed");
            Path zephyr = root.resolve("workspace/zephyr/subsys/logging");
            Files.createDirectories(zephyr);
            Files.writeString(zephyr.resolve("Kconfig"), "menu \"Logging\"\n"
                    + "config LOG\n    bool \"Logging enabled\"\n"
                    + "    depends on PRINTK\n    help\n      Enables subsystem logging.\n"
                    + "config OTHER\n    bool \"Other\"\n");
            var sourceHelp = io.github.niwantha33.zew.core.KconfigSourceHelp.firstDefinition(
                    root.resolve("workspace"), "LOG");
            ok(sourceHelp != null && sourceHelp.line() == 2
                    && sourceHelp.sourceExcerpt().contains("depends on PRINTK")
                    && !sourceHelp.sourceExcerpt().contains("config OTHER"),
                    "definition lookup gives source help without including next symbol");
            ok(io.github.niwantha33.zew.core.KconfigSourceHelp.firstDefinition(
                    root.resolve("workspace"), "NONEXISTENT") == null,
                    "missing metadata must not pretend to evaluate Kconfig availability");
            rejects(() -> io.github.niwantha33.zew.core.KconfigSourceHelp.firstDefinition(
                    root.resolve("workspace"), "BAD/NAME"));
            var presentation = io.github.niwantha33.zew.core.ConfigurationStudioPresentation.class;
            var overridden = new io.github.niwantha33.zew.core.ConfigurationStudioFiles.Option("LOG", "y", "y", 3);
            var invalid = new io.github.niwantha33.zew.core.ConfigurationStudioFiles.Option("DEBUG_INFO", null, "y", 2);
            var inherited = new io.github.niwantha33.zew.core.ConfigurationStudioFiles.Option("GPIO", "n", null, 0);
            ok(io.github.niwantha33.zew.core.ConfigurationStudioPresentation.title("LOG").equals("System logging"),
                    "user-friendly setting title");
            ok(io.github.niwantha33.zew.core.ConfigurationStudioPresentation.current(overridden).equals("Enabled"),
                    "boolean y is displayed as Enabled");
            ok(io.github.niwantha33.zew.core.ConfigurationStudioPresentation.current(inherited).equals("Disabled"),
                    "boolean n is displayed as Disabled");
            ok(io.github.niwantha33.zew.core.ConfigurationStudioPresentation.matches(overridden,
                    io.github.niwantha33.zew.core.ConfigurationStudioPresentation.MINE, "logging"),
                    "search by plain English finds project choices");
            ok(io.github.niwantha33.zew.core.ConfigurationStudioPresentation.matches(overridden,
                    io.github.niwantha33.zew.core.ConfigurationStudioPresentation.ALL, "CONFIG_LOG"),
                    "pasted CONFIG_ symbols are searchable without manual prefix removal");
            ok(io.github.niwantha33.zew.core.ConfigurationStudioPresentation.matches(
                    new ConfigurationStudioFiles.Option("MAIN_STACK_SIZE", "4096", null, 0),
                    io.github.niwantha33.zew.core.ConfigurationStudioPresentation.ALL, "4096"),
                    "effective build values are searchable without a project override");
            ok(io.github.niwantha33.zew.core.ConfigurationStudioPresentation.matches(invalid,
                    io.github.niwantha33.zew.core.ConfigurationStudioPresentation.REVIEW, "debug"),
                    "unresolved symbol appears under Needs review");
            ok(!io.github.niwantha33.zew.core.ConfigurationStudioPresentation.matches(inherited,
                    io.github.niwantha33.zew.core.ConfigurationStudioPresentation.MINE, ""),
                    "inherited setting must not appear under My changes");
            ok(io.github.niwantha33.zew.core.ConfigurationStudioPresentation.category("GPIO").equals(
                    io.github.niwantha33.zew.core.ConfigurationStudioPresentation.DRIVERS),
                    "drivers category is correct");
            var binary = new io.github.niwantha33.zew.core.ConfigurationStudioFiles.Option(
                    "DEBUG_THREAD_INFO", "n", null, 0);
            var requestedEnable = new io.github.niwantha33.zew.core.ConfigurationStudioFiles.Option(
                    "DEBUG_THREAD_INFO", "n", "y", 12);
            var numeric = new io.github.niwantha33.zew.core.ConfigurationStudioFiles.Option(
                    "MAIN_STACK_SIZE", "2048", "4096", 7);
            ok(io.github.niwantha33.zew.core.ConfigurationStudioPresentation.isToggle(overridden),
                    "Enable/Disable allowed for an effective y switch");
            ok(io.github.niwantha33.zew.core.ConfigurationStudioPresentation.isToggle(binary),
                    "Enable/Disable allowed for an effective n switch");
            ok(io.github.niwantha33.zew.core.ConfigurationStudioPresentation.inlineValue(binary)
                    .contains("Enable  |  ● Disable"),
                    "both inline on/off choices are displayed together in the value column");
            ok(io.github.niwantha33.zew.core.ConfigurationStudioPresentation.inlineValue(requestedEnable)
                    .contains("pending build"),
                    "inline value warns when requested value differs from last generated build");
            ok(!io.github.niwantha33.zew.core.ConfigurationStudioPresentation.isToggle(numeric),
                    "non-boolean numeric options cannot be toggled with y/n");
            ok(!io.github.niwantha33.zew.core.ConfigurationStudioPresentation.isToggle(invalid),
                    "unresolved options cannot be enabled before Kconfig validation");
            ok(!io.github.niwantha33.zew.core.ConfigurationStudioPresentation.isToggle(null),
                    "missing selection cannot be toggled");
            ok(io.github.niwantha33.zew.core.ConfigurationStudioPresentation.requested(requestedEnable)
                    .equals("Enable"), "requested enabled value is visible independently of effective state");
            ok(io.github.niwantha33.zew.core.ConfigurationStudioPresentation.requested(binary)
                    .equals("Inherited"), "no explicit override is shown as inherited");
            ok(io.github.niwantha33.zew.core.ConfigurationStudioPresentation.review(requestedEnable)
                    .equals("Verify with build"), "requested and effective mismatch requires build validation");
            ok(io.github.niwantha33.zew.core.ConfigurationStudioPresentation.matches(requestedEnable,
                    io.github.niwantha33.zew.core.ConfigurationStudioPresentation.REVIEW, ""),
                    "Needs review includes requested settings that differ from generated effective values");
            ok(!io.github.niwantha33.zew.core.ConfigurationStudioPresentation.matches(binary,
                    io.github.niwantha33.zew.core.ConfigurationStudioPresentation.REVIEW, ""),
                    "Needs review excludes inherited settings that agree with the generated configuration");
            ok(io.github.niwantha33.zew.core.ConfigurationStudioPresentation.review(invalid)
                    .equals("Unresolved"), "invalid setting is visibly marked unresolved");
            var requestOn = ConfigurationStudioFiles.plan(app, build, "DEBUG_THREAD_INFO", "y");
            ok(requestOn.updated().contains("CONFIG_DEBUG_THREAD_INFO=y"),
                    "Enable action emits an explicit yes request using the verified selected build");
            var requestOff = ConfigurationStudioFiles.plan(app, build, "DEBUG_THREAD_INFO", "n");
            ok(requestOff.updated().contains("CONFIG_DEBUG_THREAD_INFO=n"),
                    "Disable action emits an explicit no request using the verified selected build");
            var resources = io.github.niwantha33.zew.core.ConfigurationStudioResources.class;
            ok(io.github.niwantha33.zew.core.ConfigurationStudioResources.contextTarget("", true),
                    "project root context menu available");
            for (String file : new String[]{"prj.conf", ".project", "demo.prj",
                    "app.overlay", "boards/custom.overlay"}) {
                ok(io.github.niwantha33.zew.core.ConfigurationStudioResources.contextTarget(file, false),
                        "project configuration context offered for " + file);
            }
            for (String file : new String[]{"main.c", "CMakeLists.txt", "README.md",
                    "../unrelated.prj", "other.txt"}) {
                ok(!io.github.niwantha33.zew.core.ConfigurationStudioResources.contextTarget(file, false),
                        "no irrelevant context menu for " + file);
            }
            ok(io.github.niwantha33.zew.core.ConfigurationStudioResources.projectSettings("prj.conf"),
                    "right-click prj.conf focuses project choices");
            ok(io.github.niwantha33.zew.core.ConfigurationStudioResources.hardware("boards/custom.overlay"),
                    "right-click overlay opens Devicetree tab");
            String peripheralDts = """
                    /* Generated build fixture */
                    / {
                        pinctrl {
                            adc_pins: adc-default {
                                phandle = <0x10>;
                                group1 {
                                    pinmux = <0x1234
                                              0x5678>;
                                    bias-disable;
                                };
                            };
                        };
                        soc {
                            adc@4000 {
                                compatible = "ti,mspm0-adc";
                                status = "disabled";
                                pinctrl-0 = <0x10>;
                                pinctrl-names = "default";
                                channel@0 {
                                    reg = <0>;
                                    zephyr,gain = "ADC_GAIN_1";
                                    zephyr,reference = "ADC_REF_INTERNAL";
                                    zephyr,resolution = <12>;
                                };
                            };
                            uart@5000 {
                                compatible = "vendor,uart";
                                note = "https://example.invalid/device";
                            };
                        };
                    };
                    """;
            var properties = io.github.niwantha33.zew.core.DevicetreeParameters.inspect(peripheralDts);
            var adcParameters = io.github.niwantha33.zew.core.DevicetreeParameters.forDevice(properties, "/soc/adc@4000");
            ok(adcParameters.stream().anyMatch(p -> p.path().equals("/soc/adc@4000/channel@0")
                    && p.name().equals("zephyr,gain") && p.value().equals("\"ADC_GAIN_1\"")),
                    "ADC parameter view retains per-channel values and DTS string syntax");
            ok(adcParameters.stream().noneMatch(p -> p.path().equals("/soc/uart@5000")),
                    "ADC parameters exclude unrelated controllers");
            var routes = io.github.niwantha33.zew.core.DevicetreeParameters.pinRoutes(properties, "/soc/adc@4000");
            ok(routes.size() == 1 && routes.get(0).path().equals("/pinctrl/adc-default/group1")
                    && routes.get(0).value().contains("0x1234") && routes.get(0).value().contains("0x5678"),
                    "chip schematic resolves numeric pinctrl phandles and multiline pinmux without guessing pads");
            ok(io.github.niwantha33.zew.core.DevicetreeParameters.pinRoutes(properties, "/soc/uart@5000").isEmpty(),
                    "a peripheral without pin data never gains invented chip pins");
            ok(properties.stream().anyMatch(p -> p.name().equals("bias-disable") && p.value().isEmpty()),
                    "boolean pinctrl parameters remain boolean");
            ok(properties.stream().anyMatch(p -> p.name().equals("note") && p.value().contains("https://")),
                    "comment stripping preserves slashes inside property strings");
            var resolution = adcParameters.stream().filter(p -> p.name().equals("zephyr,resolution")).findFirst().orElseThrow();
            ok(peripheralDts.split("\\R")[resolution.line() - 1].contains("zephyr,resolution"),
                    "parameter source reveal uses its exact physical source line");
            String request = io.github.niwantha33.zew.core.DevicetreeParameters.overlayRequest(resolution, "<10>");
            ok(request.contains("&{/soc/adc@4000/channel@0}") && request.contains("zephyr,resolution = <10>;"),
                    "overlay draft targets only the selected ADC channel");
            boolean malformedRequestRejected = false;
            try { io.github.niwantha33.zew.core.DevicetreeParameters.overlayRequest(resolution, "<10>; evil;"); }
            catch (IllegalArgumentException expected) { malformedRequestRejected = true; }
            ok(malformedRequestRejected, "overlay draft rejects extra property statements");
            for (String invalidDraft : new String[] { "<10", "<10>>", "<10> /*", "<10> //comment",
                    "<((10)>", "<(10))>", "<10>,", "<10> other", "<10]", "", "\"12\"", "<10>\n" }) {
                boolean rejected = false;
                try { io.github.niwantha33.zew.core.DevicetreeParameters.overlayRequest(resolution, invalidDraft); }
                catch (IllegalArgumentException expected) { rejected = true; }
                ok(rejected, "invalid or shape-changing overlay draft rejected: " + invalidDraft);
            }
            ok(io.github.niwantha33.zew.core.DevicetreeParameters.overlayRequest(resolution,
                    "<(1 << 3)>").contains("<(1 << 3)>"), "balanced cell expressions remain supported");
            var gain = adcParameters.stream().filter(p -> p.name().equals("zephyr,gain")).findFirst().orElseThrow();
            ok(io.github.niwantha33.zew.core.DevicetreeParameters.overlayRequest(gain,
                    "\"ADC_GAIN_1_6\"").contains("ADC_GAIN_1_6"), "quoted ADC enum draft");
            boolean unterminatedString = false;
            try { io.github.niwantha33.zew.core.DevicetreeParameters.overlayRequest(gain, "\"ADC_GAIN_1"); }
            catch (IllegalArgumentException expected) { unterminatedString = true; }
            ok(unterminatedString, "unterminated string cannot swallow overlay closing syntax");
            var url = properties.stream().filter(p -> p.name().equals("note")).findFirst().orElseThrow();
            ok(io.github.niwantha33.zew.core.DevicetreeParameters.overlayRequest(url,
                    "\"https://example.invalid/path\"").contains("https://"), "comment-like text inside strings retained");
            var numericRoute = properties.stream().filter(p -> p.name().equals("pinctrl-0")).findFirst().orElseThrow();
            boolean numericPhandleRejected = false;
            try { io.github.niwantha33.zew.core.DevicetreeParameters.overlayRequest(numericRoute, "<0x10>"); }
            catch (IllegalArgumentException expected) { numericPhandleRejected = true; }
            ok(numericPhandleRejected, "generated phandle identity cannot be copied into portable source");
            boolean mixedPhandleRejected = false;
            try { io.github.niwantha33.zew.core.DevicetreeParameters.overlayRequest(numericRoute, "<0x10 &adc_default>"); }
            catch (IllegalArgumentException expected) { mixedPhandleRejected = true; }
            ok(mixedPhandleRejected, "one symbolic reference does not authorise other numeric pinctrl identities");
            var invalidHandleProperties = new java.util.ArrayList<>(properties);
            invalidHandleProperties.add(new io.github.niwantha33.zew.core.DevicetreeParameters.Property(
                    "/invalid", "phandle", "<999999999999999999999999999>", 1));
            ok(io.github.niwantha33.zew.core.DevicetreeParameters.pinRoutes(invalidHandleProperties,
                    "/soc/adc@4000").equals(routes), "malformed out-of-range phandle does not crash or invent routes");
            ok(io.github.niwantha33.zew.core.DevicetreeParameters.overlayRequest(numericRoute,
                    "<&adc_default>").contains("<&adc_default>"), "symbolic pinctrl reference can be drafted");
            var bytes = new io.github.niwantha33.zew.core.DevicetreeParameters.Property("/", "data", "[00 ff]", 1);
            ok(io.github.niwantha33.zew.core.DevicetreeParameters.overlayRequest(bytes, "[01 aF]").contains("[01 aF]"),
                    "byte array syntax is retained");
            boolean invalidBytes = false;
            try { io.github.niwantha33.zew.core.DevicetreeParameters.overlayRequest(bytes, "[1 123]"); }
            catch (IllegalArgumentException expected) { invalidBytes = true; }
            ok(invalidBytes, "malformed byte array rejected");
            ok(io.github.niwantha33.zew.core.DevicetreeParameters.inspect("").isEmpty(),
                    "an unbuilt project has no fabricated ADC parameters");
            System.out.println("ConfigurationStudioTests PASS");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
