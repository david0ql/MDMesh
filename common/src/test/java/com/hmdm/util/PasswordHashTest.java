package com.hmdm.util;

import org.junit.Assert;
import org.junit.Test;

/** Password storage: PBKDF2 with a per-user salt, legacy SHA-1 hashes still verify (and are recognised as legacy). */
public class PasswordHashTest {

    private static final String MD5_ADMIN = "21232F297A57A5A743894A0E4A801FC3"; // MD5("admin"), as the console sends it

    @Test
    public void newHashesArePbkdf2AndSalted() {
        String a = PasswordUtil.getHashFromMd5(MD5_ADMIN);
        String b = PasswordUtil.getHashFromMd5(MD5_ADMIN);
        Assert.assertTrue(a, a.startsWith("pbkdf2$310000$"));
        Assert.assertNotEquals("same password must hash differently (per-user salt)", a, b);
        Assert.assertTrue(a.length() < 255);
        Assert.assertFalse(PasswordUtil.isLegacyHash(a));
    }

    @Test
    public void verification() {
        String h = PasswordUtil.getHashFromMd5(MD5_ADMIN);
        Assert.assertTrue(PasswordUtil.passwordMatch(MD5_ADMIN, h));
        Assert.assertTrue("md5 case does not matter", PasswordUtil.passwordMatch(MD5_ADMIN.toLowerCase(), h));
        Assert.assertFalse(PasswordUtil.passwordMatch("00000000000000000000000000000000", h));
        Assert.assertFalse(PasswordUtil.passwordMatch(MD5_ADMIN, "pbkdf2$broken"));
        Assert.assertFalse(PasswordUtil.passwordMatch(null, h));
        Assert.assertFalse(PasswordUtil.passwordMatch(MD5_ADMIN, null));
        Assert.assertFalse(PasswordUtil.passwordMatch(MD5_ADMIN, ""));
    }

    @Test
    public void legacyHashesStillVerify() {
        String legacy = CryptoUtil.getSHA1String(MD5_ADMIN + "5YdSYHyg2U");
        Assert.assertTrue(PasswordUtil.isLegacyHash(legacy));
        Assert.assertTrue(PasswordUtil.passwordMatch(MD5_ADMIN, legacy));
        Assert.assertTrue(PasswordUtil.passwordMatch(MD5_ADMIN, legacy.toUpperCase()));
        Assert.assertFalse(PasswordUtil.passwordMatch("00000000000000000000000000000000", legacy));
    }
}
