/*
 * DallyControl remote-support hub (ADR 0012): pairs the console's socket with the device's socket of one support
 * session and relays between them. Screen frames, audio and file chunks pass through undecoded.
 */
package com.hmdm.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdm.persistence.SupportSessionDAO;
import com.hmdm.persistence.domain.SupportSession;
import com.hmdm.util.CryptoUtil;
import com.hmdm.util.SupportSessions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.websocket.CloseReason;
import javax.websocket.Session;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Live remote-support sessions, one per device at most. A session is created by the console's start call
 * ({@link #create}); the device joins with its own secret (checked by the endpoint) and the session key, the console
 * with the one-time token the start call returned. Everything either side sends is passed to the other, except:
 * input from a view-only operator is dropped, and a console never gets to send screen frames.
 *
 * <p>In memory on this server, like {@link AgentWakeHub}: both sockets of a session end on the same node.</p>
 */
@Singleton
public class SupportHub {

    private static final Logger log = LoggerFactory.getLogger(SupportHub.class);

    /** Static bridge for the container-created WebSocket endpoints. Set in the constructor. */
    public static volatile SupportHub INSTANCE;

    /** One live session. Fields are guarded by the instance's monitor unless noted. */
    public static final class Live {
        final String key;
        final String tokenHash;
        final long dbId;
        final int customerId;
        final String deviceNumber;
        final boolean viewOnly;
        final String operator;
        final long createdAt;
        volatile Session device;
        volatile Session console;
        volatile long deviceLeftAt;
        volatile long consoleLeftAt;
        volatile long lastActivity;
        volatile boolean deviceEverJoined;
        volatile boolean consoleEverJoined;
        volatile boolean ended;
        int chat;
        int files;
        int input;
        long voiceMillis;
        long callSince;

        Live(String key, String tokenHash, long dbId, int customerId, String deviceNumber, boolean viewOnly,
             String operator, long now) {
            this.key = key;
            this.tokenHash = tokenHash;
            this.dbId = dbId;
            this.customerId = customerId;
            this.deviceNumber = deviceNumber;
            this.viewOnly = viewOnly;
            this.operator = operator;
            this.createdAt = now;
            this.lastActivity = now;
            this.consoleLeftAt = now; // the console has REJOIN_MILLIS to open its socket
        }

        public String getKey() { return key; }
        public long getDbId() { return dbId; }
        public String getDeviceNumber() { return deviceNumber; }
        public boolean isViewOnly() { return viewOnly; }
        public boolean isDeviceConnected() { Session s = device; return s != null && s.isOpen(); }
    }

    private final SupportSessionDAO dao;
    private final AgentWakeHub wakeHub;
    private final Map<String, Live> byKey = new ConcurrentHashMap<>();
    private final Map<String, Live> byDevice = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper;

    @Inject
    public SupportHub(SupportSessionDAO dao, AgentWakeHub wakeHub) {
        this.dao = dao;
        this.wakeHub = wakeHub;
        INSTANCE = this;
        try {
            int n = dao.closeOrphans(System.currentTimeMillis());
            if (n > 0) log.info("Remote support: {} session(s) left open by the previous run were closed", n);
        } catch (Exception e) {
            log.warn("Remote support: could not close orphan sessions: {}", e.getMessage());
        }
        reaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "support-hub-reaper");
            t.setDaemon(true);
            return t;
        });
        reaper.scheduleWithFixedDelay(this::reap, 10, 10, TimeUnit.SECONDS);
    }

    /** What the start call hands back to the operator. */
    public static final class Created {
        public final Live live;
        public final String token;

        Created(Live live, String token) {
            this.live = live;
            this.token = token;
        }
    }

    /** Open a session on a device; one already open on it is ended (reason "replaced"). */
    public Created create(int customerId, String deviceNumber, Integer userId, String userLogin, boolean viewOnly, String engine) {
        Live previous = byDevice.get(deviceNumber);
        if (previous != null) end(previous, "replaced");
        long now = System.currentTimeMillis();
        String key = SupportSessions.newKey();
        String token = SupportSessions.newToken();
        SupportSession row = new SupportSession();
        row.setSessionKey(key);
        row.setCustomerId(customerId);
        row.setDeviceNumber(deviceNumber);
        row.setUserId(userId);
        row.setUserLogin(userLogin);
        row.setViewOnly(viewOnly);
        row.setEngine(engine);
        row.setStartedAt(now);
        dao.insert(row);
        Live live = new Live(key, CryptoUtil.getSHA256String(token), row.getId(), customerId, deviceNumber, viewOnly, userLogin, now);
        byKey.put(key, live);
        byDevice.put(deviceNumber, live);
        wakeHub.send(deviceNumber, SupportSessions.wakePayload(key));
        log.info("Remote support session {} opened on {} by {} (viewOnly={})", row.getId(), deviceNumber, userLogin, viewOnly);
        return new Created(live, token);
    }

    public Live get(String key) {
        return key == null ? null : byKey.get(key);
    }

    public Live forDevice(String deviceNumber) {
        return deviceNumber == null ? null : byDevice.get(deviceNumber);
    }

    // --- sockets ---------------------------------------------------------------------------------------------------

    /** The device's socket (its secret was checked by the endpoint). False = no such session for that device. */
    public boolean deviceOpen(String deviceNumber, String key, Session ws) {
        Live live = byKey.get(key);
        if (live == null || live.ended || !live.deviceNumber.equals(deviceNumber)) return false;
        Session old = live.device;
        live.device = ws;
        live.deviceLeftAt = 0;
        live.lastActivity = System.currentTimeMillis();
        if (old != null && old != ws) closeQuietly(old, "replaced");
        if (!live.deviceEverJoined) {
            live.deviceEverJoined = true;
            try { dao.markConnected(live.dbId, live.lastActivity); } catch (Exception e) { log.warn("support: {}", e.getMessage()); }
        }
        send(ws, SupportSessions.sessionMessage(live.viewOnly, live.operator));
        Session console = live.console;
        if (console != null && console.isOpen()) {
            send(ws, SupportSessions.peerMessage(SupportSessions.ROLE_CONSOLE, true));
            send(console, SupportSessions.peerMessage(SupportSessions.ROLE_DEVICE, true));
        }
        return true;
    }

    /** The console's first message carries the token. False = wrong session or token. */
    public boolean consoleAuth(String key, String token, Session ws) {
        Live live = byKey.get(key);
        if (live == null || live.ended || token == null
                || !CryptoUtil.constantTimeEquals(CryptoUtil.getSHA256String(token), live.tokenHash)) {
            return false;
        }
        Session old = live.console;
        live.console = ws;
        live.consoleLeftAt = 0;
        live.consoleEverJoined = true;
        live.lastActivity = System.currentTimeMillis();
        if (old != null && old != ws) closeQuietly(old, "replaced");
        send(ws, "{\"t\":\"auth.ok\"}");
        Session device = live.device;
        if (device != null && device.isOpen()) {
            send(ws, SupportSessions.peerMessage(SupportSessions.ROLE_DEVICE, true));
            send(device, SupportSessions.peerMessage(SupportSessions.ROLE_CONSOLE, true));
        }
        return true;
    }

    public void onText(String role, String key, Session from, String text) {
        Live live = byKey.get(key);
        if (live == null || live.ended) return;
        boolean console = SupportSessions.ROLE_CONSOLE.equals(role);
        if (from != (console ? live.console : live.device)) return; // a socket that was replaced
        // Frame acknowledgements and keep-alives are the bulk of the traffic: no parsing, no audit.
        if (!text.startsWith("{\"t\":\"ack\"") && !text.startsWith("{\"t\":\"ping\"")) {
            JsonNode m = SupportSessions.parse(text);
            String type = SupportSessions.type(m);
            if (type == null) return;
            if (console && !SupportSessions.consoleMayRelay(type, live.viewOnly)) return;
            live.lastActivity = System.currentTimeMillis();
            account(live, role, type, m);
            if ("bye".equals(type)) {
                end(live, role);
                return;
            }
        }
        Session to = console ? live.device : live.console;
        if (to != null && to.isOpen()) send(to, text);
    }

    public void onBinary(String role, String key, Session from, ByteBuffer data) {
        Live live = byKey.get(key);
        if (live == null || live.ended || !data.hasRemaining()) return;
        boolean console = SupportSessions.ROLE_CONSOLE.equals(role);
        if (from != (console ? live.console : live.device)) return;
        if (console && !SupportSessions.consoleMayRelayBinary(data.get(data.position()))) return;
        Session to = console ? live.device : live.console;
        if (to != null && to.isOpen()) send(to, data);
    }

    public void onClose(String role, String key, Session ws) {
        Live live = byKey.get(key);
        if (live == null || live.ended) return;
        long now = System.currentTimeMillis();
        if (SupportSessions.ROLE_CONSOLE.equals(role)) {
            if (live.console != ws) return;
            live.console = null;
            live.consoleLeftAt = now;
            Session device = live.device;
            if (device != null && device.isOpen()) send(device, SupportSessions.peerMessage(SupportSessions.ROLE_CONSOLE, false));
        } else {
            if (live.device != ws) return;
            live.device = null;
            live.deviceLeftAt = now;
            Session console = live.console;
            if (console != null && console.isOpen()) send(console, SupportSessions.peerMessage(SupportSessions.ROLE_DEVICE, false));
        }
    }

    // --- lifecycle -------------------------------------------------------------------------------------------------

    /** End by key (the console's stop call). */
    public boolean end(String key, String reason) {
        Live live = byKey.get(key);
        if (live == null) return false;
        end(live, reason);
        return true;
    }

    public void end(Live live, String reason) {
        synchronized (live) {
            if (live.ended) return;
            live.ended = true;
            if (live.callSince > 0) {
                live.voiceMillis += System.currentTimeMillis() - live.callSince;
                live.callSince = 0;
            }
        }
        byKey.remove(live.key, live);
        byDevice.remove(live.deviceNumber, live);
        String end = SupportSessions.endMessage(reason);
        for (Session s : new Session[] {live.device, live.console}) {
            if (s != null && s.isOpen()) {
                send(s, end);
                closeQuietly(s, reason);
            }
        }
        try {
            dao.end(live.dbId, System.currentTimeMillis(), reason, live.chat, live.files,
                    (int) (live.voiceMillis / 1000L), live.input);
        } catch (Exception e) {
            log.warn("Remote support: could not close session {}: {}", live.dbId, e.getMessage());
        }
        try {
            dao.expireStart(live.deviceNumber, live.key, System.currentTimeMillis());
        } catch (Exception e) {
            log.debug("Remote support: could not expire the start command: {}", e.getMessage());
        }
        log.info("Remote support session {} on {} ended ({})", live.dbId, live.deviceNumber, reason);
    }

    void reap() {
        try {
            long now = System.currentTimeMillis();
            List<Live> all = new ArrayList<>(byKey.values());
            for (Live l : all) {
                String why = expiry(l.deviceEverJoined, l.createdAt, l.deviceLeftAt, l.consoleLeftAt, l.lastActivity, now);
                if (why != null) end(l, why);
            }
        } catch (Exception e) {
            log.warn("Remote support reaper: {}", e.getMessage());
        }
    }

    /** Why a session is over at {@code now}, or null while it lives. A side that is connected has leftAt = 0. */
    static String expiry(boolean deviceEverJoined, long createdAt, long deviceLeftAt, long consoleLeftAt,
                         long lastActivity, long now) {
        if (consoleLeftAt > 0 && now - consoleLeftAt > SupportSessions.REJOIN_MILLIS) return "console";
        if (!deviceEverJoined && now - createdAt > SupportSessions.PENDING_MILLIS) return "timeout";
        if (deviceEverJoined && deviceLeftAt > 0 && now - deviceLeftAt > SupportSessions.REJOIN_MILLIS) return "device";
        if (now - lastActivity > SupportSessions.IDLE_MILLIS) return "idle";
        return null;
    }

    private void account(Live live, String role, String type, JsonNode m) {
        synchronized (live) {
            if (SupportSessions.isInput(type)) live.input++;
            if ("chat".equals(type)) live.chat++;
            if ("file.put".equals(type)) live.files++;
            if ("call.state".equals(type)) {
                String s = m.path("s").asText("");
                if ("active".equals(s) && live.callSince == 0) live.callSince = System.currentTimeMillis();
                if (("ended".equals(s) || "declined".equals(s) || "error".equals(s)) && live.callSince > 0) {
                    live.voiceMillis += System.currentTimeMillis() - live.callSince;
                    live.callSince = 0;
                }
            }
            if ("call.end".equals(type) && live.callSince > 0) {
                live.voiceMillis += System.currentTimeMillis() - live.callSince;
                live.callSince = 0;
            }
        }
        if ("info".equals(type) && SupportSessions.ROLE_DEVICE.equals(role) && m.hasNonNull("engine")) {
            try { dao.setEngine(live.dbId, m.path("engine").asText()); } catch (Exception e) { log.debug("support: {}", e.getMessage()); }
        }
        String[] audit = SupportSessions.audit(type, m);
        if (audit != null) {
            try {
                dao.addEvent(live.dbId, System.currentTimeMillis(), audit[0], role, audit[1]);
            } catch (Exception e) {
                log.warn("Remote support: could not log {} of session {}: {}", audit[0], live.dbId, e.getMessage());
            }
        }
    }

    /** A power action (lock, restart) the operator ran from the session bar: logged with the session. */
    public void logAction(Live live, String what) {
        try {
            dao.addEvent(live.dbId, System.currentTimeMillis(), "action", SupportSessions.ROLE_CONSOLE, what);
        } catch (Exception e) {
            log.warn("Remote support: could not log action: {}", e.getMessage());
        }
    }

    // One writer at a time per socket: a JSR-356 remote endpoint refuses concurrent sends.
    private static void send(Session to, String text) {
        try {
            synchronized (to) {
                to.getBasicRemote().sendText(text);
            }
        } catch (Exception e) {
            log.debug("support send failed: {}", e.getMessage());
        }
    }

    private static void send(Session to, ByteBuffer data) {
        try {
            synchronized (to) {
                to.getBasicRemote().sendBinary(data);
            }
        } catch (Exception e) {
            log.debug("support send failed: {}", e.getMessage());
        }
    }

    private static void closeQuietly(Session s, String reason) {
        try {
            s.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, reason));
        } catch (Exception ignored) {
        }
    }
}
