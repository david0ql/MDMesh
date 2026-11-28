package com.hmdm.notification;

import com.hmdm.util.SupportSessions;
import org.junit.Assert;
import org.junit.Test;

/** When a remote-support session ends by itself. */
public class SupportHubTest {

    private static final long T0 = 1_000_000_000L;

    @Test
    public void aSessionWithBothSidesConnectedLives() {
        Assert.assertNull(SupportHub.expiry(true, T0, 0, 0, T0 + 60_000, T0 + 61_000));
    }

    @Test
    public void aDeviceThatNeverJoinsTimesOut() {
        long justBefore = T0 + SupportSessions.PENDING_MILLIS - 1;
        Assert.assertNull(SupportHub.expiry(false, T0, 0, 0, justBefore, justBefore));
        Assert.assertEquals("timeout", SupportHub.expiry(false, T0, 0, 0, T0, T0 + SupportSessions.PENDING_MILLIS + 1));
    }

    @Test
    public void aDeviceThatDropsMayComeBackForAWhile() {
        long left = T0 + 10_000;
        Assert.assertNull(SupportHub.expiry(true, T0, left, 0, left, left + SupportSessions.REJOIN_MILLIS - 1));
        Assert.assertEquals("device", SupportHub.expiry(true, T0, left, 0, left, left + SupportSessions.REJOIN_MILLIS + 1));
    }

    @Test
    public void anOperatorWhoLeavesEndsTheSession() {
        long left = T0 + 10_000;
        Assert.assertNull(SupportHub.expiry(true, T0, 0, left, left, left + 1_000));
        Assert.assertEquals("console", SupportHub.expiry(true, T0, 0, left, left, left + SupportSessions.REJOIN_MILLIS + 1));
        // The operator whose socket never opened is the same case: nobody is watching.
        Assert.assertEquals("console", SupportHub.expiry(false, T0, 0, T0, T0, T0 + SupportSessions.REJOIN_MILLIS + 1));
    }

    @Test
    public void anIdleSessionIsClosed() {
        Assert.assertEquals("idle", SupportHub.expiry(true, T0, 0, 0, T0, T0 + SupportSessions.IDLE_MILLIS + 1));
    }

}
