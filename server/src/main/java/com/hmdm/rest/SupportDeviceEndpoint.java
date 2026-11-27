/*
 * DallyControl remote support (ADR 0012): the device's side of a support session.
 *
 * The agent opens wss://<server>/agent/ws/{deviceNumber}/support/{sessionKey} with its per-device secret as a
 * bearer token (same check as the wake channel) once the console started a session on it. Everything it sends is
 * relayed to the operator's socket by SupportHub.
 */
package com.hmdm.rest;

import com.hmdm.notification.AgentWakeHub;
import com.hmdm.notification.SupportHub;
import com.hmdm.util.SupportSessions;

import javax.websocket.CloseReason;
import javax.websocket.HandshakeResponse;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

@ServerEndpoint(value = "/agent/ws/{deviceNumber}/support/{sessionKey}",
        configurator = SupportDeviceEndpoint.AuthConfigurator.class)
public class SupportDeviceEndpoint {

    private static final String SECRET_PROP = "mdm.support.secret";
    /** A screen frame is one message: room for a large JPEG. */
    static final int MAX_BINARY = 4 * 1024 * 1024;
    static final int MAX_TEXT = 512 * 1024;
    /** A phone that stops answering pings is dropped after this. */
    static final long IDLE_TIMEOUT_MS = 90_000L;

    /** Copies the Authorization bearer token off the handshake into the session's user properties. */
    public static final class AuthConfigurator extends ServerEndpointConfig.Configurator {
        @Override
        public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request, HandshakeResponse response) {
            String bearer = null;
            Map<String, List<String>> headers = request.getHeaders();
            if (headers != null) {
                for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                    if ("Authorization".equalsIgnoreCase(e.getKey()) && e.getValue() != null && !e.getValue().isEmpty()) {
                        String v = e.getValue().get(0).trim();
                        if (v.regionMatches(true, 0, "Bearer ", 0, 7) && v.length() > 7) bearer = v.substring(7).trim();
                    }
                }
            }
            if (bearer != null) {
                config.getUserProperties().put(SECRET_PROP, bearer);
            } else {
                config.getUserProperties().remove(SECRET_PROP);
            }
        }
    }

    @OnOpen
    public void onOpen(Session session, @PathParam("deviceNumber") String deviceNumber,
                       @PathParam("sessionKey") String sessionKey) {
        Object secret = session.getUserProperties().get(SECRET_PROP);
        AgentWakeHub wake = AgentWakeHub.INSTANCE;
        SupportHub hub = SupportHub.INSTANCE;
        session.setMaxBinaryMessageBufferSize(MAX_BINARY);
        session.setMaxTextMessageBufferSize(MAX_TEXT);
        session.setMaxIdleTimeout(IDLE_TIMEOUT_MS);
        if (wake == null || hub == null || !(secret instanceof String) || !SupportSessions.isKey(sessionKey)
                || !wake.validate(deviceNumber, (String) secret)
                || !hub.deviceOpen(deviceNumber, sessionKey, session)) {
            close(session, "unauthorized");
        }
    }

    @OnMessage(maxMessageSize = MAX_TEXT)
    public void onText(Session session, String text, @PathParam("sessionKey") String sessionKey) {
        if (SupportHub.INSTANCE != null) {
            SupportHub.INSTANCE.onText(SupportSessions.ROLE_DEVICE, sessionKey, session, text);
        }
    }

    @OnMessage(maxMessageSize = MAX_BINARY)
    public void onBinary(Session session, ByteBuffer data, @PathParam("sessionKey") String sessionKey) {
        if (SupportHub.INSTANCE != null) {
            SupportHub.INSTANCE.onBinary(SupportSessions.ROLE_DEVICE, sessionKey, session, data);
        }
    }

    @OnClose
    public void onClose(Session session, @PathParam("sessionKey") String sessionKey) {
        if (SupportHub.INSTANCE != null) {
            SupportHub.INSTANCE.onClose(SupportSessions.ROLE_DEVICE, sessionKey, session);
        }
    }

    @OnError
    public void onError(Session session, Throwable t, @PathParam("sessionKey") String sessionKey) {
        if (SupportHub.INSTANCE != null) {
            SupportHub.INSTANCE.onClose(SupportSessions.ROLE_DEVICE, sessionKey, session);
        }
    }

    static void close(Session session, String reason) {
        try {
            session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, reason));
        } catch (IOException ignored) {
        }
    }
}
