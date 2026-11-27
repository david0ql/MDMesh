package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * DallyControl — the geofence state machine (pure: one fix in, the next state and what to report out).
 *
 * Per device and zone the server keeps a confirmed state (inside / outside) and a pending candidate. A fix only
 * changes the confirmed state when:
 * <ul>
 *   <li>its reported accuracy is not worse than {@link Settings#maxAccuracyM} (worse fixes are ignored outright);</li>
 *   <li>it is conclusive: moving INTO the violating side needs the point beyond the border by a margin of
 *       {@link Settings#hysteresisM} + the fix's accuracy (capped at half the zone's size so small zones still work);
 *       moving back only needs the point on the other side of the border. In between is a dead band where nothing
 *       changes, so a device standing on the border does not flap;</li>
 *   <li>{@link Settings#confirmFixes} consecutive fixes agree (one jumpy point never alerts). The event carries the
 *       time of the FIRST of those fixes — when the device actually crossed.</li>
 * </ul>
 * The first fix seen for a zone only sets the baseline (no alert). A violation that happens outside the zone's
 * schedule is tracked silently and alerted on the first fix inside the schedule, if it still holds.
 */
public final class GeofenceEvaluator {

    public static final String INSIDE = "inside";
    public static final String OUTSIDE = "outside";
    public static final String RULE_STAY_INSIDE = "stayInside";
    public static final String RULE_KEEP_OUT = "keepOut";
    public static final String ALERT_EXIT = "geofenceExit";
    public static final String ALERT_ENTER = "geofenceEnter";

    private GeofenceEvaluator() {
    }

    /** The geometry and rule of one zone. */
    public static final class Zone {
        /** "circle" or "polygon". */
        public String shape;
        public double lat;
        public double lon;
        public double radiusM;
        /** [lat, lon] vertices. */
        public double[][] polygon;
        /** {@link #RULE_STAY_INSIDE} (alert on exit) or {@link #RULE_KEEP_OUT} (alert on entry). */
        public String rule;
        /** Also alert when the device goes back to where it should be. */
        public boolean alertOnReturn;
        /** null = always. */
        public Schedule schedule;

        public double signedDistanceM(double pLat, double pLon) {
            return "polygon".equals(shape)
                    ? GeoMath.polygonSignedDistanceM(polygon, pLat, pLon)
                    : GeoMath.circleSignedDistanceM(lat, lon, radiusM, pLat, pLon);
        }

        double innerScaleM() {
            return "polygon".equals(shape) ? GeoMath.polygonInnerScaleM(polygon) : radiusM;
        }

        String violatingState() {
            return RULE_KEEP_OUT.equals(rule) ? INSIDE : OUTSIDE;
        }
    }

    /** What is stored per device and zone. */
    public static final class State {
        public String state;
        /** When the confirmed state began (time of the crossing fix). */
        public long since;
        public String pending;
        public int pendingCount;
        public long pendingSince;
        /** False while a violation exists that was not alerted yet (it happened outside the schedule). */
        public boolean alerted = true;
        public long lastFixAt;

        State copy() {
            State s = new State();
            s.state = state; s.since = since; s.pending = pending; s.pendingCount = pendingCount;
            s.pendingSince = pendingSince; s.alerted = alerted; s.lastFixAt = lastFixAt;
            return s;
        }
    }

    public static final class Settings {
        /** Fixes with a worse accuracy (larger ±m) are ignored. */
        public int maxAccuracyM = 100;
        /** Extra metres beyond the border, on top of the fix's accuracy, before a violation counts. */
        public int hysteresisM = 15;
        /** Consecutive conclusive fixes needed to change state. */
        public int confirmFixes = 2;
    }

    public static final class Outcome {
        public State next;
        /** "enter" / "exit" when the confirmed state changed with this fix, else null. */
        public String event;
        /** Time of the fix that crossed. */
        public long eventAt;
        /** {@link #ALERT_EXIT} / {@link #ALERT_ENTER} when an alert must be raised, else null. */
        public String alertType;
        public long alertAt;
        /** The alert/event is the device going back to where it should be. */
        public boolean returned;
        public boolean ignoredLowAccuracy;
        public double distanceM;
    }

    public static Outcome evaluate(Zone z, State prev, double lat, double lon, Float accuracy, long ts, Settings cfg) {
        Outcome o = new Outcome();
        State s = prev == null ? new State() : prev.copy();
        o.next = s;
        if (accuracy != null && accuracy > cfg.maxAccuracyM) {
            o.ignoredLowAccuracy = true;
            return o;
        }
        double d = z.signedDistanceM(lat, lon);
        o.distanceM = d;
        s.lastFixAt = ts;
        String violating = z.violatingState();

        if (s.state == null) {
            // Baseline: where the device is when the zone starts to apply. Not an event, not an alert.
            s.state = d < 0 ? INSIDE : OUTSIDE;
            s.since = ts;
            s.alerted = true;
            clearPending(s);
            return o;
        }

        double acc = accuracy == null ? 0 : Math.max(0, accuracy);
        double margin = Math.min(cfg.hysteresisM + acc, z.innerScaleM() * 0.5);
        String other = INSIDE.equals(s.state) ? OUTSIDE : INSIDE;
        boolean towardsViolation = other.equals(violating);
        // Signed "how far on the other side": positive once the point is across the border.
        double across = OUTSIDE.equals(other) ? d : -d;
        boolean candidate = towardsViolation ? across > margin : across > 0;
        boolean contrary = across <= 0;

        if (candidate) {
            if (other.equals(s.pending)) {
                s.pendingCount++;
            } else {
                s.pending = other;
                s.pendingCount = 1;
                s.pendingSince = ts;
            }
            if (s.pendingCount >= Math.max(1, cfg.confirmFixes)) {
                s.state = other;
                s.since = s.pendingSince;
                o.event = INSIDE.equals(other) ? "enter" : "exit";
                o.eventAt = s.pendingSince;
                clearPending(s);
                String type = INSIDE.equals(other) ? ALERT_ENTER : ALERT_EXIT;
                if (other.equals(violating)) {
                    if (z.schedule == null || z.schedule.applies(o.eventAt)) {
                        o.alertType = type;
                        o.alertAt = o.eventAt;
                        s.alerted = true;
                    } else {
                        s.alerted = false;
                    }
                } else {
                    o.returned = true;
                    // Only report the return of a violation that was itself reported.
                    if (z.alertOnReturn && s.alerted) {
                        o.alertType = type;
                        o.alertAt = o.eventAt;
                    }
                    s.alerted = true;
                }
                return o;
            }
        } else if (contrary) {
            clearPending(s);
        }

        // A violation that began outside the schedule and still holds now that the schedule applies.
        if (violating.equals(s.state) && !s.alerted && (z.schedule == null || z.schedule.applies(ts))) {
            o.alertType = INSIDE.equals(s.state) ? ALERT_ENTER : ALERT_EXIT;
            o.alertAt = ts;
            s.alerted = true;
        }
        return o;
    }

    private static void clearPending(State s) {
        s.pending = null;
        s.pendingCount = 0;
        s.pendingSince = 0;
    }

    /**
     * When a zone applies: days of the week (ISO, 1 = Monday … 7 = Sunday; none = every day) and a local time window.
     * A window whose end is not after its start crosses midnight and belongs to the day it starts on.
     */
    public static final class Schedule {
        private static final ObjectMapper JSON = new ObjectMapper();
        final Set<DayOfWeek> days;
        final int fromMin;
        final int toMin;
        final ZoneId zone;

        private Schedule(Set<DayOfWeek> days, int fromMin, int toMin, ZoneId zone) {
            this.days = days;
            this.fromMin = fromMin;
            this.toMin = toMin;
            this.zone = zone;
        }

        /** {@code {"days":[1,2,3],"from":"08:00","to":"18:00","tz":"America/Bogota"}}; null when absent or malformed. */
        public static Schedule parse(String json) {
            if (json == null || json.trim().isEmpty()) return null;
            try {
                JsonNode n = JSON.readTree(json);
                if (!n.isObject()) return null;
                Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
                if (n.has("days") && n.get("days").isArray()) {
                    for (JsonNode d : n.get("days")) {
                        int v = d.asInt(0);
                        if (v >= 1 && v <= 7) days.add(DayOfWeek.of(v));
                    }
                }
                int from = minutes(n.path("from").asText("00:00"));
                int to = minutes(n.path("to").asText("23:59"));
                if (from < 0 || to < 0) return null;
                ZoneId zone;
                try {
                    zone = ZoneId.of(n.path("tz").asText("America/Bogota"));
                } catch (Exception e) {
                    zone = ZoneId.of("America/Bogota");
                }
                return new Schedule(days, from, to, zone);
            } catch (Exception e) {
                return null;
            }
        }

        private static int minutes(String hhmm) {
            String[] p = hhmm == null ? new String[0] : hhmm.trim().split(":");
            if (p.length != 2) return -1;
            try {
                int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
                return h < 0 || h > 23 || m < 0 || m > 59 ? -1 : h * 60 + m;
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        public boolean applies(long ts) {
            ZonedDateTime t = Instant.ofEpochMilli(ts).atZone(zone);
            int min = t.getHour() * 60 + t.getMinute();
            DayOfWeek today = t.getDayOfWeek();
            if (toMin > fromMin) {
                return min >= fromMin && min < toMin && dayOk(today);
            }
            // Overnight: the part before midnight belongs to today, the part after to yesterday's window.
            if (min >= fromMin) return dayOk(today);
            return min < toMin && dayOk(today.minus(1));
        }

        private boolean dayOk(DayOfWeek d) {
            return days.isEmpty() || days.contains(d);
        }

        /** Canonical JSON (what is stored). */
        public String toJson() {
            StringBuilder sb = new StringBuilder("{\"days\":[");
            boolean first = true;
            for (DayOfWeek d : days) {
                if (!first) sb.append(',');
                sb.append(d.getValue());
                first = false;
            }
            return sb.append("],\"from\":\"").append(hhmm(fromMin)).append("\",\"to\":\"").append(hhmm(toMin))
                    .append("\",\"tz\":\"").append(zone.getId()).append("\"}").toString();
        }

        private static String hhmm(int m) {
            return String.format("%02d:%02d", m / 60, m % 60);
        }
    }
}
