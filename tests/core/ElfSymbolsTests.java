// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.ElfSymbols;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Fake ARM, Xtensa and RISC-V ELF tables; never use symbol names as live proof. */
public final class ElfSymbolsTests {
    private static int checks;
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }

    private static void section(ByteBuffer b, int bits, int index, int type, long flags,
                                long address, long offset, long size, int link, long entsize) {
        int sh = 0x400 + index * (bits == 32 ? 40 : 64);
        b.putInt(sh + 4, type);
        if (bits == 32) {
            b.putInt(sh + 8, (int) flags);
            b.putInt(sh + 12, (int) address);
            b.putInt(sh + 16, (int) offset);
            b.putInt(sh + 20, (int) size);
            b.putInt(sh + 24, link);
            b.putInt(sh + 36, (int) entsize);
        } else {
            b.putLong(sh + 8, flags);
            b.putLong(sh + 16, address);
            b.putLong(sh + 24, offset);
            b.putLong(sh + 32, size);
            b.putInt(sh + 40, link);
            b.putLong(sh + 56, entsize);
        }
    }

    private static Path elf(int bits, int machine, ByteOrder order, boolean rtt, boolean hook)
            throws Exception {
        byte[] names = ("\0_SEGGER_RTT\0sys_trace_k_thread_switched_in\0")
                .getBytes(StandardCharsets.US_ASCII);
        int entry = bits == 32 ? 16 : 24;
        ByteBuffer b = ByteBuffer.allocate(2048).order(order);
        b.put(0, (byte)0x7f); b.put(1, (byte)'E'); b.put(2, (byte)'L'); b.put(3, (byte)'F');
        b.put(4, (byte)(bits == 32 ? 1 : 2));
        b.put(5, (byte)(order == ByteOrder.LITTLE_ENDIAN ? 1 : 2));
        b.put(6, (byte)1);
        b.putShort(16, (short)2);
        b.putShort(18, (short)machine);
        b.putInt(20, 1);
        if (bits == 32) {
            b.putInt(32, 0x400);
            b.putShort(40, (short)52);
            b.putShort(46, (short)40);
            b.putShort(48, (short)5);
        } else {
            b.putLong(40, 0x400);
            b.putShort(52, (short)64);
            b.putShort(58, (short)64);
            b.putShort(60, (short)5);
        }
        section(b, bits, 1, 8, 3, 0x20000000L, 0, 256, 0, 0);
        section(b, bits, 2, 1, 6, 0x08000000L, 0x300, 128, 0, 0);
        section(b, bits, 3, 2, 0, 0, 0x80, entry * 3, 4, entry);
        section(b, bits, 4, 3, 0, 0, 0xd0, names.length, 0, 0);
        b.position(0xd0);
        b.put(names);
        // Empty table entry 0 is already all zeros.
        if (rtt) symbol(b, bits, 0x80 + entry, 1, 0x20000010L, 1);
        if (hook) symbol(b, bits, 0x80 + entry * 2, 1 + "_SEGGER_RTT".length() + 1,
                         0x08000010L, 2);
        Path out = Files.createTempFile("zew-symbols-" + machine, ".elf");
        Files.write(out, b.array());
        return out;
    }

    private static void symbol(ByteBuffer b, int bits, int off, int name, long address, int section) {
        b.putInt(off, name);
        if (bits == 32) {
            b.putInt(off + 4, (int)address);
            b.put(off + 12, (byte)0x11);
            b.putShort(off + 14, (short)section);
        } else {
            b.put(off + 4, (byte)0x11);
            b.putShort(off + 6, (short)section);
            b.putLong(off + 8, address);
        }
    }

    public static void main(String[] args) throws Exception {
        var arm = ElfSymbols.inspect(elf(32, ElfSymbols.ARM, ByteOrder.LITTLE_ENDIAN, true, true));
        check(arm.elfClass() == 1 && arm.machine() == ElfSymbols.ARM
                && arm.hasSymbolCandidates() && arm.rttHex().equals("0x20000010"),
                "ELF32 ARM allocated symbol candidates");
        check(arm.sha256().matches("[0-9a-f]{64}")
                && arm.ctfHooks().contains("sys_trace_k_thread_switched_in"),
                "observed CTF named hook plus complete file digest");
        var xtensa = ElfSymbols.inspect(elf(32, ElfSymbols.XTENSA, ByteOrder.BIG_ENDIAN, true, true));
        check(xtensa.machine() == ElfSymbols.XTENSA && xtensa.hasSymbolCandidates(),
                "ELF32 Xtensa big endian");
        var riscv = ElfSymbols.inspect(elf(64, ElfSymbols.RISCV, ByteOrder.LITTLE_ENDIAN, true, true));
        check(riscv.elfClass() == 2 && riscv.hasSymbolCandidates(),
                "ELF64 RISC-V allocated symbol candidates");
        check(!ElfSymbols.inspect(elf(32, ElfSymbols.ARM, ByteOrder.LITTLE_ENDIAN, false, true))
                .hasRttControlBlock(), "CTF hook alone is not an RTT control block");
        check(!ElfSymbols.inspect(elf(32, ElfSymbols.ARM, ByteOrder.LITTLE_ENDIAN, true, false))
                .hasCtfHooks(), "RTT alone is not CTF instrumentation");
        boolean rejected = false;
        try {
            ElfSymbols.inspect(Files.createTempFile("invalid-elf", ".bin"));
        } catch (IOException expected) { rejected = true; }
        check(rejected, "truncated ELF must fail closed");
        Path corrupt = elf(32, ElfSymbols.ARM, ByteOrder.LITTLE_ENDIAN, true, true);
        byte[] content = Files.readAllBytes(corrupt);
        ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN).putInt(0x400 + 3 * 40 + 16, 0xFFFF);
        Files.write(corrupt, content);
        rejected = false;
        try { ElfSymbols.inspect(corrupt); } catch (IOException expected) { rejected = true; }
        check(rejected, "out of range symtab cannot be trusted");
        System.out.println("PASS generic ELF trace symbol evidence: " + checks);
    }
}
