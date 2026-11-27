/*
 * DallyControl agent wake hub: holds live device WebSocket sessions and fans out wake-only signals.
 */
package com.hmdm.notification;

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.util.CryptoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.websocket.Session;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of live agent WebSocket sessions (one per device number) plus the wake fan-out.
 *
 * <p>The wake payload is signal-only ({@code {"wake":"commands"}}) — never a command — so a leaked
 * connection reveals nothing; the device still authenticates its actual sync with its bearer secret.
 * Sessions are authenticated at connect via that same per-device secret (see {@link #validate}).</p>
 *
 * <p>Reached from the container-managed {@code AgentWakeEndpoint} via {@link #INSTANCE} (JSR-356
 * endpoints aren't Guice-instantiated); the hub is an eager singleton so {@code INSTANCE} is set at
 * boot, before any device can connect.</p>
 */
@Singleton
public class AgentWakeHub {

    private static final Logger log = LoggerFactory.getLogger(AgentWakeHub.class);

    /** Static bridge for the container-created WebSocket endpoint. Set in the constructor. */
    public static volatile AgentWakeHub INSTANCE;

    private final AgentCommandDAO commandDAO;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Inject
    public AgentWakeHub(AgentCommandDAO commandDAO) {
        this.commandDAO = commandDAO;
        INSTANCE = this;
    }

    /** Constant-time check of the presented secret against the stored per-device SHA-256 hash. */
    public boolean validate(String deviceNumber, String secret) {
        if (deviceNumber == null || secret == null) {
            return false;
        }
        String expectedHash = commandDAO.getDeviceSecretHash(deviceNumber);
        return expectedHash != null
                && CryptoUtil.constantTimeEquals(CryptoUtil.getSHA256String(secret), expectedHash);
    }

    public void register(String deviceNumber, Session session) {
        Session previous = sessions.put(deviceNumber, session);
        if (previous != null && previous != session && previous.isOpen()) {
            try { previous.close(); } catch (Exception ignored) { }
        }
        log.debug("Agent wake socket registered for {}", deviceNumber);
    }

    public void unregister(String deviceNumber, Session session) {
        sessions.remove(deviceNumber, session);
    }

    /** Device numbers whose live wake socket is open right now. */
    public java.util.List<String> liveNumbers() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Map.Entry<String, Session> e : sessions.entrySet()) {
            if (e.getValue() != null && e.getValue().isOpen()) out.add(e.getKey());
        }
        return out;
    }

    public boolean isOnline(String deviceNumber) {
        Session s = sessions.get(deviceNumber);
        return s != null && s.isOpen();
    }

    /** The push channel; optional so the hub still works (and is built in tests) without it. */
    private volatile com.hmdm.notification.push.PushWaker pushWaker;

    @Inject
    public void setPushWaker(com.hmdm.notification.push.PushWaker pushWaker) {
        this.pushWaker = pushWaker;
    }

    /**
     * Send a wake-only signal to the device. Over the live socket when it is open; otherwise as a push message when
     * the server has a push project and the device a token. With neither, a no-op: the floor reconciles.
     */
    public void wake(String deviceNumber, String wakeKind) {
        boolean interactive = "interactive".equals(wakeKind);
        com.hmdm.notification.push.PushWaker push = pushWaker;
        if (sendOverSocket(deviceNumber, interactive)) {
            // A socket can look open after the phone lost its network: no check-in soon = repeat as a push.
            if (!interactive && push != null) push.escalateIfSilent(deviceNumber);
            return;
        }
        if (!interactive && push != null) push.wake(deviceNumber, false);
    }

    /**
     * Wake for a command that cannot wait (a lock, an alarm, a message, a wipe, a remote session): the live socket
     * AND a push at once, whatever the socket looks like.
     */
    public void wakeUrgent(String deviceNumber) {
        sendOverSocket(deviceNumber, false);
        com.hmdm.notification.push.PushWaker push = pushWaker;
        if (push != null) push.wake(deviceNumber, true);
    }

    /** Wake for a command of the given type: urgent types go out over both channels. */
    public void wakeFor(String deviceNumber, String commandType) {
        if (com.hmdm.notification.push.PushWaker.isUrgent(commandType)) wakeUrgent(deviceNumber);
        else wake(deviceNumber, "commands");
    }

    private boolean sendOverSocket(String deviceNumber, boolean interactive) {
        Session s = sessions.get(deviceNumber);
        if (s == null || !s.isOpen()) {
            return false;
        }
        String payload = interactive
                ? "{\"wake\":\"interactive\",\"ttlSec\":120}"
                : "{\"wake\":\"commands\"}";
        try {
            s.getAsyncRemote().sendText(payload);
            return true;
        } catch (Exception e) {
            log.warn("Agent wake send failed for {}: {}", deviceNumber, e.getMessage());
            return false;
        }
    }
}
