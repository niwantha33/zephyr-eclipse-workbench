// SPDX-License-Identifier: Apache-2.0
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import io.github.niwantha33.zew.core.RegisterPresentation;
import io.github.niwantha33.zew.core.SvdDevice;

public final class RegisterPresentationTests {
    private static SvdDevice.Register register(String access) throws Exception {
        String xml = "<device><name>Chip</name><size>32</size><peripherals>"
                + "<peripheral><name>GPIOB</name><baseAddress>0x400A2000</baseAddress><registers>"
                + "<register><name>DOUT23_20</name><addressOffset>0x314</addressOffset>"
                + "<access>" + access + "</access></register></registers></peripheral></peripherals></device>";
        return SvdDevice.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .registers().get(0);
    }
    private static void check(boolean b, String reason) { if (!b) throw new AssertionError(reason); }
    public static void main(String[] args) throws Exception {
        var output = register("write-only");
        check(RegisterPresentation.state(output, null, null).equals("Write only"), "output status");
        check(RegisterPresentation.explanation(output, null).contains("does not mean zero"),
                "write-only is explained without a made-up zero");
        var input = register("read-only");
        check(RegisterPresentation.state(input, null, null).equals("Not sampled"), "unread status");
        check(RegisterPresentation.state(input, BigInteger.ZERO, null).equals("Read OK"),
                "zero is valid after a successful GDB read");
        check(RegisterPresentation.state(input, null, "GDB timeout").equals("Read failed"),
                "read failure is not converted to a zero");
        System.out.println("RegisterPresentationTests PASS");
    }
}
