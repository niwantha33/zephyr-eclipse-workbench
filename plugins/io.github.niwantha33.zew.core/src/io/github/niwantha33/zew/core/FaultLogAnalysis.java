// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Offline interpretation of one explicitly selected Cortex-M fatal log; never a stack unwinder. */
public final class FaultLogAnalysis {
    public static final int MAX_LOG_CHARS = 4 * 1024 * 1024;
    public enum Architecture {
        UNSPECIFIED("Unspecified / other architecture"), ARMV6_M("Cortex-M ARMv6-M (M0/M0+)"),
        ARMV7_M("Cortex-M ARMv7-M/ARMv7E-M (M3/M4/M7)"),
        ARMV8_M_MAINLINE("Cortex-M ARMv8-M Mainline (M23 excluded)");
        private final String label;
        Architecture(String label) { this.label = label; }
        public String label() { return label; }
        public boolean configurableFaults() { return this == ARMV7_M || this == ARMV8_M_MAINLINE; }
    }
    public record Register(String name, long value, int line) {
        public String hex() { return String.format("0x%08X", value); }
    }
    public record Finding(String kind, String message, String evidence, String nextAction) {}
    public record Report(Architecture architecture, Map<String, Register> registers, List<Finding> findings) {
        public Report {
            registers = Collections.unmodifiableMap(new LinkedHashMap<>(registers));
            findings = List.copyOf(findings);
        }
        public String display() {
            var out = new StringBuilder("OFFLINE ZEPHYR FATAL-LOG INVESTIGATION\nArchitecture selected by user: ")
                    .append(architecture.label()).append("\nNo target was connected or modified.\n")
                    .append("Captured register labels do not establish ELF identity, stack validity or root cause.\n");
            out.append("\nCaptured registers (line numbers refer to the imported log):\n");
            if (registers.isEmpty()) out.append("  No unambiguous supported register values found.\n");
            registers.values().forEach(r -> out.append("  ").append(r.name()).append(" = ").append(r.hex())
                    .append(" (line ").append(r.line()).append(")\n"));
            for (var f : findings) out.append("\n[").append(f.kind()).append("] ").append(f.message())
                    .append("\nEvidence: ").append(f.evidence()).append("\nNext: ").append(f.nextAction()).append('\n');
            return out.toString();
        }
    }
    // Zephyr prints r0/a1, r12/ip, r14/lr and "Faulting instruction address (r15/pc)".
    // Standalone PC/LR/SP and SCB fault-register values are accepted only with explicit hex notation.
    private static final Pattern REGISTER = Pattern.compile(
            "(?i)(?<![\\w/])(?<name>r(?:1[0-5]|[0-9])(?:/(?:a[1-4]|ip|sp|lr|pc))?|pc|lr|sp|msp|psp|xpsr|cfsr|hfsr|bfar|mmfar)\\)?\\s*[:=]\\s*(?:0x)(?<hex>[0-9a-f]{1,8})(?![0-9a-f])");
    private FaultLogAnalysis() {}

    public static Report inspect(String log, Architecture architecture) {
        if (log == null || architecture == null) throw new IllegalArgumentException("Log and architecture are required.");
        if (log.length() > MAX_LOG_CHARS) throw new IllegalArgumentException("Select a fatal-log excerpt of at most 4 MiB.");
        var registers = new LinkedHashMap<String, Register>();
        var conflicts = new java.util.HashSet<String>();
        var findings = new ArrayList<Finding>();
        String[] lines = log.split("\\R", -1);
        int fatalMarkers = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].replaceAll("\\x1B\\[[0-?]*[ -/]*[@-~]", "");
            if (line.contains("ZEPHYR FATAL ERROR")) fatalMarkers++;
            var matcher = REGISTER.matcher(line);
            while (matcher.find()) {
                String name = canonical(matcher.group("name"));
                long value = Long.parseUnsignedLong(matcher.group("hex"), 16);
                var existing = registers.get(name);
                if (existing != null && existing.value() != value) conflicts.add(name);
                else registers.putIfAbsent(name, new Register(name, value, i + 1));
            }
        }
        if (fatalMarkers > 1) {
            findings.add(new Finding("MISSING_EVIDENCE", "The file contains multiple fatal-error summaries; register values were not combined.",
                    fatalMarkers + " ZEPHYR FATAL ERROR markers", "Import an excerpt containing exactly one failure and its register dump."));
            return new Report(architecture, Map.of(), findings);
        }
        for (String name : conflicts.stream().sorted().toList()) {
            registers.remove(name);
            findings.add(new Finding("MISSING_EVIDENCE", name + " has conflicting values and was excluded.",
                    "More than one distinct hexadecimal value for " + name, "Isolate one failure/register dump before analysing it."));
        }
        if (architecture == Architecture.UNSPECIFIED) {
            findings.add(new Finding("MISSING_EVIDENCE", "Architecture-specific decoding is disabled.",
                    "No Cortex-M architecture was selected", "Select the architecture from the board/SoC documentation; do not apply Cortex-M decoding to Xtensa or RISC-V."));
        } else {
            if (!registers.containsKey("PC")) findings.add(new Finding("MISSING_EVIDENCE", "The faulting PC was not captured.",
                    "No supported PC/r15 label in the log", "Capture the complete Zephyr fatal register dump."));
            if (!registers.containsKey("SP") && !registers.containsKey("MSP") && !registers.containsKey("PSP"))
                findings.add(new Finding("MISSING_EVIDENCE", "No stack pointer was captured; stack validity cannot be assessed.",
                        "No SP/r13, MSP or PSP value", "Capture the exception context or a supported Zephyr core dump. Do not infer SP from unrelated register values."));
            if (architecture.configurableFaults()) decodeFaults(registers, architecture, findings);
            else if (registers.containsKey("CFSR") || registers.containsKey("HFSR")) findings.add(new Finding("MISSING_EVIDENCE",
                    "Configurable-fault registers are not decoded for ARMv6-M.", "Selected architecture: " + architecture.label(),
                    "Check the target architecture and register provenance; these registers are not implemented as on M3/M4/M7."));
        }
        if (log.contains("#CD:BEGIN#")) findings.add(new Finding("MISSING_EVIDENCE", "A Zephyr core-dump payload is present; this view analyses text registers only.",
                "#CD:BEGIN# marker", "Use the matching Zephyr checkout's core-dump parser/GDB server for memory, threads and backtraces; keep the matching ELF."));
        findings.add(new Finding("LIMITATION", "A fatal-log excerpt cannot reconstruct a call stack or prove the software cause.",
                "Only explicitly printed registers are interpreted", "Use a matching ELF for address lookup and a core dump or suspended target for stack investigation."));
        return new Report(architecture, registers, findings);
    }

    private static String canonical(String value) {
        String name = value.toUpperCase(Locale.ROOT).split("/")[0];
        return switch (name) { case "R13" -> "SP"; case "R14" -> "LR"; case "R15" -> "PC"; default -> name; };
    }
    private static void decodeFaults(Map<String, Register> r, Architecture architecture, List<Finding> out) {
        var cfsr = r.get("CFSR");
        if (cfsr == null) out.add(new Finding("MISSING_EVIDENCE", "CFSR was not captured; configurable-fault decoding is unavailable.",
                "No CFSR hex value", "Capture CFSR at the failure before clearing fault status."));
        else {
            int[] bits = {0, 1, 3, 4, 5, 8, 9, 10, 11, 12, 13, 16, 17, 18, 19, 24, 25};
            String[] names = {"IACCVIOL: instruction access violation", "DACCVIOL: data access violation",
                    "MUNSTKERR: MemManage unstacking error", "MSTKERR: MemManage stacking error", "MLSPERR: MemManage lazy FP-state preservation error",
                    "IBUSERR: instruction bus error", "PRECISERR: precise data bus error", "IMPRECISERR: imprecise data bus error; PC need not identify the access",
                    "UNSTKERR: BusFault unstacking error", "STKERR: BusFault stacking error", "LSPERR: BusFault lazy FP-state preservation error",
                    "UNDEFINSTR: undefined instruction", "INVSTATE: invalid execution state", "INVPC: invalid exception-return PC",
                    "NOCP: unavailable coprocessor", "UNALIGNED: unaligned access", "DIVBYZERO: division by zero"};
            for (int i = 0; i < bits.length; i++) if (set(cfsr, bits[i])) out.add(bitFinding(cfsr, names[i], bits[i]));
            if (architecture == Architecture.ARMV8_M_MAINLINE && set(cfsr, 20)) out.add(bitFinding(cfsr, "STKOF: stack limit overflow", 20));
            address(r, cfsr, "MMFAR", 7, out);
            address(r, cfsr, "BFAR", 15, out);
        }
        var hfsr = r.get("HFSR");
        if (hfsr != null) {
            if (set(hfsr, 1)) out.add(bitFinding(hfsr, "VECTTBL: bus fault while reading the exception vector table", 1));
            if (set(hfsr, 30)) out.add(bitFinding(hfsr, "FORCED: a configurable fault escalated to HardFault", 30));
            if (set(hfsr, 31)) out.add(bitFinding(hfsr, "DEBUGEVT: debug event escalated to HardFault", 31));
        }
    }
    private static boolean set(Register r, int bit) { return (r.value() & (1L << bit)) != 0; }
    private static Finding bitFinding(Register register, String description, int bit) {
        return new Finding("CAPTURED_STATUS", description, register.name() + "=" + register.hex() + ", bit " + bit + ", log line " + register.line(),
                "Check the matching architecture reference and captured context. Status bits may be sticky; this does not establish a unique root cause.");
    }
    private static void address(Map<String, Register> registers, Register cfsr, String name, int validBit, List<Finding> out) {
        var address = registers.get(name);
        if (address != null) out.add(new Finding(set(cfsr, validBit) ? "CAPTURED_STATUS" : "MISSING_EVIDENCE",
                set(cfsr, validBit) ? name + " is marked valid: " + address.hex() : name + " is not marked valid; its value must not be treated as the fault address.",
                "CFSR=" + cfsr.hex() + ", validity bit " + validBit + "; " + name + " at log line " + address.line(),
                "Correlate with captured instructions and memory; an address alone does not prove why the access occurred."));
        else if (set(cfsr, validBit)) out.add(new Finding("MISSING_EVIDENCE", name + " is marked valid but its address was not captured.",
                "CFSR=" + cfsr.hex() + ", bit " + validBit, "Capture " + name + " alongside CFSR at the failure."));
    }
}
