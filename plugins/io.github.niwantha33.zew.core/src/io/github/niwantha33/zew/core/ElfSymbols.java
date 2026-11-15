// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Bounded, architecture-neutral ELF symbol evidence for selected Zephyr builds.
 * This is NOT a debugger, an RTT scanner, or evidence of what firmware is
 * currently flashed. ArmElfSymbols remains the separate fault addr2line tool.
 */
public final class ElfSymbols {
    private static final long MAX_ELF = 512L * 1024 * 1024;
    private static final long MAX_SYMBOL_BYTES = 32L * 1024 * 1024;
    private static final int MAX_STRING_BYTES = 8 * 1024 * 1024;
    private static final int MAX_SECTIONS = 4096;
    private static final int MAX_SYMBOLS = 500000;
    private static final int MAX_REPORTED_HOOKS = 64;

    public static final int ARM = 40, XTENSA = 94, RISCV = 243;

    public record Report(Path elf, int elfClass, int machine, String sha256,
                         Long seggerRttAddress, List<String> ctfHooks) {
        public Report { ctfHooks = List.copyOf(ctfHooks); }
        public boolean hasRttControlBlock() { return seggerRttAddress != null; }
        public boolean hasCtfHooks() { return !ctfHooks.isEmpty(); }
        public String rttHex() {
            return seggerRttAddress == null ? "not found"
                    : "0x" + Long.toUnsignedString(seggerRttAddress, 16);
        }
        /** A matched symbol is still not a proven live transport. */
        public boolean hasSymbolCandidates() {
            return hasRttControlBlock() && hasCtfHooks();
        }
        public BigInteger rttUnsignedAddress() {
            return seggerRttAddress == null ? null
                    : new BigInteger(Long.toUnsignedString(seggerRttAddress));
        }
    }

    private record Section(int type, long flags, long address, long offset,
                           long size, int link, long entrySize) {
        boolean allocated() { return (flags & 2) != 0; }
    }
    private ElfSymbols() {}

    public static Report inspect(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file))
            throw new IOException("Select a regular, non-symlink ELF.");
        var before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        long length = before.size();
        if (length < 52 || length > MAX_ELF)
            throw new IOException("Selected ELF is outside the 52 byte..512 MiB limit.");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer ident = read(channel, 0, 16, ByteOrder.LITTLE_ENDIAN);
            if (ident.get(0) != 0x7f || ident.get(1) != 'E' || ident.get(2) != 'L'
                    || ident.get(3) != 'F' || ident.get(6) != 1)
                throw new IOException("Invalid ELF identification.");
            int elfClass = Byte.toUnsignedInt(ident.get(4));
            int data = Byte.toUnsignedInt(ident.get(5));
            if ((elfClass != 1 && elfClass != 2) || (data != 1 && data != 2))
                throw new IOException("Unsupported ELF class/byte order.");
            ByteOrder order = data == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
            ByteBuffer h = read(channel, 0, elfClass == 1 ? 52 : 64, order);
            int type = Short.toUnsignedInt(h.getShort(16));
            int machine = Short.toUnsignedInt(h.getShort(18));
            if ((type != 2 && type != 3) || (machine != ARM && machine != XTENSA && machine != RISCV))
                throw new IOException("Expected executable ARM, Xtensa or RISC-V ELF.");
            long shoff = elfClass == 1 ? Integer.toUnsignedLong(h.getInt(32)) : h.getLong(40);
            int entrySize = Short.toUnsignedInt(h.getShort(elfClass == 1 ? 46 : 58));
            int sectionCount = Short.toUnsignedInt(h.getShort(elfClass == 1 ? 48 : 60));
            int headerMinimum = elfClass == 1 ? 40 : 64;
            if (sectionCount < 2 || sectionCount > MAX_SECTIONS
                    || entrySize < headerMinimum || entrySize > 256
                    || !range(shoff, (long) entrySize * sectionCount, length))
                throw new IOException("Invalid/extended ELF section table.");
            Section[] sections = new Section[sectionCount];
            for (int index = 0; index < sections.length; index++) {
                ByteBuffer s = read(channel, shoff + (long) index * entrySize, headerMinimum, order);
                int kind = s.getInt(4);
                long flags = elfClass == 1 ? Integer.toUnsignedLong(s.getInt(8)) : s.getLong(8);
                long address = elfClass == 1 ? Integer.toUnsignedLong(s.getInt(12)) : s.getLong(16);
                long offset = elfClass == 1 ? Integer.toUnsignedLong(s.getInt(16)) : s.getLong(24);
                long size = elfClass == 1 ? Integer.toUnsignedLong(s.getInt(20)) : s.getLong(32);
                int link = elfClass == 1 ? s.getInt(24) : s.getInt(40);
                long itemSize = elfClass == 1 ? Integer.toUnsignedLong(s.getInt(36)) : s.getLong(56);
                if (kind != 8 && !range(offset, size, length))
                    throw new IOException("ELF section lies beyond file.");
                sections[index] = new Section(kind, flags, address, offset, size, link, itemSize);
            }
            Long rtt = null;
            var hooks = new java.util.ArrayList<String>();
            boolean symtabFound = false;
            int totalSymbols = 0;
            for (Section table : sections) {
                if (table.type != 2) continue; // full symbol table, not DYNSYM
                symtabFound = true;
                if (table.link <= 0 || table.link >= sections.length)
                    throw new IOException("Symbol table has an invalid string-table link.");
                Section strings = sections[table.link];
                if (strings.type != 3 || strings.size <= 0 || strings.size > MAX_STRING_BYTES
                        || table.size > MAX_SYMBOL_BYTES || table.entrySize < (elfClass == 1 ? 16 : 24)
                        || table.entrySize > 256 || table.size % table.entrySize != 0)
                    throw new IOException("Unsafe ELF symbol or string table.");
                int count = Math.toIntExact(table.size / table.entrySize);
                if (count > MAX_SYMBOLS - totalSymbols)
                    throw new IOException("ELF symbol count exceeds bound.");
                totalSymbols += count;
                ByteBuffer str = read(channel, strings.offset, (int) strings.size, order);
                byte[] names = str.array();
                if (names[0] != 0) throw new IOException("ELF string table lacks NUL prefix.");
                int minimum = elfClass == 1 ? 16 : 24;
                for (int i = 0; i < count; i++) {
                    ByteBuffer sym = read(channel, table.offset + i * table.entrySize, minimum, order);
                    int nameOffset = sym.getInt(0);
                    int sectionIndex = Short.toUnsignedInt(sym.getShort(elfClass == 1 ? 14 : 6));
                    long address = elfClass == 1
                            ? Integer.toUnsignedLong(sym.getInt(4)) : sym.getLong(8);
                    if (nameOffset <= 0 || nameOffset >= names.length
                            || sectionIndex == 0 || sectionIndex >= sections.length
                            || !sections[sectionIndex].allocated()) continue;
                    Section owner = sections[sectionIndex];
                    // An absolute address with no allocated owner is not RTT
                    // evidence; do not infer values from non-allocated debug info.
                    if (Long.compareUnsigned(address, owner.address) < 0
                            || Long.compareUnsigned(address - owner.address, owner.size) >= 0)
                        continue;
                    int end = nameOffset;
                    while (end < names.length && names[end] != 0 && end - nameOffset <= 255) end++;
                    if (end == names.length || end - nameOffset > 255) continue;
                    String name = new String(names, nameOffset, end - nameOffset,
                            StandardCharsets.US_ASCII);
                    if (name.equals("_SEGGER_RTT")) {
                        if (rtt != null && !rtt.equals(address))
                            throw new IOException("Conflicting allocated _SEGGER_RTT addresses.");
                        rtt = address;
                    } else if ((name.startsWith("ctf_top_") || name.startsWith("sys_trace_"))
                            && hooks.size() < MAX_REPORTED_HOOKS) {
                        if (!hooks.contains(name)) hooks.add(name);
                    }
                }
            }
            if (!symtabFound) throw new IOException("No full ELF symbol table; cannot prove RTT/CTF symbols.");
            var after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !java.util.Objects.equals(before.fileKey(), after.fileKey()))
                throw new IOException("ELF changed during symbol inspection.");
            var sha = digest(channel, length);
            return new Report(file.toAbsolutePath().normalize(), elfClass, machine, sha, rtt, hooks);
        } catch (ArithmeticException overflow) {
            throw new IOException("ELF table has an unrepresentable entry count.", overflow);
        }
    }

    private static boolean range(long offset, long count, long size) {
        return offset >= 0 && count >= 0 && offset <= size && count <= size - offset;
    }
    private static ByteBuffer read(FileChannel ch, long offset, int size, ByteOrder order)
            throws IOException {
        if (offset < 0 || size < 0 || size > MAX_STRING_BYTES)
            throw new IOException("Unbounded ELF read refused.");
        ByteBuffer buffer = ByteBuffer.allocate(size).order(order);
        while (buffer.hasRemaining()) {
            int n = ch.read(buffer, offset + buffer.position());
            if (n <= 0) throw new IOException("Truncated ELF read.");
        }
        return buffer;
    }
    private static String digest(FileChannel ch, long size) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ByteBuffer block = ByteBuffer.allocate(64 * 1024);
            long position = 0;
            while (position < size) {
                block.clear();
                block.limit((int) Math.min(block.capacity(), size - position));
                int got = ch.read(block, position);
                if (got <= 0) throw new IOException("ELF changed while hashing.");
                md.update(block.array(), 0, got);
                position += got;
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
