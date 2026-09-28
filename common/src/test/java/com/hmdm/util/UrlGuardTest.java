package com.hmdm.util;

import org.junit.Assert;
import org.junit.Test;

/** SSRF guard: only http(s) to public addresses (literal IPs here, so no DNS is needed). */
public class UrlGuardTest {

    @Test
    public void internalTargetsAreRefused() {
        for (String u : new String[] {
                "http://127.0.0.1/", "http://localhost:5432/", "http://10.0.0.5/x", "http://172.16.1.1/", "http://192.168.1.1/",
                "http://169.254.169.254/latest/meta-data/", "http://100.64.0.1/", "http://0.0.0.0/", "http://[::1]/",
                "http://[fd00::1]/", "http://[fe80::1]/", "http://[::ffff:127.0.0.1]/", "http://[::127.0.0.1]/", "http://[::10.0.0.1]/", "http://224.0.0.1/",
                "file:///etc/passwd", "ftp://8.8.8.8/", "gopher://8.8.8.8/", "http://user:pw@8.8.8.8/", "not a url", ""}) {
            Assert.assertFalse(u, UrlGuard.isPublicHttpUrl(u));
        }
    }

    @Test
    public void publicTargetsAreAllowed() {
        Assert.assertTrue(UrlGuard.isPublicHttpUrl("https://8.8.8.8/file.json"));
        Assert.assertTrue(UrlGuard.isPublicHttpUrl("http://1.1.1.1:8080/a?b=c"));
        Assert.assertTrue(UrlGuard.isPublicHttpUrl("https://[2606:4700:4700::1111]/"));
    }
}
