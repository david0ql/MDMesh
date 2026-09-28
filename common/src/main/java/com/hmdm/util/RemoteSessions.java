package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <p>Remote view/control sessions (ADR 0010): what a device offers, and the {@code remote.vnc.start}
 * payload for a new session. The console asks the server to start a session instead of minting ids itself,
 * so the session secret and the transport choice live in one place.</p>
 *
 * <ul>
 *     <li>{@link #TRANSPORT_WSS} ({@code vnc-repeater-wss}): the agent tunnels the VNC stream to the repeater
 *     inside a WebSocket over the server's own HTTPS origin ({@code /remote/device/}), so the whole session is
 *     encrypted and the repeater port needs no public exposure.</li>
 *     <li>{@link #TRANSPORT_TCP} ({@code vnc-repeater}): older agents dial the repeater port directly; only the
 *     VNC password exchange is protected.</li>
 * </ul>
 */
public final class RemoteSessions {

    public static final String COMMAND_START = "remote.vnc.start";
    public static final String COMMAND_STOP = "remote.vnc.stop";
    public static final String TRANSPORT_TCP = "vnc-repeater";
    public static final String TRANSPORT_WSS = "vnc-repeater-wss";

    /** How long after a {@code remote.vnc.start} was queued its device may open the encrypted tunnel. */
    public static final long TUNNEL_WINDOW_MILLIS = 15L * 60L * 1000L;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long SESSION_ID_MIN = 100_000_000_000_000_000L; // 18 digits: fits a signed long
    private static final long SESSION_ID_SPAN = 900_000_000_000_000_000L;

    private RemoteSessions() {
    }

    /** The {@code tier} the device advertised ({@code none}, {@code view}, {@code control}). */
    public static String tier(String capabilitiesJson) {
        JsonNode remote = remoteControl(capabilitiesJson);
        String tier = remote == null ? null : remote.path("tier").asText(null);
        return tier == null || tier.isEmpty() ? "none" : tier;
    }

    /** The transports the device advertised, empty when none. */
    public static List<String> transports(String capabilitiesJson) {
        JsonNode remote = remoteControl(capabilitiesJson);
        if (remote == null || !remote.path("transport").isArray()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        remote.path("transport").forEach(t -> out.add(t.asText()));
        return out;
    }

    public static boolean supportsEncrypted(String capabilitiesJson) {
        return transports(capabilitiesJson).contains(TRANSPORT_WSS);
    }

    /** One-time numeric session id: the Mode-II repeater pairs only positive decimal ids that fit a long. */
    public static String newSessionId() {
        long n = SESSION_ID_MIN + (long) (RANDOM.nextDouble() * SESSION_ID_SPAN);
        return Long.toString(Math.max(SESSION_ID_MIN, n));
    }

    /** VNC password: classic VNC auth uses at most 8 characters. */
    public static String newPassword() {
        byte[] b = new byte[4];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    /** The {@code remote.vnc.start} payload (a JSON string, as the command queue stores payloads). */
    public static String startPayload(String sessionId, String password, boolean viewOnly, boolean encrypted) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("sessionId", sessionId);
        p.put("password", password);
        p.put("viewOnly", viewOnly);
        if (encrypted) {
            p.put("transport", "wss");
        }
        return p.toString();
    }

    private static JsonNode remoteControl(String capabilitiesJson) {
        if (capabilitiesJson == null || capabilitiesJson.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(capabilitiesJson).get("remoteControl");
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }
}
