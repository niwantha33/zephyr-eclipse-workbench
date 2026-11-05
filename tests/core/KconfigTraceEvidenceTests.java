// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.KconfigTraceEvidence;
import io.github.niwantha33.zew.core.KconfigTraceEvidence.State;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

/** Consume upstream traceconfig table syntax; never imitate Kconfig semantics. */
public final class KconfigTraceEvidenceTests {
    private static int count;
    private static void check(boolean yes, String context) {
        if (!yes) throw new AssertionError(context);
        count++;
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("kconfig trace provenance ");
        Path build = Files.createDirectories(root.resolve("build"));
        Path zephyr = Files.createDirectories(build.resolve("zephyr"));
        Path config = zephyr.resolve(".config"), trace = zephyr.resolve("kconfig-trace.md");
        Files.writeString(config, "CONFIG_GPIO=y\nCONFIG_UART=y\n"
                + "CONFIG_HIDDEN=y\n# CONFIG_NOT_SET is not set\n"
                + "CONFIG_PASSWORD=\"sensitive secret\"\n");
        check(KconfigTraceEvidence.inspect(build, "GPIO").state() == State.MISSING,
                "missing trace requires explicit user-generated upstream traceconfig");
        String markdown = """
                ## Visible symbols

                | Type   | Name          | Value      | Source   | Location                                           |
                |:-------|:--------------|:-----------|:---------|:---------------------------------------------------|
                | bool   | %1$sCONFIG_GPIO%1$s | y | assigned | [prj.conf:1](<../../prj.conf#L1>) |
                | bool   | %1$sCONFIG_UART%1$s | y | selected | %1$sA \\|\\| B%1$s |
                | string | %1$sCONFIG_PASSWORD%1$s | "sensitive secret" | default | [Kconfig:10](<../Kconfig#L10>) |

                ## Invisible symbols

                | Type | Name | Value | Source | Location |
                |:---|:---|:---|:---|:---|
                | bool | %1$sCONFIG_HIDDEN%1$s (h) | y | implied | %1$sCONFIG_GPIO%1$s |

                ## Unset symbols

                    # CONFIG_NOT_SET is not set
                """.formatted(Character.toString((char)96));
        Files.writeString(trace, markdown);
        var gpio = KconfigTraceEvidence.inspect(build, "CONFIG_GPIO");
        check(gpio.state() == State.OBSERVED && gpio.origin().equals("assigned")
                && gpio.location().contains("prj.conf:1")
                && gpio.traceSha256().matches("[0-9a-f]{64}")
                && gpio.describe().contains("pristine build"),
                "assigned origin with exact Zephyr link and honest clean-build limitation");
        var uart = KconfigTraceEvidence.inspect(build, "UART");
        check(uart.state() == State.OBSERVED && uart.origin().equals("selected")
                && uart.location().contains("||"),
                "upstream escaped pipe expressions are not split into false columns");
        var hidden = KconfigTraceEvidence.inspect(build, "HIDDEN");
        check(hidden.state() == State.OBSERVED && hidden.origin().equals("implied"),
                "hidden symbols retain their upstream implied origin");
        var secret = KconfigTraceEvidence.inspect(build, "PASSWORD");
        check(secret.state() == State.OBSERVED
                && !secret.describe().contains("sensitive secret"),
                "Kconfig string values are never copied into the response");
        check(KconfigTraceEvidence.inspect(build, "NOT_SET").state() == State.UNKNOWN,
                "unset symbols do not fabricate an origin");
        check(KconfigTraceEvidence.inspect(build, "DOES_NOT_EXIST").state() == State.UNKNOWN,
                "missing trace entry does not imply symbol unsupported");
        check(KconfigTraceEvidence.inspect(build, "BAD;rm -rf /").state() == State.UNKNOWN,
                "untrusted symbol name rejected before examining build");
        Files.writeString(config, "CONFIG_GPIO=n\nCONFIG_UART=y\nCONFIG_HIDDEN=y\n");
        check(KconfigTraceEvidence.inspect(build, "GPIO").state() == State.STALE,
                "trace disagrees with selected .config -> stale");
        Files.writeString(config, "CONFIG_GPIO=y\nCONFIG_UART=y\nCONFIG_HIDDEN=y\n");
        Files.setLastModifiedTime(trace, FileTime.from(Instant.now().minusSeconds(3600)));
        check(KconfigTraceEvidence.inspect(build, "GPIO").state() == State.STALE,
                "trace generated before current config -> stale despite equal values");
        Files.delete(trace);
        check(KconfigTraceEvidence.inspect(build, "GPIO").state() == State.MISSING,
                "trace deletion returns explicit missing state");
        System.out.println("PASS upstream Kconfig trace evidence: " + count);
    }
}
