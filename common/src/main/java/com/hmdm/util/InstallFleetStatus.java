package com.hmdm.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where the installation of one app version stands on one phone, read from the {@code app.install} commands queued
 * for it (this class only reads them). It is what the console's "Instalación en la flota" shows: installed, on its
 * way, held back (and why), or failed (and why, and whether it will be tried again).
 */
public final class InstallFleetStatus {

    public enum State {
        /** The phone answered that the app is at the wanted version. */
        INSTALLED,
        /** Queued; the phone takes it on its next connection. */
        PENDING,
        /** The phone has the order and is downloading/installing. */
        INSTALLING,
        /** Queued, but the phone cannot run it as it is (not Device Owner…): it waits until that changes. */
        HELD,
        /** The phone answered with an error, or the order expired. */
        FAILED,
        /** Nothing was queued for this version (the policy was not saved after adding it, or it is not installable). */
        NOT_QUEUED
    }

    /** One {@code app.install} command of a phone, as stored. */
    public static final class Cmd {
        public final int id;
        public final String status;
        public final String detail;
        /** The versionCode the command asks for; 0 = any. */
        public final long versionCode;
        public final String requiresCapability;
        public final long createdAt;
        public final Long completedAt;

        public Cmd(int id, String status, String detail, long versionCode, String requiresCapability, long createdAt, Long completedAt) {
            this.id = id;
            this.status = status;
            this.detail = detail;
            this.versionCode = versionCode;
            this.requiresCapability = requiresCapability;
            this.createdAt = createdAt;
            this.completedAt = completedAt;
        }
    }

    public static final class Result {
        public State state;
        /** Short key of the reason (see {@link InstallFailureReasons.Reason#code}; for HELD: notOwner / noCapabilities). */
        public String reasonCode;
        /** The reason in Spanish, for HELD and FAILED (and a short note for the others). */
        public String reason;
        /** What the phone answered, verbatim. */
        public String detail;
        /** The command this was read from. */
        public Integer commandId;
        /** Failed attempts in a row for this version. */
        public int attempts;
        /** When the next automatic retry is due; null = none. */
        public Long nextRetryAt;
        public boolean retryable;
        /** When the state was reached (the command's completion, else its creation). */
        public Long at;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("state", state.name().toLowerCase());
            m.put("reasonCode", reasonCode);
            m.put("reason", reason);
            m.put("detail", detail);
            m.put("commandId", commandId);
            m.put("attempts", attempts);
            m.put("nextRetryAt", nextRetryAt);
            m.put("retryable", retryable);
            m.put("at", at);
            return m;
        }
    }

    private InstallFleetStatus() {}

    public static final String HELD_NOT_OWNER = "El equipo no es Device Owner (no reporta la capacidad de instalación silenciosa): "
            + "la orden queda retenida y no se instalará. Vuelve a enrolar el equipo como Device Owner (QR tras restablecerlo de fábrica, o adb).";
    public static final String HELD_NO_CAPABILITIES = "El equipo todavía no ha reportado sus capacidades (no se ha conectado desde que se enroló): "
            + "la orden espera a su primera conexión.";

    /** True when the agent's "done" answer really means the phone's system refused the app for good. */
    static boolean isOsBlockedSkip(String detail) {
        return detail != null && detail.startsWith("skipped:") && detail.contains("no deja instalar");
    }

    /**
     * @param targetVersionCode the version the policy wants (0 = whichever)
     * @param commands          the phone's app.install commands for the package, oldest first
     * @param deviceTokens      the phone's capability tokens (see {@link AgentCapabilityTokens#flatten})
     */
    public static Result classify(long targetVersionCode, List<Cmd> commands, Set<String> deviceTokens) {
        Result r = new Result();
        Cmd cur = null;
        int curIndex = -1;
        for (int i = commands.size() - 1; i >= 0; i--) {
            Cmd c = commands.get(i);
            if (targetVersionCode <= 0 || c.versionCode <= 0 || c.versionCode == targetVersionCode) {
                cur = c;
                curIndex = i;
                break;
            }
        }
        if (cur == null) {
            r.state = State.NOT_QUEUED;
            r.reasonCode = "notQueued";
            r.reason = "No hay una orden de instalación de esta versión para el equipo. Pulsa Reintentar para enviarla.";
            r.retryable = true;
            return r;
        }
        r.commandId = cur.id;
        r.detail = cur.detail;
        r.at = cur.completedAt != null ? cur.completedAt : Long.valueOf(cur.createdAt);
        String status = cur.status == null ? "" : cur.status;
        switch (status) {
            case "pending":
                if (!AgentCapabilityTokens.isAllowed(cur.requiresCapability, deviceTokens)) {
                    r.state = State.HELD;
                    boolean none = deviceTokens == null || deviceTokens.isEmpty();
                    r.reasonCode = none ? "noCapabilities" : "notOwner";
                    r.reason = none ? HELD_NO_CAPABILITIES : HELD_NOT_OWNER;
                } else {
                    r.state = State.PENDING;
                    r.reason = "En cola: se entrega en la próxima conexión del equipo.";
                }
                return r;
            case "delivered":
                r.state = State.INSTALLING;
                r.reason = "El equipo recibió la orden y está descargando o instalando.";
                return r;
            case "done":
                if (!isOsBlockedSkip(cur.detail)) {
                    r.state = State.INSTALLED;
                    return r;
                }
                fail(r, InstallFailureReasons.translate(cur.detail));
                break;
            case "expired":
                fail(r, InstallFailureReasons.EXPIRED);
                break;
            case "unsupported":
                fail(r, InstallFailureReasons.UNSUPPORTED);
                break;
            default:
                fail(r, InstallFailureReasons.translate(cur.detail));
        }
        // Failed attempts in a row for this version, ending at the current one.
        int attempts = 0;
        for (int i = curIndex; i >= 0; i--) {
            Cmd c = commands.get(i);
            if (targetVersionCode > 0 && c.versionCode > 0 && c.versionCode != targetVersionCode) continue;
            boolean failed = "failed".equals(c.status) || "expired".equals(c.status)
                    || ("done".equals(c.status) && isOsBlockedSkip(c.detail));
            if (!failed) break;
            attempts++;
        }
        r.attempts = attempts;
        r.nextRetryAt = InstallRetryPolicy.nextRetryAt(attempts, r.at, r.retryable);
        return r;
    }

    private static void fail(Result r, InstallFailureReasons.Reason reason) {
        r.state = State.FAILED;
        r.reasonCode = reason.code;
        r.reason = reason.text;
        r.retryable = reason.retryable;
    }

    /** The versionCode and package an app.install payload asks for ({@code {0, null}} parts when unreadable). */
    public static Object[] payloadTarget(String payload) {
        try {
            com.fasterxml.jackson.databind.JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
            String pkg = n.path("packageName").isTextual() ? n.path("packageName").asText() : null;
            return new Object[]{n.path("versionCode").asLong(0), pkg};
        } catch (Exception e) {
            return new Object[]{0L, null};
        }
    }
}
