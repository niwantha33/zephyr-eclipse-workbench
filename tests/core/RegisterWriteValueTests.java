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
        var output = reg("<access>write-only</access>");
        check(!output.readable() && !output.writable() && output.directWritable()
                        && output.writeAllowed(), "write-only data register supports explicit whole write");
        check(RegisterWriteValue.parse(output, "0xAB").equals(BigInteger.valueOf(0xAB)),
                "write-only register accepts bounded numeric data without prior read");
        rejects(() -> RegisterWriteValue.toggle(output, BigInteger.ZERO, 0),
                "write-only register must never read/modify/write an invented snapshot");
        check(!reg("<access>writeOnce</access>").writeAllowed(),
                "write-once command must not be treated as repeatable data");
        check(!reg("<access>write-only</access><modifiedWriteValues>oneToClear</modifiedWriteValues>")
                .writeAllowed(), "write-one-to-clear commands require specialised semantics");
        check(!reg("<access>write-only</access><writeConstraint writeAsRead='true'/>")
                .writeAllowed(), "constrained write-only registers still blocked");
        check(!reg("<access>write-only</access><fields><field><name>RO</name>"
                + "<bitOffset>0</bitOffset><bitWidth>1</bitWidth>"
                + "<access>read-only</access></field></fields>").writeAllowed(),
                "mixed-access write-only field must be blocked");
        check(!reg("<access>read-only</access>").writable(), "read-only register blocked");
        check(!reg("<readAction>clear</readAction>").writable(), "read side effect blocks RMW");
        check(!reg("<modifiedWriteValues>oneToClear</modifiedWriteValues>").writable(),
                "write-one-to-clear requires a specialised writer");
        var constrained = reg("<writeConstraint><range><minimum>0</minimum><maximum>1</maximum>"
                + "</range></writeConstraint>");
        check(constrained.writable(), "understood range constraints can be validated");
        rejects(() -> RegisterWriteValue.parse(constrained, "3"), "out-of-range write blocked");
        var writeAsReadOnly = reg("<writeConstraint writeAsRead='true'/>");
        rejects(() -> RegisterWriteValue.parse(writeAsReadOnly, "1"),
                "writeAsRead without a halted prior value must block");
        var enumOnly = reg("<fields><field><name>BIT</name><bitOffset>0</bitOffset>"
                + "<bitWidth>1</bitWidth><writeConstraint useEnumeratedValues='true'/>"
                + "</field></fields>");
        rejects(() -> RegisterWriteValue.parse(enumOnly, "1"),
                "unknown enumerated field values must block edits");
        check(!reg("<fields><field><name>STATUS</name><bitOffset>0</bitOffset>"
                + "<bitWidth>1</bitWidth><access>read-only</access></field></fields>").writable(),
                "mixed-access field register blocked");
        check(!reg("<fields><field><name>FLAG</name><bitOffset>0</bitOffset>"
                + "<bitWidth>1</bitWidth><modifiedWriteValues>oneToClear</modifiedWriteValues>"
                + "</field></fields>").writable(), "field one-to-clear semantics blocked");
        var range = reg("<writeConstraint><range><minimum>2</minimum><maximum>4</maximum>"
                + "</range></writeConstraint>");
        check(RegisterWriteValue.parse(range, "3").equals(BigInteger.valueOf(3)),
                "valid bounded range accepted");
        rejects(() -> RegisterWriteValue.parse(range, "5"), "out-of-range writes blocked");
        var readback = reg("<writeConstraint writeAsRead='true'/>");
        rejects(() -> RegisterWriteValue.parse(readback, "2"), "writeAsRead requires prior value");
        check(RegisterWriteValue.parse(readback, "2", BigInteger.valueOf(2))
                .equals(BigInteger.valueOf(2)), "writeAsRead with matching snapshot");
        var w1c = reg("<modifiedWriteValues>oneToClear</modifiedWriteValues>"
                + "<fields><field><name>ACK</name><bitOffset>3</bitOffset><bitWidth>1</bitWidth>"
                + "</field></fields>");
        check(RegisterWriteValue.specialBit(w1c, w1c.fields().get(0))
                .equals(BigInteger.valueOf(8)), "W1C sends only the selected flag bit");
        var w0c = reg("<modifiedWriteValues>zeroToClear</modifiedWriteValues>"
                + "<fields><field><name>ACK</name><bitOffset>3</bitOffset><bitWidth>1</bitWidth>"
                + "</field></fields>");
        check(RegisterWriteValue.specialBit(w0c, w0c.fields().get(0))
                .equals(new BigInteger("FFFFFFF7", 16)),
                "W0C preserves all other bits using neutral ones");
        var mixed = reg("<fields><field><name>ACK</name><bitOffset>0</bitOffset>"
                + "<bitWidth>1</bitWidth><modifiedWriteValues>oneToClear</modifiedWriteValues>"
                + "</field><field><name>CONFIG</name><bitOffset>1</bitOffset>"
                + "<bitWidth>1</bitWidth></field></fields>");
        rejects(() -> RegisterWriteValue.specialBit(mixed, mixed.fields().get(0)),
                "mixed W1C and ordinary register fields must not be clobbered");
        System.out.println("RegisterWriteValueTests PASS");
    }
}
