package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdm.persistence.domain.DeviceConnection;
import com.hmdm.rest.json.agent.AgentConnectionReport;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Connection state of a device as the console shows it, and the reading of what its agent reported about its
 * connection.
 *
 * <p>Three states. <b>online</b>: reported within the window (20 min). <b>resting</b> ("En reposo"): silent, but
 * the last thing known explains it — it was switched off in an orderly way, its battery was about to run out, or
 * its screen was off (a phone put away). <b>offline</b> ("Sin conexión"): silent with no such explanation, or
 * silent for too long. Alerts and reports keep their own "no report for N minutes" rule; this only refines what the
 * device list and detail say.</p>
 */
public final class ConnectionStatus {

    /** Same window as the console and the alerts: a report this recent means connected. */
    public static final long ONLINE_WINDOW_MS = 20 * 60_000L;
    /** A phone put away (screen off, battery spent) counts as resting up to this long: an overnight plus margin. */
    public static final long RESTING_MAX_MS = 16 * 3600_000L;
    /** One switched off on purpose stays "resting" longer: a weekend. */
    public static final long SHUTDOWN_MAX_MS = 72 * 3600_000L;
    /** At or under this charge, not charging, the next silence is the battery. */
    public static final int LOW_BATTERY_PCT = 5;
    /** A goodbye report and the device's last check-in are the same moment, give or take. */
    private static final long GOODBYE_SLACK_MS = 5 * 60_000L;

    public static final String ONLINE = "online";
    public static final String RESTING = "resting";
    public static final String OFFLINE = "offline";

    private static final Set<String> FAILURE_KINDS = new HashSet<>(Arrays.asList(
            "dns", "tls", "timeout", "connect", "http", "wsUpgrade", "rejected", "other"));
    private static final Set<String> STOP_REASONS = new HashSet<>(Arrays.asList(
            "shutdown", "powerLost", "networkLost", "processKilled", "suspended", "serverUnreachable", "unknown"));
    private static final Set<String> NETWORKS = new HashSet<>(Arrays.asList(
            "wifi", "cellular", "ethernet", "vpn", "other", "none", "unknown"));

    private ConnectionStatus() {}

    /** @return {state, reason, label}: reason/label only for "resting". */
    public static Map<String, Object> classify(long now, Long lastUpdate, Integer battery, Boolean charging,
                                               Boolean screenOn, String stopReason, Long stopAt, Long stopResumedAt) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (lastUpdate == null || lastUpdate <= 0) {
            out.put("state", OFFLINE);
            return out;
        }
        long silence = now - lastUpdate;
        if (silence <= ONLINE_WINDOW_MS) {
            out.put("state", ONLINE);
            return out;
        }
        String reason = null;
        boolean saidGoodbye = "shutdown".equals(stopReason) && stopResumedAt == null && stopAt != null
                && stopAt >= lastUpdate - GOODBYE_SLACK_MS;
        if (saidGoodbye && silence <= SHUTDOWN_MAX_MS) {
            reason = "shutdown";
        } else if (silence <= RESTING_MAX_MS) {
            if (battery != null && battery >= 0 && battery <= LOW_BATTERY_PCT && !Boolean.TRUE.equals(charging)) {
                reason = "lowBattery";
            } else if (Boolean.FALSE.equals(screenOn)) {
                reason = "screenOff";
            }
        }
        out.put("state", reason == null ? OFFLINE : RESTING);
        if (reason != null) {
            out.put("reason", reason);
            out.put("label", restingLabel(reason));
        }
        return out;
    }

    public static String restingLabel(String reason) {
        switch (reason) {
            case "shutdown": return "Equipo apagado";
            case "lowBattery": return "Batería agotada";
            case "screenOff": return "Pantalla apagada";
            default: return reason;
        }
    }

    public static String failureLabel(String kind, Integer httpCode) {
        if (kind == null) return null;
        switch (kind) {
            case "dns": return "No se pudo resolver el dominio (DNS)";
            case "tls": return "Certificado TLS rechazado (posible inspección TLS de la red)";
            case "timeout": return "Tiempo de espera agotado";
            case "connect": return "No se pudo abrir la conexión (puerto 443 bloqueado o conexión cortada)";
            case "http": return "El servidor o un intermediario respondió HTTP " + (httpCode == null ? "con error" : httpCode);
            case "wsUpgrade": return "WebSocket rechazado" + (httpCode == null ? "" : " (HTTP " + httpCode + ")");
            case "rejected": return "El servidor rechazó al dispositivo";
            default: return "Error de red";
        }
    }

    public static String stopLabel(String reason) {
        if (reason == null) return null;
        switch (reason) {
            case "shutdown": return "Apagado o reinicio del equipo";
            case "powerLost": return "El equipo se quedó sin energía (batería agotada o apagado forzado)";
            case "networkLost": return "El equipo se quedó sin red";
            case "processKilled": return "El sistema cerró el agente";
            case "suspended": return "El sistema mantuvo el agente dormido (ahorro de batería)";
            case "serverUnreachable": return "Había red, pero no se pudo llegar al servidor";
            default: return "Sin causa identificada";
        }
    }

    public static String networkLabel(String network, String ssid) {
        if (network == null) return null;
        switch (network) {
            case "wifi": return ssid == null || ssid.isEmpty() ? "Wi‑Fi" : "Wi‑Fi «" + ssid + "»";
            case "cellular": return "Datos móviles";
            case "ethernet": return "Ethernet";
            case "vpn": return "VPN";
            case "none": return "Sin red";
            default: return "Otra red";
        }
    }

    /**
     * The row to store for a check-in. {@code report} is null for agents that do not send diagnostics: the row then
     * only carries what the census says (screen, network), which is all "En reposo" needs. Everything from the
     * device is untrusted: unknown kinds are dropped, text is cut to the column sizes.
     */
    public static DeviceConnection toRow(String deviceNumber, long now, AgentConnectionReport report, JsonNode telemetry) {
        DeviceConnection row = new DeviceConnection();
        row.setDeviceNumber(deviceNumber);
        row.setUpdatedAt(now);
        JsonNode dyn = telemetry == null ? null : telemetry.path("dynamic");
        if (dyn != null && dyn.hasNonNull("screenOn")) row.setScreenOn(dyn.get("screenOn").asBoolean());
        if (dyn != null && dyn.hasNonNull("networkType")) row.setNetwork(known(dyn.get("networkType").asText(), NETWORKS, "other"));
        row.setReconnects(0);
        if (report == null) return row;

        row.setServerHost(cut(report.getServerHost(), 255));
        row.setUsingBackup(report.getUsingBackup());
        Integer reconnects = report.getReconnects();
        row.setReconnects(reconnects == null ? 0 : Math.max(0, Math.min(reconnects, 10_000)));

        AgentConnectionReport.Failure f = report.getLastFailure();
        if (f != null && f.getKind() != null && FAILURE_KINDS.contains(f.getKind()) && sane(f.getAt(), now)) {
            row.setFailureKind(f.getKind());
            row.setFailureAt(f.getAt());
            row.setFailureDetail(cut(f.getDetail(), 400));
            row.setFailureHttpCode(f.getHttpCode() != null && f.getHttpCode() >= 100 && f.getHttpCode() <= 599 ? f.getHttpCode() : null);
            row.setFailureChannel("socket".equals(f.getChannel()) ? "socket" : "checkin");
            row.setFailureNetwork(known(f.getNetwork(), NETWORKS, "other"));
            row.setFailureSsid(cut(f.getSsid(), 64));
            row.setFailureHost(cut(f.getHost(), 255));
            row.setFailureIssuer(cut(f.getCertIssuer(), 400));
            row.setFailureCount(f.getCount() == null ? 1 : Math.max(1, Math.min(f.getCount(), 1_000_000)));
        }
        AgentConnectionReport.Stop s = report.getLastStop();
        if (s != null && s.getReason() != null && STOP_REASONS.contains(s.getReason()) && sane(s.getAt(), now)) {
            row.setStopReason(s.getReason());
            row.setStopAt(s.getAt());
            row.setStopResumedAt(s.getResumedAt() != null && s.getResumedAt() >= s.getAt() && sane(s.getResumedAt(), now)
                    ? s.getResumedAt() : null);
            row.setStopBattery(s.getBattery() != null && s.getBattery() >= 0 && s.getBattery() <= 100 ? s.getBattery() : null);
        }
        return row;
    }

    /** Timeline text of a reported failure, e.g. {@code tls · Wi‑Fi «Sede72» · mdm.example.com · emisor: CN=…}. */
    public static String failureEventDetail(DeviceConnection row) {
        StringBuilder sb = new StringBuilder(failureLabel(row.getFailureKind(), row.getFailureHttpCode()));
        String net = networkLabel(row.getFailureNetwork(), row.getFailureSsid());
        if (net != null) sb.append(" · ").append(net);
        if (row.getFailureHost() != null) sb.append(" · ").append(row.getFailureHost());
        if (row.getFailureIssuer() != null) sb.append(" · emisor del certificado: ").append(row.getFailureIssuer());
        if (row.getFailureCount() != null && row.getFailureCount() > 1) sb.append(" · ").append(row.getFailureCount()).append(" intentos");
        return sb.toString();
    }

    /** Timeline text of an explained silence, e.g. {@code El equipo se quedó sin red · 42 min}. */
    public static String gapEventDetail(DeviceConnection row) {
        StringBuilder sb = new StringBuilder(stopLabel(row.getStopReason()));
        if (row.getStopResumedAt() != null) {
            long minutes = Math.max(1, (row.getStopResumedAt() - row.getStopAt()) / 60_000L);
            sb.append(" · ").append(minutes >= 120 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min");
        }
        if (row.getStopBattery() != null) sb.append(" · batería ").append(row.getStopBattery()).append("%");
        return sb.toString();
    }

    /** What the console gets for one device: its state plus the stored diagnostics with Spanish labels. */
    public static Map<String, Object> view(Map<String, Object> r, long now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("number", r.get("number"));
        out.putAll(classify(now, asLong(r.get("lastupdate")), asInt(r.get("battery")), asBool(r.get("charging")),
                asBool(r.get("screenon")), str(r.get("stopreason")), asLong(r.get("stopat")), asLong(r.get("stopresumedat"))));
        out.put("serverHost", r.get("serverhost"));
        out.put("usingBackup", r.get("usingbackup"));
        out.put("reconnects", r.get("reconnects"));
        if (r.get("failurekind") != null) {
            Map<String, Object> f = new LinkedHashMap<>();
            String kind = str(r.get("failurekind"));
            f.put("kind", kind);
            f.put("label", failureLabel(kind, asInt(r.get("failurehttpcode"))));
            f.put("at", r.get("failureat"));
            f.put("detail", r.get("failuredetail"));
            f.put("httpCode", r.get("failurehttpcode"));
            f.put("channel", r.get("failurechannel"));
            f.put("network", r.get("failurenetwork"));
            f.put("networkLabel", networkLabel(str(r.get("failurenetwork")), str(r.get("failuressid"))));
            f.put("ssid", r.get("failuressid"));
            f.put("host", r.get("failurehost"));
            f.put("certIssuer", r.get("failureissuer"));
            f.put("count", r.get("failurecount"));
            out.put("lastFailure", f);
        }
        if (r.get("stopreason") != null) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("reason", r.get("stopreason"));
            s.put("label", stopLabel(str(r.get("stopreason"))));
            s.put("at", r.get("stopat"));
            s.put("resumedAt", r.get("stopresumedat"));
            s.put("battery", r.get("stopbattery"));
            out.put("lastStop", s);
        }
        return out;
    }

    /**
     * Backup server URLs from configuration ({@code agent.backup.urls}, comma or space separated): only plain https
     * origins are handed to agents. An unresolved placeholder (<code>${…}</code>) counts as not configured.
     */
    public static java.util.List<String> backupUrls(String configured) {
        return backupUrls(configured, false);
    }

    /** @param allowHttp only for a development server that itself runs on plain http (agents in release builds refuse http anyway). */
    public static java.util.List<String> backupUrls(String configured, boolean allowHttp) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (configured == null || configured.contains("${")) return out;
        for (String raw : configured.split("[,\\s]+")) {
            String u = raw.trim();
            if (u.isEmpty()) continue;
            try {
                java.net.URI uri = new java.net.URI(u);
                boolean https = "https".equalsIgnoreCase(uri.getScheme());
                boolean plain = (https || (allowHttp && "http".equalsIgnoreCase(uri.getScheme()))) && uri.getHost() != null && uri.getUserInfo() == null
                        && (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                        && uri.getQuery() == null && uri.getFragment() == null;
                if (!plain) continue;
                String clean = (https ? "https://" : "http://") + uri.getHost().toLowerCase()
                        + (uri.getPort() > 0 && !(https && uri.getPort() == 443) ? ":" + uri.getPort() : "");
                if (!out.contains(clean) && out.size() < 3) out.add(clean);
            } catch (Exception e) {
                // malformed: skipped
            }
        }
        return out;
    }

    private static boolean sane(Long ts, long now) {
        // Not in the future (beyond clock skew) and not older than a year.
        return ts != null && ts > now - 365L * 24 * 3600_000L && ts <= now + 10 * 60_000L;
    }

    private static String known(String v, Set<String> allowed, String fallback) {
        return v == null ? null : allowed.contains(v) ? v : fallback;
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("[\\p{Cntrl}]", " ").trim();
        return t.isEmpty() ? null : t.length() > max ? t.substring(0, max) : t;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long asLong(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : null;
    }

    private static Integer asInt(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : null;
    }

    private static Boolean asBool(Object o) {
        return o instanceof Boolean ? (Boolean) o : null;
    }
}
