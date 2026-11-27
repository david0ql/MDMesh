package com.hmdm.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <p>The "command latency" report: for each command type, how many were sent, how many ran, and how long they took
 * from the moment an administrator queued them (p50, p90, maximum). Pure, so the figures the report shows as
 * evidence are pinned by tests.</p>
 *
 * <p>Two spans per command, both counted from its creation: <b>delivery</b> (until the device picked it up) and
 * <b>execution</b> (until the device reported it done). Only commands that ran count toward the latencies; one
 * closed by the server because the wiped phone went silent counts as run but adds no execution time of its own
 * beyond its delivery.</p>
 */
public final class CommandLatencyStats {

    private CommandLatencyStats() {
    }

    /** One command, as the report needs it. */
    public static final class Sample {
        public final String type;
        public final String status;
        public final Long createdAt;
        public final Long deliveredAt;
        public final Long completedAt;

        public Sample(String type, String status, Long createdAt, Long deliveredAt, Long completedAt) {
            this.type = type;
            this.status = status;
            this.createdAt = createdAt;
            this.deliveredAt = deliveredAt;
            this.completedAt = completedAt;
        }
    }

    /**
     * The value below which {@code p} percent of the samples fall (nearest rank: the smallest sample with at least
     * {@code p}% of the samples at or below it), so the result is always a latency that really happened.
     *
     * @return null when there are no samples
     */
    public static Long percentile(List<Long> sorted, double p) {
        if (sorted == null || sorted.isEmpty()) return null;
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(1, Math.min(sorted.size(), rank)) - 1);
    }

    /** Milliseconds as seconds with one decimal; null stays null (shown as an empty cell). */
    public static Double seconds(Long millis) {
        return millis == null ? null : Math.round(millis / 100.0) / 10.0;
    }

    private static final class Bucket {
        int total, done, failed, expired, cancelled, open;
        final List<Long> delivery = new ArrayList<>();
        final List<Long> execution = new ArrayList<>();

        void add(Sample s) {
            total++;
            String st = s.status == null ? "" : s.status;
            switch (st) {
                case "done": done++; break;
                case "failed":
                case "unsupported": failed++; break;
                case "expired": expired++; break;
                case "cancelled": cancelled++; break;
                default: open++;
            }
            if (!"done".equals(st) || s.createdAt == null) return;
            if (s.deliveredAt != null && s.deliveredAt >= s.createdAt) delivery.add(s.deliveredAt - s.createdAt);
            if (s.completedAt != null && s.completedAt >= s.createdAt) execution.add(s.completedAt - s.createdAt);
        }

        Map<String, Object> row(String type) {
            Collections.sort(delivery);
            Collections.sort(execution);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("type", type);
            r.put("total", total);
            r.put("done", done);
            r.put("donepct", total == 0 ? null : Math.round(done * 1000.0 / total) / 10.0);
            r.put("failed", failed);
            r.put("expired", expired);
            r.put("cancelled", cancelled);
            r.put("open", open);
            r.put("deliveryp50", seconds(percentile(delivery, 50)));
            r.put("deliveryp90", seconds(percentile(delivery, 90)));
            r.put("deliverymax", seconds(delivery.isEmpty() ? null : delivery.get(delivery.size() - 1)));
            r.put("execp50", seconds(percentile(execution, 50)));
            r.put("execp90", seconds(percentile(execution, 90)));
            r.put("execmax", seconds(execution.isEmpty() ? null : execution.get(execution.size() - 1)));
            return r;
        }
    }

    /** Key of the row that adds every type up. */
    public static final String ALL = "*";

    /**
     * One row per command type (alphabetical), preceded by a row for all of them together ({@link #ALL}) when there
     * is anything to report. Row keys: type, total, done, donepct, failed, expired, cancelled, open,
     * deliveryp50/p90/max and execp50/p90/max (seconds).
     */
    public static List<Map<String, Object>> byType(List<Sample> samples) {
        Map<String, Bucket> buckets = new TreeMap<>();
        Bucket all = new Bucket();
        if (samples != null) {
            for (Sample s : samples) {
                if (s == null || s.type == null) continue;
                Bucket b = buckets.get(s.type);
                if (b == null) {
                    b = new Bucket();
                    buckets.put(s.type, b);
                }
                b.add(s);
                all.add(s);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        if (all.total == 0) return rows;
        rows.add(all.row(ALL));
        for (Map.Entry<String, Bucket> e : buckets.entrySet()) rows.add(e.getValue().row(e.getKey()));
        return rows;
    }
}
