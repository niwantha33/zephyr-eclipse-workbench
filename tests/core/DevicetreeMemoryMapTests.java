// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.DevicetreeMemoryMap;
import java.math.BigInteger;
import java.util.List;

public final class DevicetreeMemoryMapTests {
    private static int checks;
    private static void check(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
        checks++;
    }
    private static DevicetreeMemoryMap.Region one(
            DevicetreeMemoryMap.Report r, String path) {
        return r.regions().stream().filter(v -> v.path().equals(path))
                .findFirst().orElseThrow(() -> new AssertionError(path));
    }
    public static void main(String[] args) {
        var absent = DevicetreeMemoryMap.inspect("");
        check(absent.regions().isEmpty() && absent.notice().contains("Build first"),
                "missing build is explicit and never fabricated");
        String dts = """
                /dts-v1/;
                / {
                    #address-cells = <1>;
                    #size-cells = <1>;
                    chosen {
                        zephyr,flash = &flash0;
                        zephyr,sram = &sram0;
                    };
                    memory@20000000 {
                        compatible = "mmio-sram";
                        reg = <0x20000000 0x00008000>;
                        status = "okay";
                    };
                    soc {
                        #address-cells = <1>;
                        #size-cells = <1>;
                        ranges;
                        flash0: flash@00000000 {
                            compatible = "soc-nv-flash";
                            reg = <0x00000000 0x00020000>;
                            status = "okay";
                            partitions {
                                compatible = "fixed-partitions";
                                #address-cells = <1>;
                                #size-cells = <1>;
                                partition@1000 {
                                    label = "application";
                                    reg = <0x1000 0x7000>;
                                };
                            };
                        };
                        uart@40000000 {
                            compatible = "ti,mspm0-uart";
                            reg = <0x40000000 0x1000>;
                            status = "disabled";
                        };
                        gpio@400a0000 {
                            compatible = "ti,mspm0-gpio";
                            reg = <0x400a0000 0x1000>, <0x400a2000 0x1000>;
                            reg-names = "porta", "portb";
                            status = "okay";
                        };
                    };
                    reserved-memory {
                        #address-cells = <1>;
                        #size-cells = <1>;
                        ranges;
                        reserved@20006000 {
                            reg = <0x20006000 0x1000>;
                        };
                    };
                };
                """;
        var r = DevicetreeMemoryMap.inspect(dts);
        check(one(r,"/memory@20000000").cpuAddress().equals(new BigInteger("20000000",16))
                && one(r,"/memory@20000000").sizeLabel().equals("32 KiB"),
                "exact SRAM size and CPU base");
        check(one(r,"/soc/flash@00000000").cpuAddress().equals(BigInteger.ZERO)
                && one(r,"/soc/flash@00000000").kind()==DevicetreeMemoryMap.Kind.FLASH
                && one(r,"/soc/flash@00000000").endHex().equals("0x00020000"),
                "flash end is exclusive and no fabricated address");
        check(one(r,"/soc/flash@00000000/partitions/partition@1000")
                .cpuAddress().equals(BigInteger.valueOf(0x1000))
                && one(r,"/soc/flash@00000000/partitions/partition@1000")
                .kind() == DevicetreeMemoryMap.Kind.PARTITION,
                "partition offset adds to mapped containing flash");
        check(one(r,"/soc/uart@40000000").status().contains("disabled"),
                "disabled peripheral remains explicitly described, not runtime-used");
        var gpio = r.regions().stream().filter(v -> v.path().equals("/soc/gpio@400a0000")).toList();
        check(gpio.size()==2 && gpio.get(0).registerName().equals("porta")
                && gpio.get(1).registerName().equals("portb"),
                "all multiple reg ranges and names retained");
        check(one(r,"/reserved-memory/reserved@20006000").kind()
                == DevicetreeMemoryMap.Kind.RESERVED,
                "reserved RAM is distinct from available RAM");
        check(r.cpuRegions().size() == r.regions().size() && r.unmappable() == 0,
                "identity ranges allow safely mapped CPU addresses");
        String translated = """
                /dts-v1/;
                / {
                    #address-cells = <1>;
                    #size-cells = <1>;
                    bridge {
                        #address-cells = <1>;
                        #size-cells = <1>;
                        ranges = <0x0 0x50000000 0x10000>;
                        uart@200 {
                            reg = <0x200 0x100>;
                        };
                    };
                    no-map-bus {
                        #address-cells = <1>;
                        #size-cells = <1>;
                        device@400 {
                            reg = <0x400 0x100>;
                        };
                    };
                };
                """;
        var mapped = DevicetreeMemoryMap.inspect(translated);
        check(one(mapped,"/bridge/uart@200").cpuAddress().equals(
                BigInteger.valueOf(0x50000200L)), "explicit ranges translate child bus to CPU");
        check(!one(mapped,"/no-map-bus/device@400").cpuMapped()
                && mapped.unmappable()==1
                && one(mapped,"/no-map-bus/device@400").addressSpace().equals("/no-map-bus"),
                "absent bus ranges never guessed as physical address");
        String wide = """
                /dts-v1/;
                / {
                    #address-cells = <2>;
                    #size-cells = <2>;
                    memory@100000000 {
                        reg = <0x1 0x0 0x0 0x200000>;
                    };
                    id-bus {
                        #address-cells = <1>;
                        #size-cells = <0>;
                        child@2 { reg = <0x2>; };
                    };
                };
                """;
        var r64=DevicetreeMemoryMap.inspect(wide);
        check(one(r64,"/memory@100000000").cpuHex().equals("0x100000000")
                && one(r64,"/memory@100000000").sizeLabel().equals("2 MiB"),
                "correct 64-bit address and size composition without int overflow");
        check(r64.omitted()>0, "zero size-cells/identity regs do not become memory ranges");
        check(r.notice().contains("NOT the vendor's full silicon map"),
                "honest scope marker: not full datasheet memory map");
        System.out.println("PASS build-backed SoC memory map: " + checks);
    }
}
