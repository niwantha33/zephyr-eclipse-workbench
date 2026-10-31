// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.VendorSvdProvisioner;

/** Deterministic HTTPS redirect and exact-chip checks; no network required. */
public final class VendorSvdProvisionerTests {
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(VendorSvdProvisioner.supports("lp_mspm0g3507/mspm0g3507"),
                "exact selected TI target eligible");
        check(!VendorSvdProvisioner.supports("lp_mspm0g3507/mspm0g3506"),
                "other target not silently mapped");
        check(VendorSvdProvisioner.isApprovedRedirectHost("www.keil.com"),
                "Keil origin allowed");
        check(VendorSvdProvisioner.isApprovedRedirectHost("keilpack.azureedge.net"),
                "observed official CDN redirect accepted");
        check(VendorSvdProvisioner.isApprovedRedirectHost("KEILPACK.AZUREEDGE.NET"),
                "DNS host matching case insensitive");
        for (String host : new String[] {"keilpack.azureedge.net.evil.test",
                "evilkeilpack.azureedge.net", "other.azureedge.net",
                "keil.com.evil.test", "", "example.com"}) {
            check(!VendorSvdProvisioner.isApprovedRedirectHost(host),
                    "unapproved redirect rejected: " + host);
        }
        check(!VendorSvdProvisioner.isApprovedRedirectHost(null),
                "null host rejected");
        System.out.println("VendorSvdProvisionerTests PASS");
    }
}
