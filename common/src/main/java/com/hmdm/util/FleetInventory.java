/*
 * DallyControl — fleet inventory (new module, not part of upstream Headwind).
 */
package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.util.ReportExport.Column;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The fleet inventory: one row per device built from its last report (battery, storage, Android, connection,
 * network, identifiers), defined ONCE as typed columns + rows. The Excel workbook ({@link DeviceExport}), the CSV,
 * the "Inventario de la flota" report and the scheduled e-mail all render these same rows, so a column never differs
 * between the screen and a file.
 *
 * <p>Input rows are {@code AgentDeviceMapper.listDeviceExportRows} maps (lower-case keys; {@code telemetry} is the
 * agent's census JSON). Agents that do not report a field (older releases) leave its cell empty.</p>
 */
public final class FleetInventory {
    /** Same as the console's "online" (web ui/status.ts ONLINE_WINDOW_MS). */
    public static final long ONLINE_WINDOW_MS = 20 * 60_000L;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] SIGNAL = {"Sin señal", "Mala", "Regular", "Buena", "Excelente"};
    private static final double GB = 1024.0 * 1024.0 * 1024.0;

    private FleetInventory() {}

    /** What "now", "online" and each alert mean for one run. */
    public static final class Options {
        public long now = System.currentTimeMillis();
        public long onlineWindowMs = ONLINE_WINDOW_MS;
        /** Devices whose live channel is open right now (online regardless of their last report); may be null. */
        public Set<String> live;
        /** Battery at or below this (and not charging) is "batería baja". */
        public int lowBatteryPct = 20;
        /** Storage used at or above this is "almacenamiento casi lleno". */
        public int storageUsedPct = 90;
        /** No report for more than this many days is "sin conexión prolongada". */
        public int offlineDays = 7;
        /** The agent version served by this server; null = compare against the newest one seen in the fleet. */
        public String latestAgent;
    }

    /** The inventory columns, in file order. Keys are the row-map keys. */
    public static List<Column> columns() {
        return Arrays.asList(
                col("name", "Nombre", "text"), col("number", "Número", "text"), col("group", "Carpeta", "text"),
                col("policy", "Política", "text"), col("status", "Estado", "text"),
                col("minutes", "Min. desde el último reporte", "number"),
                col("lastseen", "Última conexión", "datetime"), col("stateat", "Última muestra", "datetime"),
                col("connectedsince", "Conectado desde", "datetime"),
                col("model", "Modelo", "text"), col("manufacturer", "Fabricante", "text"),
                col("android", "Android", "text"), col("patch", "Parche de seguridad", "text"),
                col("osupdate", "Actualización de Android", "text"), col("agent", "Agente", "text"),
                col("serial", "Serie", "text"), col("imei", "IMEI", "text"), col("mac", "MAC Wi-Fi", "text"),
                col("phone", "Teléfono", "text"), col("sim", "SIM", "text"),
                col("expectedphone", "Celular esperado", "text"), col("simmatch", "SIM esperada", "text"),
                col("accountemail", "Correo", "text"),
                col("battery", "Batería %", "number"), col("charging", "Cargando", "text"),
                col("batteryhealth", "Salud batería", "text"), col("batterytemp", "Temperatura °C", "number"),
                col("freegb", "Libre GB", "number"), col("totalgb", "Total GB", "number"),
                col("usedpct", "% usado", "number"),
                col("network", "Red", "text"), col("signal", "Señal", "text"),
                col("netname", "SSID/Operador", "text"), col("ip", "IP", "text"),
                col("kiosk", "Quiosco", "text"), col("powermode", "Modo de conexión", "text"),
                col("location", "Ubicación", "text"), col("locationat", "Hora de la ubicación", "datetime"),
                col("enrolled", "Inscrito", "datetime"), col("alerts", "Alertas", "text"));
    }

    /**
     * One inventory row per device. {@code paths}: folder id → "Padre / Hija"; {@code openSince}: device number →
     * start of its open connection session (may be empty).
     */
    public static List<Map<String, Object>> rows(List<Map<String, Object>> devices, Map<Integer, String> paths,
                                                 Map<String, Long> openSince, Options o) {
        String latest = o.latestAgent != null && !o.latestAgent.trim().isEmpty() ? o.latestAgent.trim() : newest(devices);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : devices) out.add(row(d, paths, openSince, o, latest));
        return out;
    }

    private static Map<String, Object> row(Map<String, Object> d, Map<Integer, String> paths,
                                           Map<String, Long> openSince, Options o, String latest) {
        JsonNode tel = parse(str(d.get("telemetry")));
        JsonNode hw = tel.path("hardware"), idn = tel.path("identity"), dyn = tel.path("dynamic");
        Map<String, Object> r = new LinkedHashMap<>();
        String number = str(d.get("number"));
        r.put("id", d.get("id"));
        r.put("name", nonBlank(str(d.get("description")), number));
        r.put("number", number);
        Integer gid = intOf(d.get("groupid"));
        r.put("groupid", gid);
        r.put("group", gid == null ? "Sin carpeta" : paths.getOrDefault(gid, ""));
        r.put("policy", nonBlank(str(d.get("configurationname")), ""));

        long stateAt = lng(d.get("stateat"));
        long last = Math.max(lng(d.get("lastupdate")), stateAt);
        boolean online = (o.live != null && number != null && o.live.contains(number))
                || (last > 0 && o.now - last <= o.onlineWindowMs);
        r.put("online", online);
        r.put("status", online ? "En línea" : "Sin conexión");
        r.put("minutes", last > 0 ? (Object) Math.max(0L, (o.now - last) / 60_000L) : null);
        r.put("lastseen", last > 0 ? (Object) last : null);
        r.put("stateat", stateAt > 0 ? (Object) stateAt : null);
        Long since = openSince == null ? null : openSince.get(number);
        r.put("connectedsince", since != null && since > 0 ? since : null);

        r.put("model", hw.path("model").asText(""));
        r.put("manufacturer", hw.path("manufacturer").asText(""));
        r.put("android", nonBlank(hw.path("osRelease").asText(""), str(d.get("androidrelease"))));
        r.put("patch", hw.path("securityPatch").asText(""));
        boolean pending = dyn.hasNonNull("systemUpdatePendingSince");
        r.put("osupdatepending", pending);
        r.put("osupdate", tel.size() == 0 ? "" : pending ? "Pendiente" : "Al día");
        String agent = nonBlank(str(d.get("agentversion")), "");
        r.put("agent", agent);
        r.put("serial", idn.path("serial").asText(""));
        r.put("imei", join(idn.path("imei")));
        r.put("mac", idn.path("wifiMac").asText(""));
        r.put("phone", phones(dyn.path("sim"), idn.path("phoneNumber")));
        r.put("sim", sim(dyn.path("sim")));
        String expected = nonBlank(str(d.get("expectedphone")), "");
        r.put("expectedphone", expected);
        Object mismatch = d.get("simmismatch");
        r.put("simmatch", expected.isEmpty() ? "" : Boolean.TRUE.equals(mismatch) ? "No coincide"
                : Boolean.FALSE.equals(mismatch) ? "Coincide" : "No disponible");
        r.put("accountemail", nonBlank(str(d.get("accountemail")), str(d.get("groupaccountemail"))));

        Integer battery = intOf(d.get("battery"));
        if (battery == null && dyn.hasNonNull("batteryPct")) battery = dyn.get("batteryPct").asInt();
        if (battery != null && (battery < 0 || battery > 100)) battery = null;
        r.put("battery", battery);
        Object charging = d.get("charging");
        r.put("charging", yesNo(charging));
        r.put("batteryhealth", health(dyn.path("batteryHealth").asText("")));
        r.put("batterytemp", dyn.hasNonNull("batteryTempC") ? (Object) round1(dyn.get("batteryTempC").asDouble()) : null);

        Long free = dyn.hasNonNull("freeStorageBytes") ? dyn.get("freeStorageBytes").asLong() : null;
        Long total = hw.hasNonNull("totalStorageBytes") && hw.get("totalStorageBytes").asLong() > 0
                ? hw.get("totalStorageBytes").asLong() : null;
        r.put("freegb", free == null ? null : (Object) round1(free / GB));
        r.put("totalgb", total == null ? null : (Object) round1(total / GB));
        Integer usedPct = free == null || total == null ? null
                : (int) Math.max(0, Math.min(100, Math.round((total - free) * 100.0 / total)));
        r.put("usedpct", usedPct);

        String type = dyn.path("networkType").asText("");
        r.put("network", "wifi".equals(type) ? "Wi-Fi" : "cellular".equals(type) ? "Datos móviles"
                : "none".equals(type) ? "Sin red" : "");
        Integer level = null;
        if ("wifi".equals(type) && dyn.hasNonNull("wifiRssi")) level = DeviceSummary.wifiLevel(dyn.get("wifiRssi").asInt());
        else if ("cellular".equals(type) && dyn.hasNonNull("cellularSignalLevel")) {
            level = Math.max(0, Math.min(4, dyn.get("cellularSignalLevel").asInt()));
        }
        String signal = level == null ? "" : SIGNAL[level] + " (" + level + "/4)";
        if ("wifi".equals(type) && dyn.hasNonNull("wifiRssi")) signal += " " + dyn.get("wifiRssi").asInt() + " dBm";
        r.put("signal", signal);
        r.put("netname", "wifi".equals(type) ? cleanSsid(dyn.path("wifiSsid").asText(""))
                : "cellular".equals(type) ? dyn.path("cellularOperator").asText("") : "");
        r.put("ip", dyn.path("ipAddress").asText(""));

        r.put("kiosk", yesNo(d.get("kioskactive")));
        String pm = str(d.get("powermode"));
        r.put("powermode", "alwaysOn".equals(pm) ? "Siempre conectado" : "adaptive".equals(pm) ? "Ahorro de batería" : "");
        JsonNode loc = dyn.path("location");
        if (loc.hasNonNull("lat") && loc.hasNonNull("lon")) {
            r.put("location", String.format(Locale.ROOT, "%.6f,%.6f", loc.get("lat").asDouble(), loc.get("lon").asDouble()));
            long at = loc.path("capturedAt").asLong(0);
            r.put("locationat", at > 0 ? (Object) at : null);
        } else {
            r.put("location", "");
            r.put("locationat", null);
        }
        long enrolled = lng(d.get("enrolltime"));
        r.put("enrolled", enrolled > 0 ? (Object) enrolled : null);

        // Alerts (also flags, so the console can filter without re-deriving the rules).
        boolean lowBattery = battery != null && battery <= o.lowBatteryPct && !Boolean.TRUE.equals(charging);
        boolean storageFull = usedPct != null && usedPct >= o.storageUsedPct;
        boolean offlineLong = !online && (last <= 0 || o.now - last > o.offlineDays * 86_400_000L);
        boolean agentOutdated = !agent.isEmpty() && latest != null && compareVersions(agent, latest) < 0;
        r.put("lowbattery", lowBattery);
        r.put("storagefull", storageFull);
        r.put("offlinelong", offlineLong);
        r.put("agentoutdated", agentOutdated);
        List<String> alerts = new ArrayList<>();
        if (lowBattery) alerts.add("Batería baja");
        if (storageFull) alerts.add("Almacenamiento casi lleno");
        if (offlineLong) alerts.add(last <= 0 ? "Nunca ha reportado" : "Sin conexión > " + o.offlineDays + " d");
        if (agentOutdated) alerts.add("Agente desactualizado");
        if (pending) alerts.add("Actualización de Android pendiente");
        r.put("alerts", String.join("; ", alerts));
        return r;
    }

    /**
     * The fleet summary over inventory rows: totals, the alert counts and the breakdowns by Android version, model
     * and agent version (each a list of {label, count}, biggest first).
     */
    public static Map<String, Object> summary(List<Map<String, Object>> rows, Options o) {
        int online = 0, low = 0, full = 0, off = 0, outdated = 0, never = 0, pending = 0;
        Map<String, Integer> android = new TreeMap<>(), model = new TreeMap<>(), agent = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            if (Boolean.TRUE.equals(r.get("online"))) online++;
            if (Boolean.TRUE.equals(r.get("lowbattery"))) low++;
            if (Boolean.TRUE.equals(r.get("storagefull"))) full++;
            if (Boolean.TRUE.equals(r.get("offlinelong"))) off++;
            if (Boolean.TRUE.equals(r.get("agentoutdated"))) outdated++;
            if (Boolean.TRUE.equals(r.get("osupdatepending"))) pending++;
            if (r.get("lastseen") == null) never++;
            android.merge(nonBlank(str(r.get("android")), "Desconocido"), 1, Integer::sum);
            String m = (nonBlank(str(r.get("manufacturer")), "") + " " + nonBlank(str(r.get("model")), "")).trim();
            model.merge(m.isEmpty() ? "Desconocido" : m, 1, Integer::sum);
            agent.merge(nonBlank(str(r.get("agent")), "Desconocido"), 1, Integer::sum);
        }
        String latest = o.latestAgent;
        if (latest == null || latest.trim().isEmpty()) {
            for (Map<String, Object> r : rows) {
                String a = str(r.get("agent"));
                if (a != null && !a.isEmpty() && (latest == null || compareVersions(a, latest) > 0)) latest = a;
            }
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("total", rows.size());
        s.put("online", online);
        s.put("offline", rows.size() - online);
        s.put("onlineWindowMinutes", o.onlineWindowMs / 60_000L);
        s.put("lowBattery", low);
        s.put("lowBatteryPct", o.lowBatteryPct);
        s.put("storageAlmostFull", full);
        s.put("storageUsedPct", o.storageUsedPct);
        s.put("offlineLong", off);
        s.put("offlineDays", o.offlineDays);
        s.put("neverReported", never);
        s.put("outdatedAgent", outdated);
        s.put("latestAgent", latest);
        s.put("osUpdatePending", pending);
        s.put("byAndroid", breakdown(android, true));
        s.put("byModel", breakdown(model, false));
        s.put("byAgent", breakdown(agent, true));
        return s;
    }

    /** The summary's headline figures as label/value pairs (the workbook's Resumen and the e-mail body). */
    public static List<String[]> headline(Map<String, Object> s) {
        List<String[]> kv = new ArrayList<>();
        kv.add(new String[]{"Dispositivos", String.valueOf(s.get("total"))});
        kv.add(new String[]{"En línea (últimos " + s.get("onlineWindowMinutes") + " min)", String.valueOf(s.get("online"))});
        kv.add(new String[]{"Sin conexión", String.valueOf(s.get("offline"))});
        kv.add(new String[]{"Sin conexión > " + s.get("offlineDays") + " días", String.valueOf(s.get("offlineLong"))});
        kv.add(new String[]{"Batería baja (≤ " + s.get("lowBatteryPct") + " %, sin cargar)", String.valueOf(s.get("lowBattery"))});
        kv.add(new String[]{"Almacenamiento casi lleno (≥ " + s.get("storageUsedPct") + " % usado)", String.valueOf(s.get("storageAlmostFull"))});
        kv.add(new String[]{"Agente desactualizado" + (s.get("latestAgent") == null ? "" : " (última: " + s.get("latestAgent") + ")"),
                String.valueOf(s.get("outdatedAgent"))});
        kv.add(new String[]{"Actualización de Android pendiente", String.valueOf(s.get("osUpdatePending"))});
        return kv;
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private static List<Map<String, Object>> breakdown(Map<String, Integer> counts, boolean versionOrder) {
        List<Map.Entry<String, Integer>> e = new ArrayList<>(counts.entrySet());
        e.sort((a, b) -> {
            int c = Integer.compare(b.getValue(), a.getValue());
            if (c != 0) return c;
            return versionOrder ? compareVersions(b.getKey(), a.getKey()) : a.getKey().compareToIgnoreCase(b.getKey());
        });
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Integer> x : e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", x.getKey());
            m.put("count", x.getValue());
            out.add(m);
        }
        return out;
    }

    private static String newest(List<Map<String, Object>> devices) {
        String best = null;
        for (Map<String, Object> d : devices) {
            String a = str(d.get("agentversion"));
            if (a != null && !a.trim().isEmpty() && (best == null || compareVersions(a.trim(), best) > 0)) best = a.trim();
        }
        return best;
    }

    /** Dotted numeric comparison ("0.9.8" &lt; "0.10.0"); a non-numeric part compares as text. */
    public static int compareVersions(String a, String b) {
        String[] x = (a == null ? "" : a).split("[.\\-+]"), y = (b == null ? "" : b).split("[.\\-+]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0", q = i < y.length ? y[i] : "0";
            int c;
            try {
                c = Long.compare(Long.parseLong(p), Long.parseLong(q));
            } catch (NumberFormatException e) {
                c = p.compareToIgnoreCase(q);
            }
            if (c != 0) return c;
        }
        return 0;
    }

    private static String health(String h) {
        switch (h) {
            case "good": return "Buena";
            case "overheat": return "Sobrecalentada";
            case "dead": return "Agotada";
            case "overVoltage": return "Sobrevoltaje";
            case "cold": return "Fría";
            case "unknown": return "Desconocida";
            default: return h;
        }
    }

    /** Android reports the SSID quoted, and "&lt;unknown ssid&gt;" without the location permission. */
    private static String cleanSsid(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1);
        return t.startsWith("<unknown") ? "" : t;
    }

    private static Column col(String key, String label, String type) {
        return new Column(key, label, type);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static JsonNode parse(String json) {
        try {
            return json == null ? JSON.createObjectNode() : JSON.readTree(json);
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    private static String join(JsonNode arr) {
        if (!arr.isArray()) return arr.asText("");
        List<String> v = new ArrayList<>();
        for (JsonNode n : arr) v.add(n.asText());
        return String.join(", ", v);
    }

    private static String phones(JsonNode sim, JsonNode identityPhones) {
        List<String> v = new ArrayList<>();
        for (JsonNode s : sim.path("slots")) if (s.hasNonNull("number")) v.add(s.get("number").asText());
        if (v.isEmpty()) for (JsonNode n : identityPhones) v.add(n.asText());
        return String.join(", ", v);
    }

    private static String sim(JsonNode sim) {
        String state = sim.path("state").asText("");
        if (state.isEmpty()) return "";
        if ("absent".equals(state)) return "Sin SIM";
        if ("locked".equals(state)) return "Bloqueada (PIN/PUK)";
        List<String> v = new ArrayList<>();
        for (JsonNode s : sim.path("slots")) if (s.hasNonNull("carrier")) v.add(s.get("carrier").asText());
        return v.isEmpty() ? ("ready".equals(state) ? "Lista" : state) : String.join(", ", v);
    }

    private static String yesNo(Object o) {
        return o instanceof Boolean ? ((Boolean) o ? "Sí" : "No") : "";
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nonBlank(String a, String b) {
        return a != null && !a.trim().isEmpty() ? a : b == null ? "" : b;
    }

    private static long lng(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    private static Integer intOf(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : null;
    }

    /** Unmodifiable empty map, for callers without connection history. */
    public static Map<String, Long> noSessions() {
        return Collections.emptyMap();
    }
}
