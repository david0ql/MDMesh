package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <p>One line, in Spanish, for the console: is the factory-reset protection of a device in force, and if not, why.
 * Built from what the agent reads back from the system ({@code telemetry.security.protection}) and from how many
 * accounts the device should hold according to its policy, folder and company.</p>
 *
 * <p>Pure: no I/O.</p>
 */
public final class ProtectionSummary {

    public static final String ACTIVE = "active";
    public static final String INACTIVE = "inactive";
    /** The device holds a protection that is not the one it should (other accounts), or has not applied it yet. */
    public static final String ATTENTION = "attention";
    /** The agent has not reported it (an older agent, or no report yet). */
    public static final String UNKNOWN = "unknown";

    public final String state;
    public final String text;

    private ProtectionSummary(String state, String text) {
        this.state = state;
        this.text = text;
    }

    private static final String FREE = " (tras un restablecimiento por botones el equipo quedaría libre)";

    /**
     * @param protection the agent's {@code security.protection} block, or null when it never reported one
     * @param expected   how many accounts the device should hold
     */
    public static ProtectionSummary frp(JsonNode protection, int expected) {
        JsonNode frp = protection == null ? null : protection.get("frp");
        if (frp == null || !frp.isObject()) {
            return expected == 0
                    ? new ProtectionSummary(INACTIVE, "FRP inactiva: no hay cuentas configuradas" + FREE)
                    : new ProtectionSummary(UNKNOWN, "FRP sin confirmar: el agente de este equipo aún no reporta su estado "
                    + "(agente anterior, o todavía no ha sincronizado)");
        }
        boolean active = frp.path("active").asBoolean(false);
        int held = frp.path("accounts").isArray() ? frp.path("accounts").size() : 0;
        String reason = frp.path("reason").isTextual() ? frp.path("reason").asText() : null;
        String error = frp.path("error").isTextual() ? frp.path("error").asText() : null;
        if (active && reason == null) {
            return new ProtectionSummary(ACTIVE, "FRP activa con " + held + (held == 1 ? " cuenta" : " cuentas"));
        }
        if (active) {
            return new ProtectionSummary(ATTENTION, "FRP activa con " + held + (held == 1 ? " cuenta" : " cuentas")
                    + ", pero no son las de la política; se corrige en la próxima sincronización");
        }
        if (reason == null) reason = "error";
        switch (reason) {
            case "noAccounts":
                return expected > 0
                        ? new ProtectionSummary(ATTENTION, "FRP pendiente: hay " + expected + (expected == 1 ? " cuenta configurada" : " cuentas configuradas")
                        + " y el equipo aún no las aplica; se aplica en la próxima sincronización")
                        : new ProtectionSummary(INACTIVE, "FRP inactiva: no hay cuentas configuradas" + FREE);
            case "unsupported":
                return new ProtectionSummary(INACTIVE, "FRP inactiva: este equipo no admite la protección de restablecimiento administrada" + FREE);
            case "gmsMissing":
                return new ProtectionSummary(INACTIVE, "FRP inactiva: el equipo no tiene los Servicios de Google Play, "
                        + "necesarios para la protección en Android 10 o anterior" + FREE);
            case "androidTooOld":
                return new ProtectionSummary(INACTIVE, "FRP inactiva: la versión de Android es anterior a 5.1" + FREE);
            case "notDeviceOwner":
                return new ProtectionSummary(INACTIVE, "FRP inactiva: el agente no es el propietario del dispositivo" + FREE);
            default:
                return new ProtectionSummary(INACTIVE, "FRP inactiva: el equipo no pudo aplicarla"
                        + (error == null ? "" : " (" + (error.length() > 200 ? error.substring(0, 200) : error) + ")"));
        }
    }
}
