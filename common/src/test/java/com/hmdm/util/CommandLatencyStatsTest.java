package com.hmdm.util;

import com.hmdm.util.CommandLatencyStats.Sample;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CommandLatencyStatsTest {

    private static Sample done(String type, long deliveryMs, long totalMs) {
        return new Sample(type, "done", 1_000L, 1_000L + deliveryMs, 1_000L + totalMs);
    }

    private static Map<String, Object> row(List<Map<String, Object>> rows, String type) {
        for (Map<String, Object> r : rows) if (type.equals(r.get("type"))) return r;
        throw new AssertionError("no row for " + type);
    }

    @Test public void percentile_is_the_nearest_rank_so_it_is_a_latency_that_happened() {
        List<Long> ten = Arrays.asList(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertEquals(Long.valueOf(5L), CommandLatencyStats.percentile(ten, 50));
        assertEquals(Long.valueOf(9L), CommandLatencyStats.percentile(ten, 90));
        assertEquals(Long.valueOf(10L), CommandLatencyStats.percentile(ten, 100));
        assertEquals(Long.valueOf(7L), CommandLatencyStats.percentile(Collections.singletonList(7L), 90));
        assertEquals(Long.valueOf(2L), CommandLatencyStats.percentile(Arrays.asList(1L, 2L, 3L), 50));
        assertNull(CommandLatencyStats.percentile(Collections.<Long>emptyList(), 50));
        assertNull(CommandLatencyStats.percentile(null, 50));
    }

    @Test public void seconds_keep_one_decimal() {
        assertEquals(Double.valueOf(1.2), CommandLatencyStats.seconds(1_240L));
        assertEquals(Double.valueOf(0.4), CommandLatencyStats.seconds(350L));
        assertNull(CommandLatencyStats.seconds(null));
    }

    @Test public void latencies_per_type_come_from_the_commands_that_ran() {
        List<Sample> s = new ArrayList<>();
        for (int i = 1; i <= 10; i++) s.add(done("device.lock", i * 100L, i * 1_000L));
        s.add(new Sample("device.lock", "expired", 1_000L, null, 999_000L));
        s.add(new Sample("device.lock", "pending", 1_000L, null, null));

        Map<String, Object> lock = row(CommandLatencyStats.byType(s), "device.lock");

        assertEquals(12, lock.get("total"));
        assertEquals(10, lock.get("done"));
        assertEquals(83.3, lock.get("donepct"));
        assertEquals(1, lock.get("expired"));
        assertEquals(1, lock.get("open"));
        assertEquals(0.5, lock.get("deliveryp50"));
        assertEquals(0.9, lock.get("deliveryp90"));
        assertEquals(1.0, lock.get("deliverymax"));
        assertEquals(5.0, lock.get("execp50"));
        assertEquals(9.0, lock.get("execp90"));
        assertEquals(10.0, lock.get("execmax"));
    }

    @Test public void every_closing_state_is_counted_apart() {
        List<Sample> s = Arrays.asList(
                done("device.alert", 100, 200),
                new Sample("device.alert", "failed", 1L, 2L, 3L),
                new Sample("device.alert", "unsupported", 1L, 2L, 3L),
                new Sample("device.alert", "cancelled", 1L, null, 3L),
                new Sample("device.alert", "delivered", 1L, 2L, null));
        Map<String, Object> r = row(CommandLatencyStats.byType(s), "device.alert");
        assertEquals(5, r.get("total"));
        assertEquals(1, r.get("done"));
        assertEquals(2, r.get("failed"));
        assertEquals(1, r.get("cancelled"));
        assertEquals(1, r.get("open"));
        assertEquals(20.0, r.get("donepct"));
        // A failure is not a latency: only the one that ran is measured.
        assertEquals(0.2, r.get("execmax"));
    }

    @Test public void the_first_row_adds_every_type_up_and_types_follow_in_order() {
        List<Sample> s = Arrays.asList(done("device.lock", 100, 1_000), done("device.alert", 300, 3_000),
                done("device.lockscreenMessage", 200, 2_000));
        List<Map<String, Object>> rows = CommandLatencyStats.byType(s);
        assertEquals(4, rows.size());
        assertEquals(CommandLatencyStats.ALL, rows.get(0).get("type"));
        assertEquals(3, rows.get(0).get("total"));
        assertEquals(2.0, rows.get(0).get("execp50"));
        assertEquals(3.0, rows.get(0).get("execmax"));
        assertEquals("device.alert", rows.get(1).get("type"));
        assertEquals("device.lock", rows.get(2).get("type"));
    }

    @Test public void nothing_sent_means_no_rows_and_a_type_that_never_ran_has_empty_latencies() {
        assertTrue(CommandLatencyStats.byType(Collections.<Sample>emptyList()).isEmpty());
        assertTrue(CommandLatencyStats.byType(null).isEmpty());
        Map<String, Object> r = row(CommandLatencyStats.byType(
                Collections.singletonList(new Sample("device.wipe", "pending", 1L, null, null))), "device.wipe");
        assertNull(r.get("execp50"));
        assertNull(r.get("deliverymax"));
        assertEquals(0.0, r.get("donepct"));
    }

    @Test public void a_clock_that_went_backwards_does_not_produce_a_negative_latency() {
        Map<String, Object> r = row(CommandLatencyStats.byType(
                Collections.singletonList(new Sample("device.lock", "done", 5_000L, 4_000L, 3_000L))), "device.lock");
        assertEquals(1, r.get("done"));
        assertNull(r.get("execp50"));
    }
}
