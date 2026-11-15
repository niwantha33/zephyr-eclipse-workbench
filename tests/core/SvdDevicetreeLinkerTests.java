// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.SvdDevice;
import io.github.niwantha33.zew.core.SvdDevicetreeLinker;
import io.github.niwantha33.zew.core.DevicetreeHardware;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.util.List;

public final class SvdDevicetreeLinkerTests {
    private static void check(boolean yes, String message) {
        if (!yes) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        String xml = "<device><name>Chip</name><size>32</size><peripherals>"
                + "<peripheral><name>UART0</name><baseAddress>0x40001000</baseAddress><registers>"
                + "<register><name>STAT</name><addressOffset>0x4</addressOffset></register>"
                + "</registers></peripheral>"
                + "<peripheral><name>GPIOB</name><baseAddress>0x400A2000</baseAddress><registers>"
                + "<register><name>DIN</name><addressOffset>0x100</addressOffset></register>"
                + "</registers></peripheral></peripherals></device>";
        var svd = SvdDevice.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        var nodes = List.of(
                new DevicetreeHardware.Device("/soc", "soc", "", "", "okay", "", 1),
                new DevicetreeHardware.Device("/soc/uart@40001000", "uart@40001000",
                        "", "vendor,uart", "disabled", "<0x40001000 0x100>", 2),
                new DevicetreeHardware.Device("/soc/gpio@400a2000", "gpio@400a2000",
                        "", "vendor,gpio", "okay", "<0x400A2000 0x1000>", 3));
        var map = SvdDevicetreeLinker.link(svd, nodes);
        check(map.get("UART0").state() == SvdDevicetreeLinker.State.DISABLED_IN_DTS,
                "disabled UART must not be shown as active");
        check(map.get("GPIOB").state() == SvdDevicetreeLinker.State.ENABLED_IN_DTS,
                "mapped GPIOB may be labelled DTS enabled");
        var parentOff = List.of(
                new DevicetreeHardware.Device("/soc", "soc", "", "", "disabled", "", 1),
                nodes.get(1), nodes.get(2));
        check(SvdDevicetreeLinker.link(svd, parentOff).get("GPIOB").state()
                == SvdDevicetreeLinker.State.DISABLED_IN_DTS,
                "disabled parent must block peripheral despite own status okay");
        check(SvdDevicetreeLinker.link(svd, List.of()).get("GPIOB").state()
                == SvdDevicetreeLinker.State.UNKNOWN,
                "missing evidence must not claim peripheral is active");
        var invalid = List.of(new DevicetreeHardware.Device("/soc/uart", "uart",
                "", "", "okay", "<&some_phandle 0x40001000>", 1));
        check(SvdDevicetreeLinker.link(svd, invalid).get("UART0").state()
                == SvdDevicetreeLinker.State.UNKNOWN,
                "unknown phandle expression cannot be used to claim a match");
        System.out.println("SvdDevicetreeLinkerTests PASS");
    }
}
