package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>The payloads of the two anti-theft commands, {@code device.wipe} and {@code device.lostMode}: what the server
 * stores when one is queued (only known fields, bounded) and the Spanish line that says what was ordered — shown in
 * the command history and kept in the device's events, so who ordered a wipe and with which options stays on record.</p>
 *
 * <p>Pure and total: an unreadable payload becomes the defaults (a wipe with no options keeps the reset protection).</p>
 */
public final class ProtectionCommands {

    public static final String WIPE = "device.wipe";
    public static final String LOST_MODE = "device.lostMode";
    public static final String INTENT_LOST = "lost";
    public static final String INTENT_REASSIGN = "reassign";
    /** Prefix of the detail of the agent's announcement, sent right before it wipes. */
    public static final String WIPE_STARTING = "wipe:starting";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_REASON = 200;
    private static final int MAX_MESSAGE = 240;
    private static final int MAX_PHONE = 40;

    private ProtectionCommands() {
    }

    private static JsonNode read(String payload) {
        if (payload == null || payload.trim().isEmpty()) return JSON.createObjectNode();
        try {
            JsonNode n = JSON.readTree(payload);
            return n != null && n.isObject() ? n : JSON.createObjectNode();
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    private static String text(JsonNode n, String key, int max) {
        JsonNode v = n.path(key);
        if (!v.isTextual()) return null;
        String s = v.asText().replaceAll("\\p{Cntrl}", " ").trim();
        if (s.isEmpty()) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static Integer bounded(JsonNode n, String key, int min, int max) {
        JsonNode v = n.path(key);
        if (!v.isNumber() && !(v.isTextual() && v.asText().trim().matches("^[0-9]{1,6}$"))) return null;
        int i = v.isNumber() ? v.asInt() : Integer.parseInt(v.asText().trim());
        if (i <= 0) return null;
        return Math.max(min, Math.min(max, i));
    }

    /** Whether a wipe with this payload keeps the factory-reset protection (kept unless reassigning or asked otherwise). */
    public static boolean keepsFrp(String payload) {
        JsonNode n = read(payload);
        if (n.path("keepFrp").isBoolean()) return n.path("keepFrp").asBoolean();
        return !INTENT_REASSIGN.equals(n.path("intent").asText(null));
    }

    /** The wipe payload as stored: known fields only. */
    public static String normalizeWipe(String payload) {
        JsonNode n = read(payload);
        ObjectNode out = JSON.createObjectNode();
        String intent = n.path("intent").asText(null);
        if (INTENT_LOST.equals(intent) || INTENT_REASSIGN.equals(intent)) out.put("intent", intent);
        out.put("keepFrp", keepsFrp(payload));
        if (n.path("externalStorage").asBoolean(false)) out.put("externalStorage", true);
        if (n.path("euicc").asBoolean(false)) out.put("euicc", true);
        if (n.path("silent").asBoolean(false)) out.put("silent", true);
        String reason = text(n, "reason", MAX_REASON);
        if (reason != null) out.put("reason", reason);
        return out.toString();
    }

    /** "Equipo perdido o robado · protección de restablecimiento: se conserva · tarjeta SD: sí · …" */
    public static String describeWipe(String payload) {
        JsonNode n = read(payload);
        List<String> parts = new ArrayList<String>();
        String intent = n.path("intent").asText(null);
        if (INTENT_LOST.equals(intent)) parts.add("Equipo perdido o robado");
        else if (INTENT_REASSIGN.equals(intent)) parts.add("Preparar para reasignar");
        parts.add("protección de restablecimiento (FRP): " + (keepsFrp(payload) ? "se conserva" : "se elimina"));
        parts.add("tarjeta SD: " + (n.path("externalStorage").asBoolean(false) ? "se borra" : "no se borra"));
        if (n.path("euicc").asBoolean(false)) parts.add("eSIM: se borra");
        if (n.path("silent").asBoolean(false)) parts.add("sin aviso en pantalla");
        String reason = text(n, "reason", MAX_REASON);
        if (reason != null) parts.add("motivo: «" + reason + "»");
        return String.join(" · ", parts);
    }

    /** The lost-mode payload as stored: known fields only, numbers bounded. */
    public static String normalizeLostMode(String payload) {
        JsonNode n = read(payload);
        ObjectNode out = JSON.createObjectNode();
        boolean enabled = !n.path("enabled").isBoolean() || n.path("enabled").asBoolean();
        out.put("enabled", enabled);
        if (!enabled) return out.toString();
        String message = text(n, "message", MAX_MESSAGE);
        if (message != null) out.put("message", message);
        String phone = text(n, "phone", MAX_PHONE);
        if (phone != null) out.put("phone", phone);
        Integer ring = bounded(n, "ringSeconds", 5, 300);
        if (ring != null) out.put("ringSeconds", ring);
        Integer track = bounded(n, "trackMinutes", 1, 60);
        if (track != null) out.put("trackMinutes", track);
        Integer unlocks = bounded(n, "wipeAfterFailedUnlocks", 3, 100);
        if (unlocks != null) out.put("wipeAfterFailedUnlocks", unlocks);
        Integer days = bounded(n, "wipeIfSilentDays", 1, 365);
        if (days != null) out.put("wipeIfSilentDays", days);
        return out.toString();
    }

    public static String describeLostMode(String payload) {
        JsonNode n = read(normalizeLostMode(payload));
        if (!n.path("enabled").asBoolean(true)) return "Desactivar modo perdido";
        List<String> parts = new ArrayList<String>();
        parts.add("Activar modo perdido");
        if (n.has("message")) parts.add("mensaje: «" + n.path("message").asText() + "»");
        if (n.has("phone")) parts.add("teléfono de contacto: " + n.path("phone").asText());
        if (n.has("ringSeconds")) parts.add("sonar " + n.path("ringSeconds").asInt() + " s");
        parts.add("ubicación cada " + n.path("trackMinutes").asInt(1) + " min");
        if (n.has("wipeAfterFailedUnlocks")) {
            parts.add("borrar tras " + n.path("wipeAfterFailedUnlocks").asInt() + " intentos fallidos de desbloqueo");
        }
        if (n.has("wipeIfSilentDays")) parts.add("borrar si no reporta en " + n.path("wipeIfSilentDays").asInt() + " días");
        return String.join(" · ", parts);
    }

    /** What the history shows next to the command's name; null for every other type. */
    public static String subjectOf(String type, String payload) {
        if (WIPE.equals(type)) return describeWipe(payload);
        if (LOST_MODE.equals(type)) return describeLostMode(payload);
        return null;
    }

    /** The agent's "I am about to wipe" announcement. */
    public static boolean isWipeStarting(String type, String status, String detail) {
        return WIPE.equalsIgnoreCase(type) && "accepted".equals(status) && detail != null && detail.startsWith(WIPE_STARTING);
    }
}
