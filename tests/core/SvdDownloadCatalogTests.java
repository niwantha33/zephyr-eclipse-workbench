// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.SvdDownloadCatalog;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;

public final class SvdDownloadCatalogTests {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void rejects(String json) throws Exception {
        try {
            SvdDownloadCatalog.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
            throw new AssertionError("Unsafe catalog JSON accepted");
        } catch (IllegalArgumentException expected) { /* fail closed */ }
    }
    public static void main(String[] args) throws Exception {
        check(SvdDownloadCatalog.entries().size() == 9, "All nine existing sources migrated");
        var esp = SvdDownloadCatalog.pinned("esp32s3_devkitc/esp32s3/appcpu", "svd");
        check(esp != null && esp.sha256().equals(
                "d7629249eba2ad660a960530d124966e9274c8228341380dd4102f251e879e17"),
                "Existing Espressif digest preserved unchanged");
        check(SvdDownloadCatalog.pinned("lp_mspm0g3507/mspm0g3507", "pack") == null,
                "TI URL without a separately verified digest cannot authorize network access");
        rejects("[{\"part\":\"esp32s3\",\"type\":\"svd\",\"sha256\":\"1234\","
                + "\"url\":\"https://evil.invalid/esp32s3.svd\"}]");
        rejects("[{\"part\":\"esp32s3\",\"type\":\"svd\",\"sha256\":\"\","
                + "\"url\":\"http://raw.githubusercontent.com/test.svd\"}]");
        check(SvdDownloadCatalog.pinned("unknown_board/other", "svd") == null,
                "Unknown part never provisions");
        System.out.println("SvdDownloadCatalogTests PASS");
    }
}
