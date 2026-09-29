package com.hmdm.util;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class DeviceSummaryTest {
    @Test
    public void network_quality_from_wifi_or_cellular() {
        Map<String, Object> row = new HashMap<>();
        row.put("number", "d1");
        row.put("telemetry", "{\"hardware\":{\"model\":\"moto g31\",\"osRelease\":\"11\",\"totalStorageBytes\":64000000000},"
                + "\"dynamic\":{\"networkType\":\"wifi\",\"wifiRssi\":-70,\"wifiSsid\":\"Amovil\",\"freeStorageBytes\":12000000000}}");
        Map<String, Object> s = DeviceSummary.of(row);
        assertEquals("moto g31", s.get("model"));
        assertEquals("11", s.get("androidVersion"));
        assertEquals(2, s.get("signalLevel"));
        assertEquals("Regular", s.get("signalLabel"));
        row.put("telemetry", "{\"dynamic\":{\"networkType\":\"cellular\",\"cellularSignalLevel\":4,\"cellularOperator\":\"Claro\"}}");
        s = DeviceSummary.of(row);
        assertEquals("Excelente", s.get("signalLabel"));
        assertEquals("Claro", s.get("operator"));
        row.put("telemetry", "not json");
        assertNull(DeviceSummary.of(row).get("signalLevel"));
    }

    @Test
    public void history_sample_from_checkin() throws Exception {
        com.fasterxml.jackson.databind.JsonNode tel = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"dynamic\":{\"networkType\":\"wifi\",\"wifiRssi\":-60,\"freeStorageBytes\":5,\"freeRamBytes\":7}}");
        Map<String, Object> m = DeviceSummary.metric(tel, 80, true, false, true);
        assertEquals(80, m.get("battery"));
        assertEquals(-60, m.get("wifiRssi"));
        assertEquals(3, m.get("signalLevel"));
        assertEquals(5L, m.get("freeStorageBytes"));
        assertEquals(true, m.get("locked"));
        Map<String, Object> none = DeviceSummary.metric(null, -1, null, null, null);
        assertNull(none.get("battery"));
        assertNull(none.get("networkType"));
        assertNull(none.get("signalLevel"));
    }
}
