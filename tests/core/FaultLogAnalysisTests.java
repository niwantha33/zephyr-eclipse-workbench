// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.ArmElfSymbols;
import io.github.niwantha33.zew.core.FaultLogAnalysis;
import java.nio.file.Files;
import java.nio.file.Path;

public class FaultLogAnalysisTests {
    public static void main(String[] args) throws Exception {
        var arch = FaultLogAnalysis.Architecture.ARMV7_M;
        // Register labels follow Zephyr's documented fatal output. Values are synthetic, not hardware capture.
        String log = "[00:00:01.000,000] <err> os: r0/a1: 0x00000000 r1/a2: 0x00000001 r2/a3: 0x00000002\n"
                + "[00:00:01.000,010] <err> os: r3/a4: 0x00000003 r12/ip: 0x0000000c r14/lr: 0x08000109\n"
                + "[00:00:01.000,020] <err> os: xpsr: 0x61000000\n"
                + "[00:00:01.000,030] <err> os: Faulting instruction address (r15/pc): 0x08000104\n"
                + "[00:00:01.000,040] <err> os: >>> ZEPHYR FATAL ERROR 0: CPU exception on CPU 0\n"
                + "CFSR=0x00008200 HFSR=0x40000000 BFAR=0x2000ffff SP=0x20001000\n";
        var report = FaultLogAnalysis.inspect(log, arch);
        check(report.registers().get("PC").value() == 0x08000104L, "Zephyr PC label");
        check(report.registers().get("LR").line() == 2, "Zephyr LR source line");
        check(report.registers().get("R12").value() == 12, "ip alias");
        check(has(report, "PRECISERR"), "precise bus fault");
        check(has(report, "FORCED"), "escalated fault");
        check(has(report, "BFAR is marked valid"), "BFAR validity respected");
        check(!has(report, "stack pointer was"), "explicit SP retained");
        var invalidAddress = FaultLogAnalysis.inspect("CFSR=0x00000400 BFAR=0x20000000 PC=0x1234", arch);
        check(has(invalidAddress, "not marked valid"), "do not interpret invalid BFAR");
        check(has(invalidAddress, "PC need not identify"), "imprecise fault PC limitation");
        var missingAddress = FaultLogAnalysis.inspect("CFSR=0x80", arch);
        check(has(missingAddress, "MMFAR is marked valid but"), "valid address missing");
        var v6 = FaultLogAnalysis.inspect("CFSR=0x200 HFSR=0x40000000 PC=0x1234", FaultLogAnalysis.Architecture.ARMV6_M);
        check(!has(v6, "PRECISERR") && !has(v6, "FORCED"), "M0 does not decode configurable faults");
        var other = FaultLogAnalysis.inspect("CFSR=0x200 PC=0x1234", FaultLogAnalysis.Architecture.UNSPECIFIED);
        check(!has(other, "PRECISERR"), "unknown architectures not decoded");
        check(has(FaultLogAnalysis.inspect("CFSR=0x00100000", FaultLogAnalysis.Architecture.ARMV8_M_MAINLINE), "STKOF"), "v8 stack limit");
        check(!has(FaultLogAnalysis.inspect("CFSR=0x00100000", arch), "STKOF"), "v7 reserved bit not v8 decode");
        var conflicting = FaultLogAnalysis.inspect("PC=0x1 PC=0x2\nLR=0x3", arch);
        check(!conflicting.registers().containsKey("PC") && has(conflicting, "conflicting values"), "conflicting registers excluded");
        var multiple = FaultLogAnalysis.inspect(log + log, arch);
        check(multiple.registers().isEmpty() && has(multiple, "multiple fatal"), "do not merge multiple crashes");
        var malformed = FaultLogAnalysis.inspect("PC=0x100000000 LR=1234 PC=0xGARBAGE unrelatedpc=0x1", arch);
        check(malformed.registers().isEmpty(), "invalid and over-width values excluded");
        var ansi = FaultLogAnalysis.inspect("\u001b[31mPC: 0xFFFFFFFF\u001b[0m", arch);
        check(ansi.registers().get("PC").value() == 0xFFFFFFFFL, "unsigned 32-bit and ANSI");
        check(has(FaultLogAnalysis.inspect("#CD:BEGIN#\n012345\n#CD:END#", arch), "text registers only"), "binary dump not pretended supported");
        check(FaultLogAnalysis.inspect("", arch).display().contains("No unambiguous"), "empty input");
        try { FaultLogAnalysis.inspect("x".repeat(FaultLogAnalysis.MAX_LOG_CHARS + 1), arch); throw new AssertionError("size limit"); }
        catch (IllegalArgumentException expected) {}
        try { report.registers().clear(); throw new AssertionError("immutable report"); }
        catch (UnsupportedOperationException expected) {}
        elfValidation();
        System.out.println("FaultLogAnalysisTests PASS");
    }
    private static void elfValidation() throws Exception {
        Path folder = Files.createTempDirectory("zew-fault-elf-");
        try {
            Path elf = folder.resolve("firmware.elf");
            byte[] header = new byte[52];
            Files.write(elf, header);
            rejectElf(elf, "invalid magic");
            header[0]=0x7f; header[1]='E'; header[2]='L'; header[3]='F'; header[4]=1; header[5]=1; header[18]=62;
            Files.write(elf, header);
            rejectElf(elf, "non ARM ELF");
            header[18]=40;
            Files.write(elf, header);
            var image = ArmElfSymbols.inspect(elf);
            check(image.sha256().length() == 64, "ELF SHA256");
            try {
                ArmElfSymbols.resolve(image, elf, new FaultLogAnalysis.Register("LR", 0xFFFFFFF9L, 1), () -> false);
                throw new AssertionError("EXC_RETURN rejected before executing anything");
            } catch (java.io.IOException expected) { check(expected.getMessage().contains("EXC_RETURN"), "EXC_RETURN message"); }
            Files.writeString(elf, "changed");
            try {
                ArmElfSymbols.resolve(image, elf, new FaultLogAnalysis.Register("PC", 0x1001L, 1), () -> false);
                throw new AssertionError("changed ELF rejected before executing tool");
            } catch (java.io.IOException expected) { check(expected.getMessage().contains("changed"), "ELF mutation message"); }
        } finally {
            try (var files = Files.walk(folder)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
    private static void rejectElf(Path elf, String label) throws Exception {
        try { ArmElfSymbols.inspect(elf); throw new AssertionError(label); } catch (java.io.IOException expected) {}
    }
    private static boolean has(FaultLogAnalysis.Report report, String text) {
        return report.findings().stream().anyMatch(f -> f.message().contains(text));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
