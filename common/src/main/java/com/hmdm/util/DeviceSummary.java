package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A device's list summary from its last census: model, Android, agent, battery, network (type, signal quality 0-4 with
 * a label), carrier, free storage, kiosk, connection mode.
 */
public final class DeviceSummary {
    private static final ObjectMapper JSON = new ObjectMapper();

    private DeviceSummary() {}

    public static Map<String, Object> of(Map<String, Object> row) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("number", row.get("number"));
        JsonNode tel;
        try {
            tel = row.get("telemetry") == null ? JSON.createObjectNode() : JSON.readTree(String.valueOf(row.get("telemetry")));
        } catch (Exception e) {
            tel = JSON.createObjectNode();
        }
        JsonNode hw = tel.path("hardware"), dyn = tel.path("dynamic");
        s.put("manufacturer", text(hw, "manufacturer"));
        s.put("model", text(hw, "model"));
        s.put("androidVersion", text(hw, "osRelease") != null ? text(hw, "osRelease") : row.get("androidrelease"));
        s.put("agentVersion", row.get("agentversion"));
        s.put("battery", row.get("battery"));
        s.put("charging", row.get("charging"));
        s.put("kioskActive", row.get("kioskactive"));
        s.put("powerMode", row.get("powermode"));
        String type = text(dyn, "networkType");
        s.put("networkType", type);
        Integer level = null;
        if ("wifi".equals(type) && dyn.hasNonNull("wifiRssi")) level = wifiLevel(dyn.get("wifiRssi").asInt());
        else if ("cellular".equals(type) && dyn.hasNonNull("cellularSignalLevel")) level = Math.max(0, Math.min(4, dyn.get("cellularSignalLevel").asInt()));
        s.put("signalLevel", level);
        s.put("signalLabel", level == null ? null : new String[]{"Sin señal", "Mala", "Regular", "Buena", "Excelente"}[level]);
        s.put("wifiSsid", text(dyn, "wifiSsid"));
        s.put("operator", text(dyn, "cellularOperator"));
        s.put("freeStorageBytes", dyn.hasNonNull("freeStorageBytes") ? dyn.get("freeStorageBytes").asLong() : null);
        s.put("totalStorageBytes", hw.hasNonNull("totalStorageBytes") ? hw.get("totalStorageBytes").asLong() : null);
        s.put("stateAt", row.get("stateat"));
        return s;
    }

    /** Android's own 5-step scale (WifiManager.calculateSignalLevel with 5 levels). */
    public static int wifiLevel(int rssi) {
        if (rssi >= -55) return 4;
        if (rssi >= -66) return 3;
        if (rssi >= -77) return 2;
        if (rssi >= -88) return 1;
        return 0;
    }

    private static String text(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asText() : null;
    }
}
