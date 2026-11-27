package com.hmdm.util;

import com.hmdm.util.AlertDigest.Item;
import com.hmdm.util.AlertDigest.Mail;
import com.hmdm.util.AlertDigest.RecipientState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class AlertDigestTest {

    private static final long MIN = 60_000L;
    private static final long INTERVAL = 10 * MIN;

    private static Item item(long id, String key, String... to) {
        return new Item(id, key, new HashSet<>(Arrays.asList(to)));
    }

    private static Map<String, RecipientState> states(Object... kv) {
        Map<String, RecipientState> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 3) m.put((String) kv[i], new RecipientState((Long) kv[i + 1], (Long) kv[i + 2]));
        return m;
    }

    @Test
    public void first_alert_goes_out_at_once_grouped_per_recipient() {
        List<Item> alerts = Arrays.asList(item(1, "a", "x@e.co", "y@e.co"), item(2, "b", "x@e.co"));
        List<Mail> mails = AlertDigest.plan(100 * MIN, INTERVAL, states("x@e.co", 0L, 0L, "y@e.co", 0L, 0L), alerts);
        assertEquals(2, mails.size());
        Mail x = mails.stream().filter(m -> m.to.equals("x@e.co")).findFirst().get();
        assertEquals("one mail with both alerts", 2, x.items.size());
        assertEquals(2, x.cursor);
        Mail y = mails.stream().filter(m -> m.to.equals("y@e.co")).findFirst().get();
        assertEquals(1, y.items.size());
    }

    @Test
    public void within_the_interval_nothing_is_sent_and_alerts_wait() {
        List<Item> alerts = Arrays.asList(item(5, "a", "x@e.co"), item(6, "b", "x@e.co"));
        // last mail 3 minutes ago (cursor 4)
        assertTrue(AlertDigest.plan(100 * MIN, INTERVAL, states("x@e.co", 97 * MIN, 4L), alerts).isEmpty());
        // 10 minutes after the last one: one digest with everything that accumulated
        List<Mail> later = AlertDigest.plan(107 * MIN, INTERVAL, states("x@e.co", 97 * MIN, 4L), alerts);
        assertEquals(1, later.size());
        assertEquals(2, later.get(0).items.size());
        assertEquals(6, later.get(0).cursor);
    }

    @Test
    public void alerts_at_or_below_the_cursor_are_not_resent() {
        List<Item> alerts = Arrays.asList(item(3, "a", "x@e.co"), item(4, "b", "x@e.co"));
        assertTrue(AlertDigest.plan(100 * MIN, INTERVAL, states("x@e.co", 0L, 4L), alerts).isEmpty());
        List<Mail> mails = AlertDigest.plan(100 * MIN, INTERVAL, states("x@e.co", 0L, 3L), alerts);
        assertEquals(1, mails.get(0).items.size());
        assertEquals(4, mails.get(0).items.get(0).id);
    }

    @Test
    public void unknown_recipient_is_skipped_until_it_has_a_state() {
        // A recipient added just now has no cursor yet: the caller creates it at the current newest alert, so old
        // alerts never flood a new address.
        assertTrue(AlertDigest.plan(100 * MIN, INTERVAL, new HashMap<>(), Arrays.asList(item(1, "a", "new@e.co"))).isEmpty());
    }

    @Test
    public void repeated_alerts_collapse_into_one_line_with_a_count() {
        List<Item> items = new ArrayList<>();
        for (int i = 1; i <= 5; i++) items.add(item(i, "dev1|geofenceExit|7", "x@e.co"));
        items.add(item(6, "dev2|simChange|", "x@e.co"));
        List<AlertDigest.Line> lines = AlertDigest.collapse(items, 50);
        assertEquals(2, lines.size());
        assertEquals(5, lines.get(0).count);
        assertEquals("the newest of the repeated ones represents the line", 5, lines.get(0).item.id);
        assertEquals(1, lines.get(1).count);
    }

    @Test
    public void collapse_caps_the_number_of_lines() {
        List<Item> items = new ArrayList<>();
        for (int i = 1; i <= 80; i++) items.add(item(i, "k" + i, "x@e.co"));
        assertEquals(50, AlertDigest.collapse(items, 50).size());
    }

    @Test
    public void recipients_are_parsed_and_validated() {
        assertEquals(Arrays.asList("a@e.co", "b@e.co"), AlertDigest.parseEmails(" A@e.co; b@e.co,a@e.co\nnope, x@ "));
        assertTrue(AlertDigest.parseEmails(null).isEmpty());
    }
}
