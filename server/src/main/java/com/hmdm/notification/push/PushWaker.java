package com.hmdm.notification.push;

import com.hmdm.persistence.DevicePushDAO;
import com.hmdm.persistence.domain.DevicePush;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * <p>The second way to wake a phone: a push message telling its agent to check in now. Used when a command is
 * queued for a device whose live channel is closed, as an immediate reinforcement of urgent commands (a lock, an
 * alarm, a message, a wipe, a remote session) and when a wake sent over the live channel got no answer.</p>
 *
 * <p>Without a push project configured every method is a no-op and the server behaves as before: the command waits
 * for the phone's next check-in.</p>
 */
@Singleton
public class PushWaker {

    /** What {@link #wake} decided. */
    public enum Outcome { QUEUED, NOT_CONFIGURED, NO_TOKEN, DEBOUNCED }

    /** Where tokens live and where results are noted. */
    public interface Store {
        /** The device's registration token, or null. */
        String token(String deviceNumber);

        void recordSend(String deviceNumber, long at, boolean ok, String error);

        void dropToken(String deviceNumber, String token, String error);

        /** @return true when this check-in answered a push. */
        boolean recordAnswer(String deviceNumber, long at);
    }

    /** Runs work now (off the caller's thread) or after a delay. */
    public interface Scheduler {
        void run(Runnable task);

        void later(long delayMs, Runnable task);
    }

    /** Command types that cannot wait for the next periodic check-in: they are pushed even when the channel looks open. */
    private static final java.util.Set<String> URGENT = new java.util.HashSet<>(java.util.Arrays.asList(
            "device.lock", "device.ring", "device.ringstop", "device.alert", "device.lockscreenmessage",
            "device.announce", "device.wipe", "device.passcodereset", "device.reboot", "remote.vnc.start"));

    /** While a push is unanswered, further non-urgent ones within this time are folded into it (a burst = one wake). */
    static final long DEBOUNCE_MS = 30_000L;
    /** Urgent commands are pushed each time, but not more often than this. */
    static final long URGENT_DEBOUNCE_MS = 3_000L;
    /** A wake sent over the live channel without a check-in after this long is repeated as a push. */
    static final long SILENT_AFTER_MS = 12_000L;
    /** An urgent push without a check-in is repeated after each of these waits. */
    static final long[] FOLLOW_UP_MS = {30_000L, 90_000L};
    static final int TTL_SEC = 4 * 60 * 60;
    static final int URGENT_TTL_SEC = 24 * 60 * 60;
    private static final int MAX_ERRORS = 10;

    private static final Logger log = LoggerFactory.getLogger(PushWaker.class);

    private final FcmSettings settings;
    private final FcmClient client;
    private final Store store;
    private final Scheduler scheduler;
    private final LongSupplier clock;
    private final SmsWakeProvider sms;

    private final Map<String, Long> lastQueuedAt = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCheckInAt = new ConcurrentHashMap<>();
    private final java.util.Set<String> trailing = ConcurrentHashMap.newKeySet();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong answered = new AtomicLong();
    private volatile long lastOkAt;
    private final Deque<Map<String, Object>> errors = new ArrayDeque<>();

    @Inject
    public PushWaker(DevicePushDAO dao) {
        this(FcmSettings.fromEnvironment(), dao);
    }

    private PushWaker(FcmSettings settings, DevicePushDAO dao) {
        this(settings, settings.canSend() ? new FcmClient(settings) : null, daoStore(dao), threads(),
                System::currentTimeMillis, SmsWakeProvider.fromEnvironment());
        if (settings.isConfigured()) {
            log.info("Push wake channel ready (Firebase project {})", settings.projectId);
        } else if (settings.problem != null) {
            log.warn("Push wake channel off: {}", settings.problem);
        }
    }

    public PushWaker(FcmSettings settings, FcmClient client, Store store, Scheduler scheduler, LongSupplier clock,
                     SmsWakeProvider sms) {
        this.settings = settings;
        this.client = client;
        this.store = store;
        this.scheduler = scheduler;
        this.clock = clock;
        this.sms = sms;
    }

    public static boolean isUrgent(String commandType) {
        return commandType != null && URGENT.contains(commandType.trim().toLowerCase(Locale.ROOT));
    }

    public FcmSettings settings() {
        return settings;
    }

    public boolean isConfigured() {
        return client != null && settings.canSend();
    }

    /** Push a wake to the device. Never blocks and never throws: the queue and the periodic check-in stay the floor. */
    public Outcome wake(String deviceNumber, boolean urgent) {
        return wake(deviceNumber, urgent, 0);
    }

    private Outcome wake(String deviceNumber, boolean urgent, int followUp) {
        if (!isConfigured() || deviceNumber == null) return Outcome.NOT_CONFIGURED;
        try {
            String token = store.token(deviceNumber);
            if (token == null || token.isEmpty()) return Outcome.NO_TOKEN;
            long now = clock.getAsLong();
            Long last = lastQueuedAt.get(deviceNumber);
            long window = urgent ? URGENT_DEBOUNCE_MS : DEBOUNCE_MS;
            // A push that the phone already answered is spent: whatever is queued now needs its own. Only a push
            // still on its way absorbs the next ones — and then one more goes out when the window closes, unless
            // the phone reported meanwhile (that report carries everything queued until then).
            if (last != null && now - last < window && !checkedInSince(deviceNumber, last)) {
                if (trailing.add(deviceNumber)) {
                    scheduler.later(window - (now - last), () -> {
                        trailing.remove(deviceNumber);
                        if (!checkedInSince(deviceNumber, last)) wake(deviceNumber, urgent, followUp);
                    });
                }
                return Outcome.DEBOUNCED;
            }
            lastQueuedAt.put(deviceNumber, now);
            scheduler.run(() -> deliver(deviceNumber, token, urgent, followUp));
            return Outcome.QUEUED;
        } catch (Exception e) {
            log.warn("Push wake of {} could not be queued: {}", deviceNumber, e.getMessage());
            return Outcome.NOT_CONFIGURED;
        }
    }

    /**
     * A wake just went out over the live channel. A socket can look open for a while after the phone lost its
     * network or went into deep sleep, so when no check-in follows, the wake is repeated as a push.
     */
    public void escalateIfSilent(String deviceNumber) {
        if (!isConfigured() || deviceNumber == null) return;
        long asked = clock.getAsLong();
        scheduler.later(SILENT_AFTER_MS, () -> {
            if (!checkedInSince(deviceNumber, asked)) wake(deviceNumber, false);
        });
    }

    /** Every check-in passes here: it is what answers a push (and cancels the follow-ups). */
    public void onCheckIn(String deviceNumber) {
        if (deviceNumber == null) return;
        long now = clock.getAsLong();
        lastCheckInAt.put(deviceNumber, now);
        if (!isConfigured() || !lastQueuedAt.containsKey(deviceNumber)) return;
        try {
            if (store.recordAnswer(deviceNumber, now)) answered.incrementAndGet();
        } catch (Exception e) {
            log.debug("Could not note the push answer of {}: {}", deviceNumber, e.getMessage());
        }
    }

    private boolean checkedInSince(String deviceNumber, long since) {
        Long at = lastCheckInAt.get(deviceNumber);
        return at != null && at >= since;
    }

    private void deliver(String deviceNumber, String token, boolean urgent, int followUp) {
        long at = clock.getAsLong();
        FcmClient.Result result;
        try {
            result = client.send(token, "commands", urgent ? URGENT_TTL_SEC : TTL_SEC);
        } catch (Exception e) {
            result = null;
            noteError(deviceNumber, e.getMessage() == null ? e.toString() : e.getMessage());
        }
        if (result == null) {
            failed.incrementAndGet();
            safeRecord(deviceNumber, at, false, "error interno");
            return;
        }
        if (result.ok) {
            sent.incrementAndGet();
            lastOkAt = clock.getAsLong();
            safeRecord(deviceNumber, at, true, null);
            if (urgent && followUp < FOLLOW_UP_MS.length) {
                scheduler.later(FOLLOW_UP_MS[followUp], () -> {
                    if (!checkedInSince(deviceNumber, at)) {
                        lastQueuedAt.remove(deviceNumber);
                        wake(deviceNumber, true, followUp + 1);
                    }
                });
            }
            return;
        }
        failed.incrementAndGet();
        noteError(deviceNumber, result.error);
        safeRecord(deviceNumber, at, false, result.error);
        if (result.unregistered) {
            try {
                store.dropToken(deviceNumber, token, "El servicio de mensajes ya no conoce este equipo (token caducado).");
            } catch (Exception e) {
                log.debug("Could not drop the push token of {}: {}", deviceNumber, e.getMessage());
            }
        }
        log.warn("Push wake of {} failed after {} attempt(s): {}", deviceNumber, result.attempts, result.error);
    }

    private void safeRecord(String deviceNumber, long at, boolean ok, String error) {
        try {
            store.recordSend(deviceNumber, at, ok, error == null || error.length() <= 380 ? error : error.substring(0, 380));
        } catch (Exception e) {
            log.debug("Could not note the push to {}: {}", deviceNumber, e.getMessage());
        }
    }

    private void noteError(String deviceNumber, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("at", clock.getAsLong());
        e.put("device", deviceNumber);
        e.put("message", message);
        synchronized (errors) {
            errors.addFirst(e);
            while (errors.size() > MAX_ERRORS) errors.removeLast();
        }
    }

    /** For the console's server settings: is push on, and how has it been going since this server started. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", isConfigured() && settings.canRegister());
        out.put("canSend", isConfigured());
        out.put("canRegister", settings.canRegister());
        out.put("projectId", settings.projectId);
        out.put("problem", settings.problem);
        out.put("sent", sent.get());
        out.put("failed", failed.get());
        out.put("answered", answered.get());
        out.put("lastOkAt", lastOkAt == 0 ? null : lastOkAt);
        List<Map<String, Object>> recent;
        synchronized (errors) {
            recent = new ArrayList<>(errors);
        }
        out.put("recentErrors", recent);
        out.put("sms", sms == null ? null : sms.name());
        return out;
    }

    public SmsWakeProvider sms() {
        return sms;
    }

    private static Store daoStore(DevicePushDAO dao) {
        return new Store() {
            @Override
            public String token(String deviceNumber) {
                DevicePush row = dao.find(deviceNumber);
                return row == null || !"fcm".equals(row.getProvider()) ? null : row.getToken();
            }

            @Override
            public void recordSend(String deviceNumber, long at, boolean ok, String error) {
                dao.recordSend(deviceNumber, at, ok, error);
            }

            @Override
            public void dropToken(String deviceNumber, String token, String error) {
                dao.dropToken(deviceNumber, token, error);
            }

            @Override
            public boolean recordAnswer(String deviceNumber, long at) {
                return dao.recordAnswer(deviceNumber, at);
            }
        };
    }

    private static Scheduler threads() {
        ScheduledExecutorService pool = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "push-wake");
            t.setDaemon(true);
            return t;
        });
        return new Scheduler() {
            @Override
            public void run(Runnable task) {
                pool.execute(guard(task));
            }

            @Override
            public void later(long delayMs, Runnable task) {
                pool.schedule(guard(task), delayMs, TimeUnit.MILLISECONDS);
            }
        };
    }

    private static Runnable guard(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                log.warn("Push wake task failed: {}", t.toString());
            }
        };
    }
}
