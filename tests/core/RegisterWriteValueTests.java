// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.RegisterWriteValue;
import io.github.niwantha33.zew.core.SvdDevice;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

public final class RegisterWriteValueTests {
    private static SvdDevice.Register reg(String additions) throws Exception {
        String xml = "<device><name>Chip</name><size>32</size><access>read-write</access><peripherals>"
                + "<peripheral><name>GPIO</name><baseAddress>0x40000000</baseAddress><registers>"
                + "<register><name>DATA</name><addressOffset>0</addressOffset>" + additions
                + "</register></registers></peripheral></peripherals></device>";
        return SvdDevice.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .registers().get(0);
    }
    private static void check(boolean okay, String reason) {
        if (!okay) throw new AssertionError(reason);
    }
    private static void rejects(Runnable action, String reason) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(reason);
    }
    public static void main(String[] args) throws Exception {
        var r = reg("<fields><field><name>BIT0</name><bitOffset>0</bitOffset>"
                + "<bitWidth>1</bitWidth></field></fields>");
        check(r.writable(), "plain read-write register can be explicitly edited");
        check(RegisterWriteValue.parse(r, "0xFFFFFFFF").equals(new BigInteger("FFFFFFFF", 16)),
                "full-width unsigned hex accepted");
        check(RegisterWriteValue.parse(r, "255").equals(BigInteger.valueOf(255)),
                "decimal accepted");
        check(RegisterWriteValue.toggle(r, BigInteger.ZERO, 0).equals(BigInteger.ONE),
                "bit toggle on known snapshot");
        rejects(() -> RegisterWriteValue.parse(r, "0x100000000"), "overflow rejected");
        rejects(() -> RegisterWriteValue.parse(r, "-1"), "signed input rejected");
        rejects(() -> RegisterWriteValue.parse(r, "not_a_number"), "injection rejected");
        rejects(() -> RegisterWriteValue.toggle(r, null, 0), "no made-up zero snapshot");
        rejects(() -> RegisterWriteValue.toggle(r, BigInteger.ONE, 32), "out-of-range bit rejected");
        check(!reg("<access>read-only</access>").writable(), "read-only register blocked");
        check(!reg("<readAction>clear</readAction>").writable(), "read side effect blocks RMW");
        check(!reg("<modifiedWriteValues>oneToClear</modifiedWriteValues>").writable(),
                "write-one-to-clear requires a specialised writer");
        check(!reg("<writeConstraint><range><minimum>0</minimum><maximum>1</maximum>"
                + "</range></writeConstraint>").writable(), "constrained writes blocked");
        check(!reg("<fields><field><name>STATUS</name><bitOffset>0</bitOffset>"
                + "<bitWidth>1</bitWidth><access>read-only</access></field></fields>").writable(),
                "mixed-access field register blocked");
        check(!reg("<fields><field><name>FLAG</name><bitOffset>0</bitOffset>"
                + "<bitWidth>1</bitWidth><modifiedWriteValues>oneToClear</modifiedWriteValues>"
                + "</field></fields>").writable(), "field one-to-clear semantics blocked");
        System.out.println("RegisterWriteValueTests PASS");
    }
}
