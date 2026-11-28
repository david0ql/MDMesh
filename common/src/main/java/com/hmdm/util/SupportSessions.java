package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * <p>Remote-support sessions (ADR 0012): the rules of the session channel the server relays between the console and
 * the agent, kept free of WebSocket types so they can be tested.</p>
 *
 * <p>Both ends speak small JSON text messages ({@code {"t": "<type>", ...}}) plus binary messages whose first byte
 * says what they carry (screen frame, audio, file chunk). The server never decodes frames or audio: it pairs the two
 * sockets, refuses input from a view-only operator, and writes the audit trail (chat lines, files, calls).</p>
 */
public final class SupportSessions {

    /** Command the agent runs to join a session when its live socket was down (the wake carries it otherwise). */
    public static final String COMMAND_START = "remote.session.start";
    public static final String COMMAND_STOP = "remote.session.stop";
    /** Capability token of an agent that speaks the session channel. */
    public static final String CAPABILITY = "device.supportSession";

    public static final String ROLE_CONSOLE = "console";
    public static final String ROLE_DEVICE = "device";

    public static final byte BIN_FRAME = 1;
    public static final byte BIN_AUDIO = 2;
    public static final byte BIN_FILE = 3;

    /** A device that never joins: the session is closed after this. */
    public static final long PENDING_MILLIS = 6L * 60L * 1000L;
    /** A device (or console) that dropped may come back within this before the session ends. */
    public static final long REJOIN_MILLIS = 45L * 1000L;
    /** Nothing but keep-alives for this long: the session ends by itself. */
    public static final long IDLE_MILLIS = 30L * 60L * 1000L;

    private static final Set<String> INPUT = new HashSet<>(Arrays.asList("tap", "swipe", "key", "text", "clip.set"));
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private SupportSessions() {
    }

    /** Public id of a session (in URLs): 24 hex characters. */
    public static String newKey() {
        return hex(12);
    }

    /** The operator's secret for the console socket: 64 hex characters, returned once by the start call. */
    public static String newToken() {
        return hex(32);
    }

    public static boolean isKey(String s) {
        return s != null && s.matches("^[0-9a-f]{24}$");
    }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** The {@code t} of a text message, or null when it is not one of ours. */
    public static String type(JsonNode message) {
        JsonNode t = message == null ? null : message.get("t");
        return t != null && t.isTextual() ? t.asText() : null;
    }

    public static JsonNode parse(String text) {
        try {
            JsonNode n = MAPPER.readTree(text);
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Messages that act on the phone (touch, keys, typed text, clipboard): refused in a view-only session. */
    public static boolean isInput(String type) {
        return type != null && INPUT.contains(type);
    }

    /**
     * Whether the relay passes this console message on to the device.
     *
     * @param viewOnly the session was opened as "solo ver"
     */
    public static boolean consoleMayRelay(String type, boolean viewOnly) {
        if (type == null) return false;
        if ("auth".equals(type)) return false; // the token never travels to the device
        return !(viewOnly && isInput(type));
    }

    /** Binary from the console: audio and file chunks only (a console never sends frames). */
    public static boolean consoleMayRelayBinary(byte kind) {
        return kind == BIN_AUDIO || kind == BIN_FILE;
    }

    /** The audit line of a message, or null when it leaves no trace: [kind, detail]. */
    public static String[] audit(String type, JsonNode m) {
        if (type == null || m == null) return null;
        switch (type) {
            case "chat":
                return new String[] {"chat", m.path("s").asText("")};
            case "file.put":
                return new String[] {"file", m.path("name").asText("") + " (" + m.path("size").asLong(0) + " bytes)"};
            case "clip.set":
                return new String[] {"clipboard", "texto enviado al portapapeles (" + m.path("s").asText("").length() + " caracteres)"};
            case "call.start":
                return new String[] {"call", "llamada iniciada"};
            case "call.state":
                return new String[] {"call", m.path("s").asText("")};
            case "call.end":
                return new String[] {"call", "llamada terminada"};
            case "action":
                return new String[] {"action", m.path("k").asText("")};
            default:
                return null;
        }
    }

    /** First message the device gets once its socket is accepted. */
    public static String sessionMessage(boolean viewOnly, String operator) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("t", "session");
        o.put("viewOnly", viewOnly);
        if (operator != null) o.put("operator", operator);
        return o.toString();
    }

    public static String peerMessage(String role, boolean joined) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("t", "peer");
        o.put("role", role);
        o.put("state", joined ? "joined" : "left");
        return o.toString();
    }

    public static String endMessage(String reason) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("t", "end");
        o.put("reason", reason);
        return o.toString();
    }

    /** Payload of {@link #COMMAND_START} / {@link #COMMAND_STOP}. */
    public static String commandPayload(String sessionKey) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("sessionId", sessionKey);
        return o.toString();
    }

    /** The wake that makes a connected agent join at once, without a check-in round trip. */
    public static String wakePayload(String sessionKey) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("wake", "support");
        o.put("sessionId", sessionKey);
        return o.toString();
    }

    /**
     * What the device said about remote support in its capabilities ({@code remoteControl.support}): session
     * (speaks the channel), screen (ready / needsAuthorization / unsupported / disabled), control, voice, capture.
     * Empty for agents older than the session channel.
     */
    public static Map<String, Object> support(String capabilitiesJson) {
        if (capabilitiesJson == null || capabilitiesJson.isEmpty()) return Collections.emptyMap();
        try {
            JsonNode s = MAPPER.readTree(capabilitiesJson).path("remoteControl").path("support");
            if (!s.isObject()) return Collections.emptyMap();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("session", s.path("session").asBoolean(false));
            out.put("screen", s.path("screen").asText("unsupported"));
            out.put("control", s.path("control").asBoolean(false));
            out.put("voice", s.path("voice").asText("unsupported"));
            out.put("capture", s.path("capture").asText("none"));
            if (s.hasNonNull("selfEnable")) out.put("selfEnable", s.path("selfEnable").asBoolean(false));
            return out;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /**
     * One phrase for the console: listo (unattended sessions work), falta autorización (the one-time switch on the
     * phone is pending), sin soporte (agent or Android too old) or apagado (the policy turned it off).
     */
    public static String readiness(String capabilitiesJson) {
        Map<String, Object> s = support(capabilitiesJson);
        if (!Boolean.TRUE.equals(s.get("session"))) return "unsupported";
        Object screen = s.get("screen");
        if ("ready".equals(screen)) return "ready";
        if ("needsAuthorization".equals(screen)) return "needsAuthorization";
        if ("disabled".equals(screen)) return "disabled";
        return "unsupported";
    }
}
