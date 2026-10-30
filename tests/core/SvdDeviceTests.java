// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.SvdDevice;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;

public final class SvdDeviceTests {
    private static SvdDevice parse(String xml) throws Exception {
        return SvdDevice.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
    private static String device(String body) {
        return "<device><name>TestChip</name><size>32</size><access>read-write</access><resetValue>0x80</resetValue>"
                + "<peripherals>" + body + "</peripherals></device>";
    }
    private static String peripheral(String registers) {
        return "<peripheral><name>GPIO</name><baseAddress>0x40000000</baseAddress><registers>"
                + registers + "</registers></peripheral>";
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(String xml, String message) throws Exception {
        try { parse(xml); } catch (Exception expected) { return; }
        throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        String reg = "<register><name>CTRL</name><addressOffset>4</addressOffset><fields>"
                + "<field><name>EN</name><bitOffset>7</bitOffset><bitWidth>1</bitWidth></field>"
                + "<field><name>MODE</name><bitRange>[3:1]</bitRange></field></fields></register>";
        var d = parse(device(peripheral(reg)));
        var r = d.registers().get(0);
        check(d.name().equals("TestChip") && r.address().equals(new BigInteger("40000004", 16)), "address");
        check(r.size() == 32 && r.reset().intValue() == 128 && r.fields().get(1).width() == 3, "properties");
        check(r.readable(), "readable register");
        var ro = parse(device(peripheral(reg.replace("<fields>", "<readAction>clear</readAction><fields>"))));
        check(!ro.registers().get(0).readable(), "read clear must be blocked");
        var fieldAction = parse(device(peripheral(reg.replace("<name>EN</name>", "<name>EN</name><readAction>modify</readAction>"))));
        check(!fieldAction.registers().get(0).readable(), "field read action blocks parent");
        var wo = parse(device(peripheral(reg.replace("<fields>", "<access>write-only</access><fields>"))));
        check(!wo.registers().get(0).readable(), "write-only must be blocked");
        var once = parse(device(peripheral(reg.replace("<fields>", "<access>writeOnce</access><fields>"))));
        check(!once.registers().get(0).readable(), "writeOnce is not readable");
        var unknownAccess = parse(device(peripheral(reg.replace("<fields>", "<access>vendor-mode</access><fields>"))));
        check(!unknownAccess.registers().get(0).readable(), "unknown access must fail closed");
        var fieldWrite = parse(device(peripheral(reg.replace("<name>MODE</name>",
                "<name>MODE</name><access>write-only</access>"))));
        check(!fieldWrite.registers().get(0).readable(), "write-only field blocks whole-register reads");
        check(fieldWrite.registers().get(0).readBlockReason().contains("field MODE access"), "specific field diagnostic");
        String inheritedAction = reg.replace("<name>EN</name>", "<name>EN</name><readAction>clear</readAction>")
                .replace("<field><name>MODE</name><bitRange>[3:1]</bitRange></field>",
                        "<field derivedFrom='EN'><name>COPY</name><bitRange>[3:1]</bitRange></field>");
        var inherited = parse(device(peripheral(inheritedAction)));
        check(inherited.registers().get(0).fields().get(1).readAction().equals("clear"),
                "derived field retains side effect metadata");
        String derivedOverride = inheritedAction.replace("<bitRange>[3:1]</bitRange>",
                "<bitRange>[3:1]</bitRange><readAction></readAction>");
        check(!parse(device(peripheral(derivedOverride))).registers().get(0).readable(),
                "later field must not erase earlier field read side effect");
        var inheritedAccess = parse(device(peripheral(reg.replace("<fields>", "<access>read-only</access><fields>"))));
        check(inheritedAccess.registers().get(0).fields().get(0).access().equals("read-only"),
                "field inherits register access");
        var derived = parse(device(peripheral(reg) + "<peripheral derivedFrom='GPIO'><name>GPIO2</name><baseAddress>0x50000000</baseAddress></peripheral>"));
        check(derived.registers().size() == 2 && derived.registers().get(1).address().toString(16).equals("50000004"), "peripheral inheritance");
        var rd = parse(device(peripheral(reg + "<register derivedFrom='CTRL'><name>ALT</name><addressOffset>8</addressOffset></register>")));
        check(rd.registers().get(1).fields().size() == 2, "register inheritance");
        var array = parse(device(peripheral("<cluster><name>BANK%s</name><dim>2</dim><dimIncrement>0x100</dimIncrement>"
                + "<dimIndex>1-2</dimIndex><addressOffset>0x20</addressOffset><size>16</size>" + reg + "</cluster>")));
        check(array.registers().size() == 2 && array.registers().get(1).name().equals("BANK2.CTRL"), "cluster arrays");
        check(array.registers().get(1).address().toString(16).equals("40000124") && array.registers().get(1).size() == 16, "cluster inherited size");
        rejects(device(peripheral(reg.replace("<fields>", "<size>64</size><fields>"))), "reject unsupported width");
        rejects(device(peripheral(reg.replace("<bitWidth>1", "<bitWidth>33"))), "reject invalid field");
        rejects(device(peripheral(reg.replace("<addressOffset>4", "<addressOffset>3"))), "reject unaligned address");
        rejects(device(peripheral("<register derivedFrom='NOPE'><name>X</name></register>")), "reject unresolved inheritance");
        rejects(device(peripheral("<register derivedFrom='B'><name>A</name></register><register derivedFrom='A'><name>B</name></register>")), "reject cycles");
        rejects("<!DOCTYPE device [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>" + device(peripheral(reg)), "reject XXE");
        rejects(device(peripheral(reg)).replace("<device>", "<device><addressUnitBits>16</addressUnitBits>"), "reject address unit");
        var noReset = parse(device(peripheral(reg)).replace("<resetValue>0x80</resetValue>", ""));
        check(noReset.registers().get(0).reset() == null, "missing reset must stay unknown");
        var fd = parse(device(peripheral(reg.replace("</fields>",
                "<field derivedFrom='MODE'><name>MODE2</name><bitRange>[6:4]</bitRange></field></fields>"))));
        check(fd.registers().get(0).fields().get(2).offset() == 4, "field inheritance");
        rejects(device(peripheral(reg + reg)), "duplicate register keys");
        rejects(device(peripheral(reg.replace("<fields>", "<dim>4097</dim><dimIncrement>4</dimIncrement><fields>"))), "array bound");
        rejects(device(peripheral(reg.replace("<fields>", "<dim>2</dim><dimIndex>0-5</dimIndex><dimIncrement>4</dimIncrement><fields>"))), "invalid array indices");
        rejects(device(peripheral(reg.replace("<name>EN</name>", "<name>EN</name><dim>2</dim>"))), "unsupported field arrays");
        System.out.println("SvdDeviceTests: PASS");
    }
}
