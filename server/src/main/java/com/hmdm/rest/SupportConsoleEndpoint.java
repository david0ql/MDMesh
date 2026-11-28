/*
 * DallyControl remote support (ADR 0012): the operator's side of a support session.
 *
 * The console opens wss://<server>/agent/ws/support/console/{sessionKey} and sends {"t":"auth","token":"…"} as its
 * first message: the one-time token only the operator who started the session received (POST …/support/v1/devices/
 * {id}/start, which needs a signed-in console user with edit_devices and reach over the device's folder). The token
 * travels in a message, never in the URL, so it stays out of access logs.
 */
package com.hmdm.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdm.notification.SupportHub;
import com.hmdm.util.SupportSessions;

import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.nio.ByteBuffer;

@ServerEndpoint("/agent/ws/support/console/{sessionKey}")
public class SupportConsoleEndpoint {

    private static final String AUTHED = "mdm.support.authed";

    @OnOpen
    public void onOpen(Session session, @PathParam("sessionKey") String sessionKey) {
        session.setMaxBinaryMessageBufferSize(SupportDeviceEndpoint.MAX_BINARY);
        session.setMaxTextMessageBufferSize(SupportDeviceEndpoint.MAX_TEXT);
        session.setMaxIdleTimeout(SupportDeviceEndpoint.IDLE_TIMEOUT_MS);
        if (SupportHub.INSTANCE == null || !SupportSessions.isKey(sessionKey)) {
            SupportDeviceEndpoint.close(session, "unauthorized");
        }
    }

    @OnMessage(maxMessageSize = SupportDeviceEndpoint.MAX_TEXT)
    public void onText(Session session, String text, @PathParam("sessionKey") String sessionKey) {
        SupportHub hub = SupportHub.INSTANCE;
        if (hub == null) return;
        if (!Boolean.TRUE.equals(session.getUserProperties().get(AUTHED))) {
            JsonNode m = SupportSessions.parse(text);
            String token = m != null && "auth".equals(SupportSessions.type(m)) ? m.path("token").asText(null) : null;
            if (token != null && hub.consoleAuth(sessionKey, token, session)) {
                session.getUserProperties().put(AUTHED, Boolean.TRUE);
            } else {
                SupportDeviceEndpoint.close(session, "unauthorized");
            }
            return;
        }
        hub.onText(SupportSessions.ROLE_CONSOLE, sessionKey, session, text);
    }

    @OnMessage(maxMessageSize = SupportDeviceEndpoint.MAX_BINARY)
    public void onBinary(Session session, ByteBuffer data, @PathParam("sessionKey") String sessionKey) {
        if (SupportHub.INSTANCE != null && Boolean.TRUE.equals(session.getUserProperties().get(AUTHED))) {
            SupportHub.INSTANCE.onBinary(SupportSessions.ROLE_CONSOLE, sessionKey, session, data);
        }
    }

    @OnClose
    public void onClose(Session session, @PathParam("sessionKey") String sessionKey) {
        if (SupportHub.INSTANCE != null) {
            SupportHub.INSTANCE.onClose(SupportSessions.ROLE_CONSOLE, sessionKey, session);
        }
    }

    @OnError
    public void onError(Session session, Throwable t, @PathParam("sessionKey") String sessionKey) {
        if (SupportHub.INSTANCE != null) {
            SupportHub.INSTANCE.onClose(SupportSessions.ROLE_CONSOLE, sessionKey, session);
        }
    }
}
