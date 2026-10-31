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
        check(SvdAutoDiscovery.partFromBoard("qemu_x86/atom").equals("atom"), "CPU is not a device");
        Path root = Files.createTempDirectory("zew-exact-svd-");
        try {
            Path vendor = Files.createDirectories(root.resolve("modules/hal/vendor"));
            Files.writeString(vendor.resolve("MSPM0G350X.svd"), svd("MSPM0G350X"));
            var missing = SvdAutoDiscovery.resolve("lp_mspm0g3507/mspm0g3507", List.of(root), root.resolve("cache"));
            check(missing.state() == SvdAutoDiscovery.State.MISSING && missing.path() == null,
                    "family-level model must not silently be assigned to an exact SoC");
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
            Path other = Files.createDirectories(root.resolve("competing"));
            Files.writeString(other.resolve("MSPM0G3507.svd"), svd("MSPM0G3507")
                    .replace("0x400A0000", "0x400B0000"));
            var ambiguous = SvdAutoDiscovery.resolve("lp_mspm0g3507/mspm0g3507",
                    List.of(packRoot, other), root.resolve("cache"));
            check(ambiguous.state() == SvdAutoDiscovery.State.AMBIGUOUS && ambiguous.path() == null,
                    "divergent vendor packs require explicit selection, no silent revision selection");
            System.out.println("SvdAutoDiscoveryTests PASS");
        } finally {
            try (var walk = Files.walk(root)) {
                for (var file : walk.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(file);
            }
        }
    }
}
