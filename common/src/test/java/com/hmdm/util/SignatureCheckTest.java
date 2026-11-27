package com.hmdm.util;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class SignatureCheckTest {
    private static final String A = "aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11";
    private static final String B = "bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22";

    @Test
    public void same_certificate_is_fine_whatever_the_case() {
        SignatureCheck.Result r = SignatureCheck.compare(true, A, "1.0", A.toUpperCase());
        assertEquals(SignatureCheck.Verdict.SAME, r.verdict);
        assertFalse(r.changed());
        assertEquals(Boolean.FALSE, r.toMap().get("changed"));
    }

    @Test
    public void another_certificate_is_flagged_with_a_message_that_names_both() {
        SignatureCheck.Result r = SignatureCheck.compare(true, A, "1.0", B);
        assertEquals(SignatureCheck.Verdict.CHANGED, r.verdict);
        assertTrue(r.changed());
        String msg = (String) r.toMap().get("message");
        assertTrue(msg, msg.contains("1.0") && msg.contains("aa11aa11aa11") && msg.contains("bb22bb22bb22")
                && msg.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE"));
        assertEquals("changed", r.toMap().get("status"));
    }

    @Test
    public void the_first_version_of_a_package_has_nothing_to_compare_with() {
        assertEquals(SignatureCheck.Verdict.FIRST, SignatureCheck.compare(false, null, null, B).verdict);
    }

    @Test
    public void an_unreadable_signature_is_unknown_not_changed() {
        assertEquals(SignatureCheck.Verdict.UNKNOWN, SignatureCheck.compare(true, null, "1.0", B).verdict);
        assertEquals(SignatureCheck.Verdict.UNKNOWN, SignatureCheck.compare(true, A, "1.0", " ").verdict);
        assertFalse(SignatureCheck.compare(true, null, "1.0", B).changed());
    }

    /** Two real APKs of the same package signed with different keys (built by scripts/build-testapp.sh), when at hand. */
    @Test
    public void real_apks_signed_with_different_keys_are_told_apart() {
        File v1 = new File("../scripts/testapp/testapp-v1.apk");
        File other = new File("../scripts/testapp/testapp-v3-otherkey.apk");
        org.junit.Assume.assumeTrue(v1.isFile() && other.isFile());
        ApkSigner.Signer a = ApkSigner.of(v1);
        ApkSigner.Signer b = ApkSigner.of(other);
        assertNotNull(a);
        assertNotNull(b);
        assertTrue(SignatureCheck.compare(true, a.sha256, "1.0.1", b.sha256).changed());
        assertFalse(SignatureCheck.compare(true, a.sha256, "1.0.1", ApkSigner.of(v1).sha256).changed());
    }

    // --- .aab ---------------------------------------------------------------------------------------------------

    private static File zip(String... entries) throws Exception {
        File f = File.createTempFile("bundle", ".zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            for (String e : entries) {
                z.putNextEntry(new ZipEntry(e));
                z.write(new byte[]{1, 2, 3});
                z.closeEntry();
            }
        }
        return f;
    }

    @Test
    public void an_aab_is_recognised_by_name_and_by_content() throws Exception {
        assertTrue(AppBundles.isAabName("App-Release.AAB"));
        assertFalse(AppBundles.isAabName("app.apks"));
        assertFalse(AppBundles.isAabName(null));
        File aab = zip("BundleConfig.pb", "base/manifest/AndroidManifest.xml", "base/dex/classes.dex");
        File apks = zip("toc.pb", "universal.apk");
        File notZip = File.createTempFile("notzip", ".bin");
        try {
            assertTrue(AppBundles.isAab(aab));
            assertFalse(AppBundles.isAab(apks));
            assertFalse(AppBundles.isAab(notZip));
        } finally {
            aab.delete();
            apks.delete();
            notZip.delete();
        }
    }

    @Test
    public void the_aab_message_says_how_to_convert_it() {
        assertTrue(AppBundles.AAB_REJECTED.contains("bundletool build-apks"));
        assertTrue(AppBundles.AAB_REJECTED.contains(".apks"));
    }
}
