// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.SvdAutoDiscovery;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Exact-part and package provenance; no target access or manufactured GPIO data. */
public final class SvdAutoDiscoveryTests {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static String svd(String device) {
        return "<device><name>" + device + "</name><size>32</size><peripherals><peripheral>"
                + "<name>GPIOA</name><baseAddress>0x400A0000</baseAddress><registers>"
                + "<register><name>DOUT</name><addressOffset>0x4</addressOffset><fields>"
                + "<field><name>OUT0</name><bitOffset>0</bitOffset><bitWidth>1</bitWidth></field>"
                + "</fields></register></registers></peripheral></peripherals></device>";
    }
    public static void main(String[] ignored) throws Exception {
        check(SvdAutoDiscovery.partFromBoard("lp_mspm0g3507/mspm0g3507").equals("mspm0g3507"),
                "parse selected variant, not board name");
        check(SvdAutoDiscovery.partFromBoard("esp32s3_devkitc/esp32s3/appcpu").equals("esp32s3"),
                "architecture variant not confused with core");
        check(SvdAutoDiscovery.partFromBoard("qemu_x86/atom").isEmpty(),
                "short virtual CPU qualifier must not be treated as exact device");
        Path root = Files.createTempDirectory("zew-exact-svd-");
        try {
            Path vendor = Files.createDirectories(root.resolve("modules/hal/vendor"));
            Files.writeString(vendor.resolve("MSPM0G350X.svd"), svd("MSPM0G350X"));
            var missing = SvdAutoDiscovery.resolve("lp_mspm0g3507/mspm0g3507", List.of(root), root.resolve("cache"));
            check(missing.state() == SvdAutoDiscovery.State.MATCHED && missing.registers() == 1,
                    "explicit TI MSPM0G350X mapping must load for the G3507 SoC");
            check(SvdAutoDiscovery.resolve("lp_mspm0g3507/mspm0g3506",
                    List.of(root), root.resolve("other-cache")).state() == SvdAutoDiscovery.State.MISSING,
                    "TI family SVD must not automatically map to other part numbers");
            Files.delete(vendor.resolve("MSPM0G350X.svd"));
            Path candidate = vendor.resolve("MSPM0G3507.svd");
            Files.writeString(candidate, svd("MSPM0G3507"));
            var matched = SvdAutoDiscovery.resolve("lp_mspm0g3507/mspm0g3507",
                    List.of(root, root), root.resolve("cache"));
            check(matched.state() == SvdAutoDiscovery.State.MATCHED
                    && matched.path().equals(candidate.toRealPath())
                    && matched.registers() == 1, "same exact SoC loads");
            check(SvdAutoDiscovery.resolve("qemu_x86/atom", List.of(root), root.resolve("cache"))
                    .state() == SvdAutoDiscovery.State.INVALID_TARGET, "never claim SVD for virtual CPU");
            Files.writeString(vendor.resolve("OTHER.svd"), svd("MSPM0G3507"));
            check(SvdAutoDiscovery.resolve("lp_mspm0g3507/mspm0g3507", List.of(root),
                    root.resolve("cache")).state() == SvdAutoDiscovery.State.MATCHED,
                    "filename filter avoids unrelated model");
            // CMSIS packs may contain SVDs instead of an extracted .svd file.
            Files.delete(candidate);
            Path packRoot = Files.createDirectories(root.resolve("packs"));
            Path pack = packRoot.resolve("Vendor.DevicePack.pack");
            try (var output = new ZipOutputStream(Files.newOutputStream(pack))) {
                output.putNextEntry(new ZipEntry("SVD/MSPM0G3507.svd"));
                output.write(svd("MSPM0G3507").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
            var fromPack = SvdAutoDiscovery.resolve("lp_mspm0g3507/mspm0g3507",
                    List.of(packRoot), root.resolve("cache"));
            check(fromPack.state() == SvdAutoDiscovery.State.MATCHED
                    && Files.isRegularFile(fromPack.path()) && fromPack.registers() == 1,
                    "exact vendor CMSIS SVD inside installed pack extracted to a safe cache");
            // Real vendor DFPs commonly declare a part-specific Dname whose
            // register description is a shared, family-named SVD file.
            Path familyRoot = Files.createDirectories(root.resolve("family-pack"));
            Path familyPack = familyRoot.resolve("TI.MSPM0_DFP.pack");
            try (var output = new ZipOutputStream(Files.newOutputStream(familyPack))) {
                output.putNextEntry(new ZipEntry("TI.MSPM0_DFP.pdsc"));
                output.write(("<package><devices><family Dfamily='MSPM0'>"
                        + "<device Dname='MSPM0G3507'><debug svd='SVD/MSPM0G350X.svd'/>"
                        + "</device></family></devices></package>")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
                output.putNextEntry(new ZipEntry("SVD/MSPM0G350X.svd"));
                output.write(svd("MSPM0G350X").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
            var mapped = SvdAutoDiscovery.resolve("lp_mspm0g3507/mspm0g3507",
                    List.of(familyRoot), root.resolve("mapped-cache"));
            check(mapped.state() == SvdAutoDiscovery.State.MATCHED
                    && mapped.mappedByPack() && mapped.registers() == 1,
                    "only explicit vendor PDSC exact Dname authorizes a shared family SVD");
            Path unpacked = Files.createDirectories(root.resolve("unpacked/SVD"));
            Files.writeString(unpacked.getParent().resolve("Vendor.DFP.pdsc"),
                    "<package><devices><family Dfamily='MSPM0'>"
                    + "<device Dname='MSPM0G3507'><debug svd='SVD/MSPM0G350X.svd'/>"
                    + "</device></family></devices></package>");
            Files.writeString(unpacked.resolve("MSPM0G350X.svd"), svd("MSPM0G350X"));
            var expanded = SvdAutoDiscovery.resolve("lp_mspm0g3507/mspm0g3507",
                    List.of(unpacked.getParent()), root.resolve("unpacked-cache"));
            check(expanded.state() == SvdAutoDiscovery.State.MATCHED
                    && expanded.mappedByPack(), "unpacked CMSIS .pdsc maps exact SoC safely");
            // A cached pack entry must be repaired by its content hash, never trusted by filename.
            Files.writeString(mapped.path(), svd("OTHERCHIP"));
            var recached = SvdAutoDiscovery.resolve("lp_mspm0g3507/mspm0g3507",
                    List.of(familyRoot), root.resolve("mapped-cache"));
            check(recached.state() == SvdAutoDiscovery.State.MATCHED
                    && Files.readString(recached.path()).contains("MSPM0G350X"),
                    "tampered cached family SVD replaced only from verified pack bytes");
            Path wrongRoot = Files.createDirectories(root.resolve("wrong-pack"));
            Path wrongPack = wrongRoot.resolve("Other.DFP.pack");
            try (var output = new ZipOutputStream(Files.newOutputStream(wrongPack))) {
                output.putNextEntry(new ZipEntry("Other.DFP.pdsc"));
                output.write(("<package><devices><family Dfamily='MSPM0'>"
                        + "<device Dname='MSPM0G3506'><debug svd='SVD/MSPM0G350X.svd'/>"
                        + "</device></family></devices></package>")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
                output.putNextEntry(new ZipEntry("SVD/MSPM0G350X.svd"));
                output.write(svd("MSPM0G350X").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
            check(SvdAutoDiscovery.resolve("lp_mspm0g3507/mspm0g3507",
                    List.of(wrongRoot), root.resolve("wrong-cache")).state()
                    == SvdAutoDiscovery.State.MISSING, "another part's family map is never auto-used");
            Path other = Files.createDirectories(root.resolve("competing"));
            Files.writeString(other.resolve("MSPM0G3507.svd"), svd("MSPM0G3507")
                    .replace("0x400A0000", "0x400B0000"));
            var ambiguous = SvdAutoDiscovery.resolve("lp_mspm0g3507/mspm0g3507",
                    List.of(packRoot, other), root.resolve("cache"));
            check(ambiguous.state() == SvdAutoDiscovery.State.AMBIGUOUS && ambiguous.path() == null,
                    "divergent vendor packs require explicit selection, no silent revision selection");
            // Core-specific application/network SVDs must not be interchangeable,
            // regardless of filename traversal order or a matching family prefix.
            Path multicore = Files.createDirectories(root.resolve("dual-core"));
            Files.writeString(multicore.resolve("nrf5340_application.svd"), svd("nrf5340_application"));
            Files.writeString(multicore.resolve("nrf5340_network.svd"), svd("nrf5340_network"));
            check(SvdAutoDiscovery.resolve("nrf5340dk/nrf5340", List.of(multicore), null)
                    .state() == SvdAutoDiscovery.State.AMBIGUOUS,
                    "unqualified dual-core SVD discovery never guesses a target");
            var appCore = SvdAutoDiscovery.resolve("nrf5340dk/nrf5340/cpuapp",
                    List.of(multicore), null);
            check(appCore.state() == SvdAutoDiscovery.State.MATCHED
                    && appCore.path().getFileName().toString().equals("nrf5340_application.svd"),
                    "application qualifier accepts only application content");
            var networkCore = SvdAutoDiscovery.resolve("nrf5340dk/nrf5340/cpunet",
                    List.of(multicore), null);
            check(networkCore.state() == SvdAutoDiscovery.State.MATCHED
                    && networkCore.path().getFileName().toString().equals("nrf5340_network.svd"),
                    "network qualifier accepts only network content");
            check(SvdAutoDiscovery.resolve("nrf5340dk/nrf5340/cpu0", List.of(multicore), null)
                    .state() == SvdAutoDiscovery.State.AMBIGUOUS,
                    "unsupported core does not inherit a generic nRF SVD");
            // A huge unrelated first HAL truncates *that root*, but the next
            // exact vendor root must still be visited; incomplete scans remain
            // untrusted regardless of matching candidates.
            Path huge = Files.createDirectories(root.resolve("oversized-hal"));
            for (int i = 0; i < 12; i++)
                Files.writeString(huge.resolve("unrelated-" + i + ".txt"), "skip");
            Path nextVendor = Files.createDirectories(root.resolve("next-hal"));
            Files.writeString(nextVendor.resolve("MSPM0G3507.svd"), svd("MSPM0G3507"));
            var partial = SvdAutoDiscovery.resolveWithBudget("board/mspm0g3507",
                    List.of(huge, nextVendor), null, 5, 100);
            check(partial.state() == SvdAutoDiscovery.State.INCOMPLETE_SCAN
                    && partial.inspected().stream().anyMatch(path -> path.contains("Truncated root:"))
                    && partial.inspected().stream().anyMatch(path -> path.contains("next-hal")),
                    "per-root scan budget does not starve subsequent HALs but fails closed");
            var complete = SvdAutoDiscovery.resolveWithBudget("board/mspm0g3507",
                    List.of(nextVendor), null, 5, 100);
            check(complete.state() == SvdAutoDiscovery.State.MATCHED,
                    "a complete exact vendor root can still succeed with tiny budgets");
            System.out.println("SvdAutoDiscoveryTests PASS");
        } finally {
            try (var walk = Files.walk(root)) {
                for (var file : walk.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(file);
            }
        }
    }
}
