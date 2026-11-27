package com.hmdm.util;

import com.hmdm.persistence.domain.AgentCommand;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <p>How long a queued agent command waits for its device, and whether it is handed out again when the device
 * received it but never answered. One table, by command type, so a lock or a wipe sent to a phone without signal
 * is still there when the phone comes back, while a remote session or a "open this screen now" order is not.</p>
 *
 * <p>Pure: no I/O. The queue sweep ({@code AgentCommandDAO.sweep}) asks {@link #decide} for every open command.</p>
 */
public final class CommandPolicy {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    /** A pending command with this time-to-live never expires: it waits until it runs or an administrator cancels it. */
    public static final long NEVER = 0L;
    /** A delivered command of this rule is not handed out again. */
    public static final long NO_REDELIVERY = 0L;
    /** No cap on how many times a command is handed out (the pending time-to-live still bounds it). */
    public static final int UNLIMITED = 0;

    /** Wait of a command type nobody listed: what the queue did for every type before this table. */
    public static final long DEFAULT_PENDING_TTL_MS = HOUR;
    /** A command the device holds and that is not (or no longer) handed out again ends as expired after this. */
    public static final long DELIVERED_TTL_MS = 6 * HOUR;
    /** A delivered command without an answer goes back to the queue after this (the answer or the delivery was lost). */
    public static final long REDELIVER_AFTER_MS = 2 * MINUTE;

    /** Capability token of an agent that remembers, across restarts, which commands it already ran. */
    public static final String LEDGER_CAPABILITY = "device.commandLedger";

    /** The rule of one command type. */
    public static final class Rule {
        /** Milliseconds an undelivered command waits, counted from its creation; {@link #NEVER} = no limit. */
        public final long pendingTtlMs;
        /** Milliseconds after delivery without an answer before it is handed out again; {@link #NO_REDELIVERY} = never. */
        public final long redeliverAfterMs;
        /** How many times at most it is handed out; {@link #UNLIMITED} = no cap. */
        public final int maxDeliveries;
        /** Handed out again only to an agent that advertises {@link #LEDGER_CAPABILITY} (running it twice would hurt). */
        public final boolean needsLedger;
        /**
         * Commands that set the same piece of state share a family: a lost one is not handed out again once a newer
         * one of its family reached the device (it would undo it). Null = each command stands alone (a message, a lock).
         */
        public final String family;

        Rule(long pendingTtlMs, long redeliverAfterMs, int maxDeliveries, boolean needsLedger, String family) {
            this.pendingTtlMs = pendingTtlMs;
            this.redeliverAfterMs = redeliverAfterMs;
            this.maxDeliveries = maxDeliveries;
            this.needsLedger = needsLedger;
            this.family = family;
        }

        public boolean redelivers() {
            return redeliverAfterMs > 0;
        }
    }

    private static final Rule DEFAULT = new Rule(DEFAULT_PENDING_TTL_MS, NO_REDELIVERY, 1, false, null);

    /** Keyed by the lower-cased type. */
    private static final Map<String, Rule> RULES = new HashMap<>();

    private static void rule(String type, long ttl, long redeliver, int max, boolean ledger, String family) {
        RULES.put(type.toLowerCase(Locale.ROOT), new Rule(ttl, redeliver, max, ledger, family));
    }

    static {
        // Anti-theft: they wait for the phone as long as it takes.
        rule("device.wipe", NEVER, REDELIVER_AFTER_MS, 10, false, null);
        rule("device.lock", NEVER, REDELIVER_AFTER_MS, 10, false, null);
        // Security actions that still make sense weeks later.
        rule("device.passcodeReset", 30 * DAY, REDELIVER_AFTER_MS, 5, false, "passcode");
        rule("device.lockscreenMessage", 30 * DAY, REDELIVER_AFTER_MS, 5, false, "lockscreenMessage");
        rule("device.alert", 30 * DAY, REDELIVER_AFTER_MS, 5, false, null);
        rule("kiosk.enter", 30 * DAY, REDELIVER_AFTER_MS, 5, false, "kiosk");
        rule("kiosk.exit", 30 * DAY, REDELIVER_AFTER_MS, 5, false, "kiosk");
        // Settings of the device.
        rule("device.powerMode", 30 * DAY, REDELIVER_AFTER_MS, 5, false, "powerMode");
        rule("device.locationMode", 30 * DAY, REDELIVER_AFTER_MS, 5, false, "locationMode");
        rule("device.systemUpdate", 30 * DAY, REDELIVER_AFTER_MS, 5, false, "systemUpdate");
        rule("device.appEnable", 30 * DAY, REDELIVER_AFTER_MS, 5, false, null);
        // Apps: an install waits for a phone that is off (it skips an app already at that version).
        rule("app.install", 30 * DAY, 10 * MINUTE, UNLIMITED, false, null);
        rule("device.appInstallForce", 30 * DAY, 10 * MINUTE, 5, false, null);
        rule("app.uninstall", 30 * DAY, REDELIVER_AFTER_MS, 5, false, null);
        // Useful for a day, a surprise after that.
        rule("device.ring", DAY, REDELIVER_AFTER_MS, 3, false, "ring");
        rule("device.ringStop", DAY, REDELIVER_AFTER_MS, 3, false, "ring");
        rule("device.silence", DAY, REDELIVER_AFTER_MS, 3, false, "ring");
        // A reboot kills the agent before it answers: run again only by an agent that remembers it already did.
        rule("device.reboot", DAY, REDELIVER_AFTER_MS, 2, true, null);
        // The policy: the reconciler queues a new one on every check-in of a drifted device, so the old wait stays.
        rule("config.apply", DEFAULT_PENDING_TTL_MS, 3 * MINUTE, UNLIMITED, false, null);
        rule("config.sync", DEFAULT_PENDING_TTL_MS, REDELIVER_AFTER_MS, 3, false, null);
        rule("policy.apply", DEFAULT_PENDING_TTL_MS, REDELIVER_AFTER_MS, 3, false, "policy");
        rule("apps.scan", DEFAULT_PENDING_TTL_MS, REDELIVER_AFTER_MS, 3, false, null);
        rule("apps.icons", DEFAULT_PENDING_TTL_MS, REDELIVER_AFTER_MS, 3, false, null);
        // Remote sessions: somebody is waiting in front of the console right now.
        rule("remote.vnc.start", 5 * MINUTE, NO_REDELIVERY, 1, false, null);
        rule("remote.vnc.stop", 5 * MINUTE, NO_REDELIVERY, 1, false, null);
        rule("remote.inputSetup", 5 * MINUTE, NO_REDELIVERY, 1, false, null);
    }

    private CommandPolicy() {
    }

    /** The rule of a command type (never null; an unlisted type waits an hour and is never handed out twice). */
    public static Rule of(String type) {
        if (type == null) return DEFAULT;
        Rule r = RULES.get(type.trim().toLowerCase(Locale.ROOT));
        return r == null ? DEFAULT : r;
    }

    /** The lower-cased types of {@code type}'s family (itself included), or an empty set when it has none. */
    public static Set<String> familyTypes(String type) {
        String family = of(type).family;
        if (family == null) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, Rule> e : RULES.entrySet()) {
            if (family.equals(e.getValue().family)) out.add(e.getKey());
        }
        return out;
    }

    /** What the sweep does with an open command. */
    public enum Action {
        /** Leave it as it is. */
        KEEP,
        /** Its wait is over: it ends as expired. */
        EXPIRE,
        /** Delivered but never answered: back to the queue, the device gets it again. */
        REQUEUE,
        /** Delivered, never answered, and a newer command of its family reached the device: it ends, never re-run. */
        SUPERSEDE
    }

    /**
     * @param c              an open command ({@code pending} or {@code delivered})
     * @param ledger         the device's agent advertises {@link #LEDGER_CAPABILITY}
     * @param newerDelivered a newer command of the same family already reached the device (only asked for a
     *                       command whose rule has a family)
     */
    public static Action decide(AgentCommand c, long now, boolean ledger, boolean newerDelivered) {
        if (c == null || c.getStatus() == null) return Action.KEEP;
        Rule r = of(c.getType());
        boolean tooOld = r.pendingTtlMs != NEVER && c.getCreatedAt() != null && now - c.getCreatedAt() > r.pendingTtlMs;
        if ("pending".equals(c.getStatus())) {
            return tooOld ? Action.EXPIRE : Action.KEEP;
        }
        if (!"delivered".equals(c.getStatus()) || c.getDeliveredAt() == null) return Action.KEEP;
        long held = now - c.getDeliveredAt();
        int deliveries = c.getDeliveries() == null ? 0 : c.getDeliveries();
        boolean mayRedeliver = r.redelivers() && (!r.needsLedger || ledger)
                && (r.maxDeliveries == UNLIMITED || deliveries < r.maxDeliveries) && !tooOld;
        if (mayRedeliver) {
            if (held < r.redeliverAfterMs) return Action.KEEP;
            return r.family != null && newerDelivered ? Action.SUPERSEDE : Action.REQUEUE;
        }
        return held > DELIVERED_TTL_MS ? Action.EXPIRE : Action.KEEP;
    }
}
