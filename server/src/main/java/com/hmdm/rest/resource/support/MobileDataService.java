package com.hmdm.rest.resource.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.MobileDataDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.DeviceState;
import com.hmdm.rest.json.agent.DesiredMobileData;
import com.hmdm.util.DcPolicy;
import com.hmdm.util.MobileDataPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DallyControl — mobile data per app, server side: a device's override on top of its policy, and "use against
 * limit" for the console and the export.
 *
 * <p>The phone enforces the lists and the limits by itself (it keeps working without connection) and reports what it
 * is doing in its state ({@code telemetry.dynamic.mobileData}). Consumption shown here is the phone's own figure for
 * the period when it reported one for the current period; otherwise it is added up from the per-day usage table
 * (agents that only measure). The period is computed in Colombia's time (the fleet's), the phone's in its own zone.</p>
 */
@Singleton
public class MobileDataService {
    private static final Logger logger = LoggerFactory.getLogger(MobileDataService.class);
    private static final ZoneId ZONE = ZoneId.of("America/Bogota");
    private static final ObjectMapper JSON = new ObjectMapper();
    /** The phone's period start may differ from the server's by its time zone; beyond this it is another period. */
    private static final long SAME_PERIOD_MS = 26L * 3600_000L;

    /** For classes that are not built with it (same pattern as GeofenceService.INSTANCE). */
    public static volatile MobileDataService INSTANCE;

    private final MobileDataDAO dao;
    private final UnsecureDAO unsecureDAO;
    private final AgentCommandDAO commandDAO;

    @Inject
    public MobileDataService(MobileDataDAO dao, UnsecureDAO unsecureDAO, AgentCommandDAO commandDAO) {
        this.dao = dao;
        this.unsecureDAO = unsecureDAO;
        this.commandDAO = commandDAO;
        INSTANCE = this;
    }

    /** The device's own override, or null. Never fails a check-in. */
    public MobileDataPolicy.DeviceOverride override(Integer deviceId) {
        if (deviceId == null) return null;
        try {
            return MobileDataPolicy.parseOverride(dao.getOverride(deviceId));
        } catch (Exception e) {
            logger.warn("Mobile-data override of device {} not read: {}", deviceId, e.getMessage());
            return null;
        }
    }

    /** Store the override (null or empty = follow the policy). @return what was stored. */
    public MobileDataPolicy.DeviceOverride saveOverride(int deviceId, MobileDataPolicy.DeviceOverride override) {
        String json = MobileDataPolicy.normalizeOverride(override);
        dao.saveOverride(deviceId, json);
        return MobileDataPolicy.parseOverride(json);
    }

    /** What the device must enforce: its policy's section with its override on top (null = nothing restricted). */
    public DesiredMobileData effective(Configuration cfg, Integer deviceId) {
        DcPolicy.MobileData policy = cfg == null ? null : DcPolicy.parse(cfg.getDcPolicy()).getMobileData();
        return MobileDataPolicy.desired(policy, override(deviceId));
    }

    // --- device card ----------------------------------------------------------------------------------------

    /** Everything the device page shows: policy, override, what the phone enforces and use against limits. */
    public Map<String, Object> view(Device device) {
        Configuration cfg = device.getConfigurationId() == null ? null : unsecureDAO.getConfigurationById(device.getConfigurationId());
        DcPolicy.MobileData policy = cfg == null ? null : DcPolicy.parse(cfg.getDcPolicy()).getMobileData();
        MobileDataPolicy.DeviceOverride override = override(device.getId());
        DesiredMobileData effective = MobileDataPolicy.desired(policy, override);
        DeviceState state = commandDAO.getState(device.getNumber());
        JsonNode status = status(state == null ? null : state.getTelemetry());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configurationId", cfg == null ? null : cfg.getId());
        out.put("configurationName", cfg == null ? null : cfg.getName());
        out.put("policy", policy);
        out.put("override", override);
        out.put("effective", effective);
        out.put("status", status);
        out.put("statusAt", state == null ? null : state.getUpdatedAt());
        out.put("agentVersion", state == null ? null : state.getAgentVersion());
        Usage usage = usage(device.getNumber(), effective, status, null);
        out.put("period", usage.periodMap());
        out.put("source", usage.fromAgent ? "agent" : "server");
        out.put("deviceBytes", usage.deviceBytes);
        out.put("rows", MobileDataPolicy.usageRows(effective, usage.bytes, usage.labels, usage.deviceBytes, true));
        out.put("apps", usage.top(15));
        return out;
    }

    // --- fleet report ---------------------------------------------------------------------------------------

    /**
     * One row per managed device (its whole consumption against the device limit) and one per app with a limit.
     * Rows carry {@code number} / {@code description} / {@code groupid} so the caller can scope and name them.
     */
    public List<Map<String, Object>> fleetRows(int customerId) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Map<String, Map<String, Object[]>>> byConfig = new HashMap<>();
        for (Map<String, Object> d : dao.managedDevices(customerId)) {
            DcPolicy.MobileData policy = DcPolicy.parse(str(d.get("dcpolicy"))).getMobileData();
            DesiredMobileData effective = MobileDataPolicy.desired(policy, MobileDataPolicy.parseOverride(str(d.get("override"))));
            String number = str(d.get("number"));
            JsonNode status = status(wrap(str(d.get("status"))));
            if (effective == null && status == null) continue; // an override that lifts everything: nothing to compare
            LocalDate start = MobileDataPolicy.periodStart(LocalDate.now(ZONE), effective == null ? null : effective.getPeriod(),
                    effective == null ? null : effective.getCutDay());
            Object cfgId = d.get("configurationid");
            Map<String, Object[]> server = null;
            if (cfgId instanceof Number) {
                String key = cfgId + "|" + start;
                Map<String, Map<String, Object[]>> perDevice = byConfig.get(key);
                if (perDevice == null) {
                    perDevice = new HashMap<>();
                    for (Map<String, Object> u : dao.usageOfConfiguration(customerId, ((Number) cfgId).intValue(), start.toString())) {
                        perDevice.computeIfAbsent(str(u.get("number")), k -> new HashMap<>())
                                .put(str(u.get("pkg")), new Object[]{longOf(u.get("bytes")), str(u.get("label"))});
                    }
                    byConfig.put(key, perDevice);
                }
                server = perDevice.get(number);
                if (server == null) server = new HashMap<>();
            }
            Usage usage = usage(number, effective, status, server);
            int blocked = status == null ? 0 : status.path("blockedCount").asInt(status.path("blocked").size());
            String mechanism = status == null ? "" : mechanismLabel(status.path("mechanism").asText(""));
            for (Map<String, Object> r : MobileDataPolicy.usageRows(effective, usage.bytes, usage.labels, usage.deviceBytes, true)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", d.get("id"));
                row.put("number", number);
                row.put("description", d.get("description"));
                row.put("groupid", d.get("groupid"));
                row.put("configurationname", d.get("configurationname"));
                row.put("ambito", "device".equals(r.get("scope")) ? "Equipo" : "App");
                row.put("app", r.get("label"));
                row.put("pkg", r.get("pkg"));
                row.put("usedbytes", r.get("usedbytes"));
                row.put("limitbytes", r.get("limitbytes"));
                row.put("percent", r.get("percent"));
                row.put("estado", r.get("estado"));
                row.put("level", r.get("level"));
                row.put("blocked", "device".equals(r.get("scope")) ? (Object) blocked : null);
                row.put("mechanism", mechanism);
                row.put("periodstart", usage.start.toString());
                row.put("source", usage.fromAgent ? "Equipo" : "Servidor");
                out.add(row);
            }
        }
        return out;
    }

    public static String mechanismLabel(String mechanism) {
        switch (mechanism == null ? "" : mechanism) {
            case "dpm": return "Restricción del sistema";
            case "vpn": return "VPN local del agente";
            case "none": return "Sin restricción activa";
            default: return "";
        }
    }

    // --- consumption of the period --------------------------------------------------------------------------

    private static final class Usage {
        LocalDate start;
        LocalDate end;
        String period;
        boolean fromAgent;
        long deviceBytes;
        final Map<String, Long> bytes = new HashMap<>();
        final Map<String, String> labels = new HashMap<>();

        Map<String, Object> periodMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", period);
            m.put("start", start.toString());
            m.put("end", end.toString());
            return m;
        }

        List<Map<String, Object>> top(int n) {
            List<Map.Entry<String, Long>> sorted = new ArrayList<>(bytes.entrySet());
            sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map.Entry<String, Long> e : sorted) {
                if (out.size() >= n || e.getValue() <= 0) break;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("pkg", e.getKey());
                m.put("label", labels.get(e.getKey()));
                m.put("bytes", e.getValue());
                out.add(m);
            }
            return out;
        }
    }

    /**
     * @param server per-package {bytes, label} already read from the usage table for this period, or null to read it here
     */
    private Usage usage(String number, DesiredMobileData effective, JsonNode status, Map<String, Object[]> server) {
        Usage u = new Usage();
        String period = effective == null ? null : effective.getPeriod();
        Integer cutDay = effective == null ? null : effective.getCutDay();
        LocalDate today = LocalDate.now(ZONE);
        u.period = "daily".equals(period) ? "daily" : "monthly";
        u.start = MobileDataPolicy.periodStart(today, period, cutDay);
        u.end = MobileDataPolicy.periodEnd(today, period, cutDay);
        long startMs = u.start.atStartOfDay(ZONE).toInstant().toEpochMilli();
        boolean agent = status != null && status.path("periodStart").asLong(0) > 0
                && Math.abs(status.path("periodStart").asLong() - startMs) <= SAME_PERIOD_MS
                && u.period.equals(status.path("period").asText("monthly"));
        if (server == null) {
            server = new HashMap<>();
            for (Map<String, Object> r : dao.usageByApp(number, u.start.toString())) {
                server.put(str(r.get("pkg")), new Object[]{longOf(r.get("bytes")), str(r.get("label"))});
            }
        }
        long sum = 0;
        for (Map.Entry<String, Object[]> e : server.entrySet()) {
            long n = (Long) e.getValue()[0];
            u.bytes.put(e.getKey(), n);
            if (e.getValue()[1] != null) u.labels.put(e.getKey(), (String) e.getValue()[1]);
            sum += n;
        }
        u.deviceBytes = sum;
        if (agent) {
            // The phone read the system's counters for exactly this period: its figures win over the daily sums.
            u.fromAgent = true;
            u.deviceBytes = status.path("deviceBytes").asLong(sum);
            for (JsonNode a : status.path("apps")) {
                String pkg = a.path("pkg").asText("");
                if (pkg.isEmpty()) continue;
                u.bytes.put(pkg, a.path("bytes").asLong(0));
                if (a.path("label").isTextual()) u.labels.put(pkg, a.path("label").asText());
            }
        }
        return u;
    }

    /** The agent's mobile-data state out of a telemetry JSON (or out of {@code {"dynamic":{"mobileData":…}}}). */
    static JsonNode status(String telemetry) {
        if (telemetry == null || telemetry.isEmpty()) return null;
        try {
            JsonNode s = JSON.readTree(telemetry).path("dynamic").path("mobileData");
            return s.isObject() ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String wrap(String statusJson) {
        return statusJson == null || statusJson.isEmpty() ? null : "{\"dynamic\":{\"mobileData\":" + statusJson + "}}";
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static long longOf(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }
}
