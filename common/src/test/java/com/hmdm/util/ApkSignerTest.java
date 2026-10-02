package com.hmdm.util;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

public class ApkSignerTest {
    @Test
    public void known_publishers_are_named_and_unknown_ones_are_not() {
        assertEquals("Google LLC", ApkSigner.publisher("F0FD6C5B410F25CB25C3B53346C8972FAE30F8EE7411DF910480AD6B2D60DB83"));
        assertNull(ApkSigner.publisher("00"));
        assertNull(ApkSigner.publisher(null));
    }

    @Test
    public void a_file_that_is_not_an_apk_has_no_signer() throws Exception {
        File f = File.createTempFile("notapk", ".apk");
        try {
            java.nio.file.Files.write(f.toPath(), new byte[2048]);
            assertNull(ApkSigner.of(f));
        } finally {
            f.delete();
        }
    }

    /** The agent this project ships is v2/v3-signed with the release key: the parser must find that certificate. */
    @Test
    public void reads_the_v2_certificate_of_a_real_apk_when_one_is_at_hand() {
        File apk = new File("../agent-android/app/build/outputs/apk/release/app-release.apk");
        org.junit.Assume.assumeTrue(apk.isFile());
        ApkSigner.Signer s = ApkSigner.of(apk);
        assertNotNull(s);
        assertEquals("6f23256db8a494b07b9399902cd3c1722d000bf908dd82c14b92e4c3d708ce5a", s.sha256);
    }

    @Test
    public void apk_fetcher_only_follows_its_own_hosts_and_valid_packages() {
        assertTrue(ApkFetcher.allowedHost("d.apkpure.net"));
        assertTrue(ApkFetcher.allowedHost("data.winudf.com"));
        assertFalse(ApkFetcher.allowedHost("evil-apkpure.net"));
        assertFalse(ApkFetcher.allowedHost("169.254.169.254"));
        assertFalse(ApkFetcher.allowedHost(null));
        assertTrue(ApkFetcher.PACKAGE.matcher("com.whatsapp").matches());
        assertFalse(ApkFetcher.PACKAGE.matcher("../etc/passwd").matches());
        assertFalse(ApkFetcher.PACKAGE.matcher("com.x?y=1").matches());
    }
}
