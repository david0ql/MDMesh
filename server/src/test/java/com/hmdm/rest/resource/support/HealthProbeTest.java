package com.hmdm.rest.resource.support;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class HealthProbeTest {

    @Test
    public void readyWhenEveryCheckPasses() {
        HealthProbe probe = new HealthProbe(() -> false, 1000, 0, () -> 0L)
                .add("db", () -> true)
                .add("files", () -> true);
        HealthProbe.Result r = probe.check();
        Assert.assertTrue(r.isReady());
        Assert.assertEquals("{\"status\":\"ok\",\"checks\":{\"db\":\"ok\",\"files\":\"ok\"}}", r.toJson());
    }

    @Test
    public void downWhenACheckFailsOrThrowsAndNothingLeaks() {
        HealthProbe probe = new HealthProbe(() -> false, 1000, 0, () -> 0L)
                .add("db", () -> {
                    throw new IllegalStateException("FATAL: password authentication failed for user \"secret-user\"");
                })
                .add("files", () -> true);
        HealthProbe.Result r = probe.check();
        Assert.assertFalse(r.isReady());
        Assert.assertEquals("{\"status\":\"down\",\"checks\":{\"db\":\"fail\",\"files\":\"ok\"}}", r.toJson());
        Assert.assertFalse(r.toJson().contains("secret-user"));
    }

    @Test
    public void aCheckThatHangsFailsWithinTheTimeLimit() {
        CountDownLatch release = new CountDownLatch(1);
        HealthProbe probe = new HealthProbe(() -> false, 100, 0, System::currentTimeMillis)
                .add("db", () -> {
                    release.await();
                    return true;
                });
        long start = System.currentTimeMillis();
        HealthProbe.Result r = probe.check();
        long took = System.currentTimeMillis() - start;
        release.countDown();
        Assert.assertEquals(HealthProbe.STATUS_DOWN, r.getStatus());
        Assert.assertTrue("took " + took + " ms", took < 2000);
    }

    @Test
    public void outcomeIsCachedSoCallersCannotHammerTheDatabase() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong now = new AtomicLong(10_000);
        HealthProbe probe = new HealthProbe(() -> false, 1000, 2000, now::get)
                .add("db", () -> {
                    calls.incrementAndGet();
                    return true;
                });
        for (int i = 0; i < 50; i++) {
            probe.check();
        }
        Assert.assertEquals(1, calls.get());
        now.addAndGet(2000);
        probe.check();
        Assert.assertEquals(2, calls.get());
    }

    @Test
    public void drainingIsReportedAtOnceEvenWithACachedOk() {
        AtomicBoolean draining = new AtomicBoolean(false);
        HealthProbe probe = new HealthProbe(draining::get, 1000, 60_000, () -> 0L).add("db", () -> true);
        Assert.assertTrue(probe.check().isReady());
        draining.set(true);
        HealthProbe.Result r = probe.check();
        Assert.assertFalse(r.isReady());
        Assert.assertEquals(HealthProbe.STATUS_DRAINING, r.getStatus());
        draining.set(false);
        Assert.assertTrue(probe.check().isReady());
    }

    @Test
    public void monitorsSeeADrainingInstanceAsHealthyButNeverADownOne() {
        AtomicBoolean dbUp = new AtomicBoolean(true);
        HealthProbe probe = new HealthProbe(() -> true, 1000, 0, () -> 0L).add("db", dbUp::get);
        Assert.assertEquals(HealthProbe.STATUS_DRAINING, probe.check(true).getStatus());
        Assert.assertTrue(probe.check(false).isReady());
        dbUp.set(false);
        Assert.assertEquals(HealthProbe.STATUS_DOWN, probe.check(false).getStatus());
    }

    @Test
    public void aDownInstanceReportsDownNotDraining() {
        HealthProbe probe = new HealthProbe(() -> true, 1000, 0, () -> 0L).add("db", () -> false);
        Assert.assertEquals(HealthProbe.STATUS_DOWN, probe.check().getStatus());
    }
}
