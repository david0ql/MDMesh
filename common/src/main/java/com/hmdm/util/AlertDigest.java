package com.hmdm.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * DallyControl — who gets which alert e-mail, and when (pure; the sending lives in the server's AlertNotifier).
 *
 * Grouping and debounce, so a noisy fleet never floods an inbox:
 * <ul>
 *   <li>each recipient has a cursor (the last alert id mailed to them) and the time of their last mail;</li>
 *   <li>a recipient gets at most one mail per interval: the first alert goes out at once, whatever arrives during
 *       the interval waits and leaves together in ONE digest when it ends;</li>
 *   <li>inside a digest, repeats of the same thing (same device, type and zone) are one line with a count.</li>
 * </ul>
 */
public final class AlertDigest {

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    public static final int MAX_RECIPIENTS = 20;

    private AlertDigest() {
    }

    /** One alert waiting to be mailed. */
    public static class Item {
        public final long id;
        /** Identity for collapsing repeats, e.g. device|type|zone. */
        public final String key;
        public final Set<String> recipients;

        public Item(long id, String key, Set<String> recipients) {
            this.id = id;
            this.key = key;
            this.recipients = recipients;
        }
    }

    public static final class RecipientState {
        public final long lastSentAt;
        public final long lastAlertId;

        public RecipientState(long lastSentAt, long lastAlertId) {
            this.lastSentAt = lastSentAt;
            this.lastAlertId = lastAlertId;
        }
    }

    public static final class Mail {
        public final String to;
        /** Oldest first. */
        public final List<Item> items;
        /** The recipient's cursor once this mail is sent. */
        public final long cursor;

        Mail(String to, List<Item> items, long cursor) {
            this.to = to;
            this.items = items;
            this.cursor = cursor;
        }
    }

    public static final class Line {
        public final Item item;
        public final int count;

        Line(Item item, int count) {
            this.item = item;
            this.count = count;
        }
    }

    /**
     * The mails to send now. A recipient without a state is skipped: the caller creates its state at the newest
     * existing alert first, so an address added today is not mailed last week's alerts.
     */
    public static List<Mail> plan(long now, long intervalMs, Map<String, RecipientState> states, List<? extends Item> alerts) {
        Map<String, List<Item>> pending = new TreeMap<>();
        for (Item a : alerts) {
            if (a.recipients == null) continue;
            for (String to : a.recipients) {
                RecipientState st = states.get(to);
                if (st == null || a.id <= st.lastAlertId) continue;
                pending.computeIfAbsent(to, k -> new ArrayList<>()).add(a);
            }
        }
        List<Mail> out = new ArrayList<>();
        for (Map.Entry<String, List<Item>> e : pending.entrySet()) {
            RecipientState st = states.get(e.getKey());
            if (now - st.lastSentAt < intervalMs) continue; // still inside the quiet interval: they wait
            List<Item> items = e.getValue();
            items.sort((a, b) -> Long.compare(a.id, b.id));
            out.add(new Mail(e.getKey(), items, items.get(items.size() - 1).id));
        }
        return out;
    }

    /** Repeats of the same key become one line (represented by the newest) with a count; at most {@code maxLines}. */
    public static List<Line> collapse(List<? extends Item> items, int maxLines) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, Item> newest = new LinkedHashMap<>();
        for (Item i : items) {
            counts.computeIfAbsent(i.key, k -> new int[1])[0]++;
            Item cur = newest.get(i.key);
            if (cur == null || i.id > cur.id) newest.put(i.key, i);
        }
        List<Line> lines = new ArrayList<>();
        for (Map.Entry<String, Item> e : newest.entrySet()) {
            if (lines.size() >= maxLines) break;
            lines.add(new Line(e.getValue(), counts.get(e.getKey())[0]));
        }
        return lines;
    }

    /** Addresses from free text (comma, semicolon, space or line separated): valid, lower-cased, unique, capped. */
    public static List<String> parseEmails(String text) {
        if (text == null) return Collections.emptyList();
        Set<String> out = new LinkedHashSet<>();
        for (String raw : text.split("[,;\\s]+")) {
            String e = raw.trim().toLowerCase(Locale.ROOT);
            if (e.length() <= 254 && EMAIL.matcher(e).matches()) out.add(e);
            if (out.size() >= MAX_RECIPIENTS) break;
        }
        return new ArrayList<>(out);
    }
}
