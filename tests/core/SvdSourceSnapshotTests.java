// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.SvdSourceSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class SvdSourceSnapshotTests {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static String svd(String name) {
        return "<device><name>" + name + "</name><size>32</size><peripherals>"
                + "<peripheral><name>GPIO</name><baseAddress>0x40000000</baseAddress>"
                + "<registers><register><name>DIN</name><addressOffset>0</addressOffset>"
                + "<access>read-only</access></register></registers></peripheral>"
                + "</peripherals></device>";
    }
    private static void rejects(Path path) throws Exception {
        try { SvdSourceSnapshot.read(path); }
        catch (Exception expected) { return; }
        throw new AssertionError("Invalid SVD source must fail closed: " + path);
    }
    public static void main(String[] args) throws Exception {
        Path file = Files.createTempFile("zew-svd-source-", ".svd");
        Path link = file.resolveSibling(file.getFileName() + "-link");
        try {
            byte[] original = svd("TestPart").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(file, original);
            var first = SvdSourceSnapshot.read(file);
            String expected = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(original));
            check(first.device().name().equals("TestPart")
                    && first.device().registers().size() == 1
                    && first.sha256().equals(expected),
                    "parsed register map and checksum must derive from identical bytes");
            Files.writeString(file, svd("DifferentPart"));
            var second = SvdSourceSnapshot.read(file);
            check(second.device().name().equals("DifferentPart")
                    && !second.sha256().equals(first.sha256()),
                    "replacement SVD revision must not reuse an earlier fingerprint");
            Files.write(file, new byte[0]);
            rejects(file);
            try {
                Files.createSymbolicLink(link, file);
                rejects(link);
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
                // Symlink privileges are unavailable on some Windows CI hosts.
            }
            System.out.println("PASS SvdSourceSnapshotTests");
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(file);
        }
    }
}
