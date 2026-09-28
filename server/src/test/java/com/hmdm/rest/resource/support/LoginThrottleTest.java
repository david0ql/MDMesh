package com.hmdm.rest.resource.support;

import org.junit.Assert;
import org.junit.Test;

public class LoginThrottleTest {

    @Test
    public void locksAfterFiveFailuresAndEscalates() {
        LoginThrottle t = new LoginThrottle();
        long now = 1_000_000L;
        for (int i = 0; i < 4; i++) {
            Assert.assertEquals(0, t.failed("Admin", now));
            Assert.assertFalse(t.isLocked("admin", now));
        }
        long until = t.failed("admin", now);                 // 5th failure
        Assert.assertEquals(now + LoginThrottle.BASE_LOCK_MS, until);
        Assert.assertTrue(t.isLocked("ADMIN", now + 1));      // case-insensitive
        Assert.assertFalse(t.isLocked("admin", until));
        Assert.assertEquals(until + 2 * LoginThrottle.BASE_LOCK_MS, t.failed("admin", until)); // doubles
        long far = t.failed("admin", until) ;
        Assert.assertTrue(far - until <= LoginThrottle.MAX_LOCK_MS);
    }

    @Test
    public void successClearsAndOtherAccountsAreIndependent() {
        LoginThrottle t = new LoginThrottle();
        for (int i = 0; i < 5; i++) t.failed("a", 0);
        Assert.assertTrue(t.isLocked("a", 1));
        Assert.assertFalse(t.isLocked("b", 1));
        t.succeeded("a");
        Assert.assertFalse(t.isLocked("a", 1));
    }

    @Test
    public void oldFailuresExpire() {
        LoginThrottle t = new LoginThrottle();
        for (int i = 0; i < 4; i++) t.failed("a", 0);
        Assert.assertEquals(0, t.failed("a", LoginThrottle.WINDOW_MS + 1)); // window passed: counts from 1 again
    }
}
