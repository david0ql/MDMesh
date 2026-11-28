package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.SecureRandom;

/**
 * The rules of leaving a kiosk on the device: it always needs an exit password.
 *
 * <ul>
 *     <li>A kiosk policy is saved with a password of at least {@link #MIN_LENGTH} characters; when none is given the
 *     server generates one ({@link #generate()}).</li>
 *     <li>A kiosk sent to a device without a password (an older policy row, an ad-hoc {@code kiosk.enter}) is sent
 *     with {@code exitMode = "remote"}: no exit affordance on the device at all. That is what closes agents released
 *     before the on-device check existed (they left the kiosk without asking when no password was set); the kiosk is
 *     then left only from the console ({@code kiosk.exit}).</li>
 * </ul>
 */
public final class KioskExitPolicy {
    public static final int MIN_LENGTH = 4;
    public static final int MAX_LENGTH = 64;
    public static final int GENERATED_DIGITS = 8;
    public static final String EXIT_REMOTE = "remote";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();

    private KioskExitPolicy() {}

    public static boolean isBlank(String password) {
        return password == null || password.trim().isEmpty();
    }

    /** A password an administrator may set: {@link #MIN_LENGTH}..{@link #MAX_LENGTH} characters, no control ones. */
    public static boolean isAcceptable(String password) {
        if (password == null) return false;
        int n = password.length();
        if (password.trim().length() < MIN_LENGTH || n > MAX_LENGTH) return false;
        for (int i = 0; i < n; i++) if (Character.isISOControl(password.charAt(i))) return false;
        return true;
    }

    /** A random {@link #GENERATED_DIGITS}-digit password (digits: easy to dictate and to type on a phone). */
    public static String generate() {
        StringBuilder sb = new StringBuilder(GENERATED_DIGITS);
        for (int i = 0; i < GENERATED_DIGITS; i++) sb.append((char) ('0' + RANDOM.nextInt(10)));
        return sb.toString();
    }

    /** The exit mode to send: the wanted one when there is a password, else {@code remote} (console only). */
    public static String effectiveExitMode(String wanted, String password) {
        return isBlank(password) ? EXIT_REMOTE : wanted;
    }

    /**
     * A {@code kiosk.enter} payload as it may be sent to a device: without a password its exit mode becomes
     * {@code remote}. Anything that is not a JSON object is returned unchanged (the agent refuses it itself).
     */
    public static String hardenEnterPayload(String payload) {
        try {
            JsonNode n = payload == null || payload.trim().isEmpty() ? JSON.createObjectNode() : JSON.readTree(payload);
            if (!(n instanceof ObjectNode)) return payload;
            ObjectNode o = (ObjectNode) n;
            JsonNode pw = o.get("password");
            if (pw != null && pw.isTextual() && !isBlank(pw.asText())) return payload;
            o.remove("password");
            o.put("exitMode", EXIT_REMOTE);
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return payload;
        }
    }
}
