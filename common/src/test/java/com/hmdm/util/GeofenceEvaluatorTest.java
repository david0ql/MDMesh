package com.hmdm.util;

import com.hmdm.util.GeofenceEvaluator.Outcome;
import com.hmdm.util.GeofenceEvaluator.Settings;
import com.hmdm.util.GeofenceEvaluator.State;
import com.hmdm.util.GeofenceEvaluator.Zone;
import org.junit.Test;

import static org.junit.Assert.*;

public class GeofenceEvaluatorTest {

    private static final double LAT = 4.7, LON = -74.05;
    /** Degrees of latitude per metre. */
    private static final double M = 1 / 111_195.0;
    private static final long MIN = 60_000L;

    private static Zone circle(String rule) {
        Zone z = new Zone();
        z.shape = "circle"; z.lat = LAT; z.lon = LON; z.radiusM = 200; z.rule = rule; z.alertOnReturn = true;
        return z;
    }

    private static Settings settings() {
        Settings s = new Settings();
        s.maxAccuracyM = 100; s.hysteresisM = 15; s.confirmFixes = 2;
        return s;
    }

    /** A fix [metres] north of the centre. */
    private static Outcome at(Zone z, State prev, double metres, Float acc, long ts) {
        return GeofenceEvaluator.evaluate(z, prev, LAT + metres * M, LON, acc, ts, settings());
    }

    @Test
    public void first_fix_sets_the_baseline_without_alert() {
        Outcome o = at(circle("stayInside"), null, 0, 10f, MIN);
        assertEquals("inside", o.next.state);
        assertNull(o.event);
        assertNull(o.alertType);
        Outcome out = at(circle("stayInside"), null, 500, 10f, MIN);
        assertEquals("outside", out.next.state);
        assertNull("a device already outside when the zone starts does not alert", out.alertType);
    }

    @Test
    public void exit_needs_consecutive_fixes_and_carries_the_first_fix_time() {
        Zone z = circle("stayInside");
        State s = at(z, null, 0, 10f, MIN).next;
        Outcome first = at(z, s, 400, 10f, 2 * MIN);
        assertEquals("still inside after one fix", "inside", first.next.state);
        assertNull(first.event);
        Outcome second = at(z, first.next, 450, 10f, 3 * MIN);
        assertEquals("outside", second.next.state);
        assertEquals("exit", second.event);
        assertEquals("geofenceExit", second.alertType);
        assertEquals("time of the position that left", 2 * MIN, second.eventAt);
    }

    @Test
    public void a_single_jumpy_fix_does_not_alert() {
        Zone z = circle("stayInside");
        State s = at(z, null, 0, 10f, MIN).next;
        s = at(z, s, 2000, 10f, 2 * MIN).next;       // GPS jump
        Outcome back = at(z, s, 10, 10f, 3 * MIN);   // back inside
        assertEquals("inside", back.next.state);
        assertNull(back.event);
        assertEquals(0, back.next.pendingCount);
        // a later single jump starts counting from scratch
        Outcome again = at(z, back.next, 2000, 10f, 4 * MIN);
        assertNull(again.event);
    }

    @Test
    public void inaccurate_fixes_are_ignored() {
        Zone z = circle("stayInside");
        State s = at(z, null, 0, 10f, MIN).next;
        Outcome a = at(z, s, 800, 250f, 2 * MIN);
        assertTrue(a.ignoredLowAccuracy);
        Outcome b = at(z, a.next, 800, 250f, 3 * MIN);
        assertTrue(b.ignoredLowAccuracy);
        assertEquals("inside", b.next.state);
        assertNull(b.event);
    }

    @Test
    public void the_margin_grows_with_the_reported_accuracy() {
        Zone z = circle("stayInside");
        State s = at(z, null, 0, 10f, MIN).next;
        // 240 m from the centre = 40 m beyond the edge. With ±50 m that is not conclusive (margin 15 + 50).
        s = at(z, s, 240, 50f, 2 * MIN).next;
        Outcome o = at(z, s, 240, 50f, 3 * MIN);
        assertEquals("inside", o.next.state);
        assertNull(o.event);
        // The same place with ±5 m is conclusive (margin 15 + 5).
        s = at(z, o.next, 240, 5f, 4 * MIN).next;
        o = at(z, s, 240, 5f, 5 * MIN);
        assertEquals("exit", o.event);
    }

    @Test
    public void hysteresis_band_does_not_flap() {
        Zone z = circle("stayInside");
        State s = at(z, null, 0, 5f, MIN).next;
        // Walk along the edge: all inside the band (edge ± 20 m), never an event.
        long t = 2;
        for (double m : new double[]{195, 205, 198, 210, 203, 207, 199}) {
            Outcome o = at(z, s, m, 5f, t++ * MIN);
            assertNull("no event at " + m, o.event);
            s = o.next;
        }
        assertEquals("inside", s.state);
    }

    @Test
    public void return_is_reported_once_after_an_alerted_exit() {
        Zone z = circle("stayInside");
        State s = at(z, null, 0, 10f, MIN).next;
        s = at(z, s, 400, 10f, 2 * MIN).next;
        s = at(z, s, 400, 10f, 3 * MIN).next;
        assertEquals("outside", s.state);
        s = at(z, s, 50, 10f, 4 * MIN).next;
        Outcome in = at(z, s, 40, 10f, 5 * MIN);
        assertEquals("enter", in.event);
        assertEquals("geofenceEnter", in.alertType);
        assertTrue(in.returned);
        assertEquals(4 * MIN, in.eventAt);
        // staying inside raises nothing more
        assertNull(at(z, in.next, 30, 10f, 6 * MIN).alertType);
    }

    @Test
    public void return_alert_can_be_turned_off() {
        Zone z = circle("stayInside");
        z.alertOnReturn = false;
        State s = at(z, null, 0, 10f, MIN).next;
        s = at(z, s, 400, 10f, 2 * MIN).next;
        s = at(z, s, 400, 10f, 3 * MIN).next;
        s = at(z, s, 50, 10f, 4 * MIN).next;
        Outcome in = at(z, s, 40, 10f, 5 * MIN);
        assertEquals("the event is still recorded", "enter", in.event);
        assertNull(in.alertType);
    }

    @Test
    public void keep_out_zone_alerts_on_entry() {
        Zone z = circle("keepOut");
        State s = at(z, null, 600, 10f, MIN).next;
        assertEquals("outside", s.state);
        s = at(z, s, 20, 10f, 2 * MIN).next;
        Outcome in = at(z, s, 10, 10f, 3 * MIN);
        assertEquals("enter", in.event);
        assertEquals("geofenceEnter", in.alertType);
        assertFalse(in.returned);
        s = at(z, in.next, 600, 10f, 4 * MIN).next;
        Outcome out = at(z, s, 600, 10f, 5 * MIN);
        assertEquals("geofenceExit", out.alertType);
        assertTrue(out.returned);
    }

    @Test
    public void confirm_fixes_one_alerts_immediately() {
        Zone z = circle("stayInside");
        Settings st = settings();
        st.confirmFixes = 1;
        State s = GeofenceEvaluator.evaluate(z, null, LAT, LON, 10f, MIN, st).next;
        Outcome o = GeofenceEvaluator.evaluate(z, s, LAT + 400 * M, LON, 10f, 2 * MIN, st);
        assertEquals("geofenceExit", o.alertType);
    }

    @Test
    public void small_zone_can_still_be_entered() {
        Zone z = circle("keepOut");
        z.radiusM = 30; // margin (15 + 20) would exceed the radius: it is capped at half of it
        State s = at(z, null, 300, 20f, MIN).next;
        s = at(z, s, 2, 20f, 2 * MIN).next;
        Outcome in = at(z, s, 3, 20f, 3 * MIN);
        assertEquals("geofenceEnter", in.alertType);
    }

    @Test
    public void polygon_zone() {
        Zone z = new Zone();
        z.shape = "polygon"; z.rule = "stayInside"; z.alertOnReturn = true;
        z.polygon = new double[][]{{4.6991, -74.0509}, {4.6991, -74.0491}, {4.7009, -74.0491}, {4.7009, -74.0509}};
        State s = at(z, null, 0, 10f, MIN).next;
        assertEquals("inside", s.state);
        s = at(z, s, 300, 10f, 2 * MIN).next;
        assertEquals("geofenceExit", at(z, s, 300, 10f, 3 * MIN).alertType);
    }

    @Test
    public void outside_the_schedule_the_exit_is_tracked_but_alerts_when_the_schedule_starts() {
        Zone z = circle("stayInside");
        // Mondays to Fridays 08:00–18:00 UTC.
        z.schedule = GeofenceEvaluator.Schedule.parse("{\"days\":[1,2,3,4,5],\"from\":\"08:00\",\"to\":\"18:00\",\"tz\":\"UTC\"}");
        long mon = java.time.ZonedDateTime.of(2026, 11, 23, 0, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        long h = 60 * MIN;
        State s = at(z, null, 0, 10f, mon + 5 * h).next;
        s = at(z, s, 400, 10f, mon + 6 * h).next;
        Outcome out = at(z, s, 400, 10f, mon + 6 * h + MIN);
        assertEquals("exit", out.event);
        assertNull("06:00 is outside the schedule", out.alertType);
        // Still outside at 08:30: the alert is raised then.
        Outcome late = at(z, out.next, 400, 10f, mon + 8 * h + 30 * MIN);
        assertEquals("geofenceExit", late.alertType);
        assertEquals(mon + 8 * h + 30 * MIN, late.alertAt);
        assertNull(at(z, late.next, 400, 10f, mon + 9 * h).alertType);
    }

    @Test
    public void schedule_windows() {
        GeofenceEvaluator.Schedule day = GeofenceEvaluator.Schedule.parse("{\"days\":[1],\"from\":\"08:00\",\"to\":\"18:00\",\"tz\":\"UTC\"}");
        long mon = java.time.ZonedDateTime.of(2026, 11, 23, 0, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        long h = 60 * MIN;
        assertTrue(day.applies(mon + 8 * h));
        assertFalse(day.applies(mon + 18 * h));
        assertFalse(day.applies(mon + 24 * h + 9 * h)); // Tuesday
        // Overnight window 22:00–06:00 that starts on Monday covers Tuesday 03:00.
        GeofenceEvaluator.Schedule night = GeofenceEvaluator.Schedule.parse("{\"days\":[1],\"from\":\"22:00\",\"to\":\"06:00\",\"tz\":\"UTC\"}");
        assertTrue(night.applies(mon + 23 * h));
        assertTrue(night.applies(mon + 27 * h));
        assertFalse(night.applies(mon + 3 * h));  // Monday 03:00 belongs to Sunday's window
        assertFalse(night.applies(mon + 12 * h));
        // No days = every day; malformed = no schedule
        assertTrue(GeofenceEvaluator.Schedule.parse("{\"from\":\"00:00\",\"to\":\"23:59\"}").applies(mon));
        assertNull(GeofenceEvaluator.Schedule.parse("nope"));
        assertNull(GeofenceEvaluator.Schedule.parse(null));
    }
}
