package com.hmdm.rest.resource.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Set;

/**
 * Decides which agent command types may be fanned out to many devices at once (bulk / folder / global).
 *
 * <p>The destructive group is kept off the mass paths: a factory reset ({@code device.wipe}) can never
 * be issued in bulk. The passcode command ({@code device.passcodeReset}) is a special case — it both
 * SETS and CLEARS the lock password depending on its payload:</p>
 * <ul>
 *   <li><b>Set a lock password</b> (non-empty {@code newPassword}) — allowed in bulk: this is the
 *       "parametrizar la contraseña de bloqueo de forma masiva" feature. Still disruptive, so the caller
 *       must additionally hold {@code disruptive_actions}.</li>
 *   <li><b>Clear the lock password</b> (empty / absent {@code newPassword}) — NEVER in bulk: unlocking a
 *       whole fleet at once is treated as destructive. Clearing stays a per-device action.</li>
 * </ul>
 *
 * <p>Matching is case-insensitive so a mixed-case type cannot slip past the guard.</p>
 */
public final class BulkCommandPolicy {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Types that must never be fanned out, whatever the payload (irreversible). */
    private static final Set<String> ALWAYS_FORBIDDEN = Set.of("device.wipe");

    /** Lowercased passcode type (sets or clears the device lock password). */
    private static final String PASSCODE_RESET = "device.passcodereset";

    private BulkCommandPolicy() {
    }

    /**
     * @return {@code true} if a command of this {@code type} with this {@code payload} may be queued for many
     *         devices at once. {@code device.passcodeReset} is allowed only when it SETS a non-empty password.
     */
    public static boolean allowedInBulk(String type, String payload) {
        if (type == null) {
            return false;
        }
        final String t = type.trim().toLowerCase(Locale.ROOT);
        if (ALWAYS_FORBIDDEN.contains(t)) {
            return false;
        }
        if (PASSCODE_RESET.equals(t)) {
            return setsNonEmptyPassword(payload);
        }
        return true;
    }

    /** True only when the payload is {@code {"newPassword": "<non-empty>"}} — i.e. a SET, not a clear. */
    private static boolean setsNonEmptyPassword(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return false;
        }
        try {
            JsonNode node = MAPPER.readTree(payload);
            JsonNode pw = node.get("newPassword");
            return pw != null && pw.isTextual() && !pw.asText().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
