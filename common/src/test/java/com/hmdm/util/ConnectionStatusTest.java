package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.domain.DeviceConnection;
import com.hmdm.rest.json.agent.AgentCheckInRequest;
import com.hmdm.rest.json.agent.AgentCheckInResponse;
import com.hmdm.rest.json.agent.AgentConnectionReport;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class ConnectionStatusTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MIN = 60_000L, HOUR = 3600_000L;
    private final long now = 1_800_000_000_000L;

    private Map<String, Object> classify(long silence, Integer battery, Boolean charging, Boolean screenOn,
                                         String stop, Long stopAt, Long resumedAt) {
        return ConnectionStatus.classify(now, now - silence, battery, charging, screenOn, stop, stopAt, resumedAt);
    }

    @Test
    public void online_within_the_window_whatever_else() {
        assertEquals("online", classify(19 * MIN, 2, false, false, null, null, null).get("state"));
    }

    @Test
    public void silent_with_the_screen_off_is_resting() {
        Map<String, Object> s = classify(45 * MIN, 80, false, false, null, null, null);
        assertEquals("resting", s.get("state"));
        assertEquals("screenOff", s.get("reason"));
        assertEquals("Pantalla apagada", s.get("label"));
    }

    @Test
    public void silent_while_in_use_is_offline() {
        assertEquals("offline", classify(45 * MIN, 80, false, true, null, null, null).get("state"));
        // Older agent / no census: nothing explains the silence.
        assertEquals("offline", classify(45 * MIN, null, null, null, null, null, null).get("state"));
        assertEquals("offline", ConnectionStatus.classify(now, null, null, null, null, null, null, null).get("state"));
    }

    @Test
    public void low_battery_explains_a_silence_unless_it_was_charging() {
        assertEquals("lowBattery", classify(2 * HOUR, 3, false, true, null, null, null).get("reason"));
        assertEquals("offline", classify(2 * HOUR, 3, true, true, null, null, null).get("state"));
    }

    @Test
    public void resting_does_not_last_forever() {
        assertEquals("resting", classify(15 * HOUR, 80, false, false, null, null, null).get("state"));
        assertEquals("offline", classify(17 * HOUR, 80, false, false, null, null, null).get("state"));
    }

    @Test
    public void an_orderly_shutdown_is_resting_for_a_weekend() {
        long silence = 40 * HOUR;
        Map<String, Object> s = classify(silence, 60, false, true, "shutdown", now - silence - 1000, null);
        assertEquals("resting", s.get("state"));
        assertEquals("Equipo apagado", s.get("label"));
        assertEquals("offline", classify(80 * HOUR, 60, false, true, "shutdown", now - 80 * HOUR, null).get("state"));
        // An old, already closed shutdown explains nothing about today's silence.
        assertEquals("offline", classify(HOUR, 60, false, true, "shutdown", now - 50 * HOUR, now - 40 * HOUR).get("state"));
        assertEquals("offline", classify(HOUR, 60, false, true, "shutdown", now - 50 * HOUR, null).get("state"));
    }

    @Test
    public void report_is_read_into_a_row_and_untrusted_values_are_dropped() throws Exception {
        AgentCheckInRequest req = JSON.readValue("{\"deviceId\":\"d1\",\"connection\":{\"reconnects\":4,\"serverHost\":\"mdm.example.com\","
                + "\"usingBackup\":false,\"futureField\":1,"
                + "\"lastFailure\":{\"kind\":\"tls\",\"at\":" + (now - 5 * MIN) + ",\"detail\":\"SSLHandshakeException: x\",\"channel\":\"checkin\","
                + "\"network\":\"wifi\",\"ssid\":\"Sede72\",\"host\":\"mdm.example.com\",\"certIssuer\":\"CN=FortiGate CA\",\"count\":7},"
                + "\"lastStop\":{\"reason\":\"networkLost\",\"at\":" + (now - 50 * MIN) + ",\"resumedAt\":" + now + "}}}", AgentCheckInRequest.class);
        JsonNode tel = JSON.readTree("{\"dynamic\":{\"screenOn\":false,\"networkType\":\"wifi\"}}");
        DeviceConnection row = ConnectionStatus.toRow("d1", now, req.getConnection(), tel);
        assertEquals("tls", row.getFailureKind());
        assertEquals("Sede72", row.getFailureSsid());
        assertEquals("CN=FortiGate CA", row.getFailureIssuer());
        assertEquals(Integer.valueOf(7), row.getFailureCount());
        assertEquals(Integer.valueOf(4), row.getReconnects());
        assertEquals("networkLost", row.getStopReason());
        assertEquals(Boolean.FALSE, row.getScreenOn());
        assertEquals("wifi", row.getNetwork());
        assertTrue(ConnectionStatus.failureEventDetail(row).contains("inspección TLS"));
        assertTrue(ConnectionStatus.failureEventDetail(row).contains("Wi‑Fi «Sede72»"));
        assertTrue(ConnectionStatus.failureEventDetail(row).contains("CN=FortiGate CA"));
        assertEquals("El equipo se quedó sin red · 50 min", ConnectionStatus.gapEventDetail(row));

        AgentConnectionReport bad = JSON.readValue("{\"reconnects\":-5,\"lastFailure\":{\"kind\":\"<script>\",\"at\":1},"
                + "\"lastStop\":{\"reason\":\"shutdown\",\"at\":" + (now + 30 * HOUR) + "}}", AgentConnectionReport.class);
        DeviceConnection none = ConnectionStatus.toRow("d1", now, bad, null);
        assertNull(none.getFailureKind());
        assertNull(none.getStopReason());
        assertEquals(Integer.valueOf(0), none.getReconnects());
    }

    @Test
    public void an_older_agent_without_the_block_still_gives_screen_and_network() throws Exception {
        AgentCheckInRequest old = JSON.readValue("{\"deviceId\":\"d1\"}", AgentCheckInRequest.class);
        assertNull(old.getConnection());
        DeviceConnection row = ConnectionStatus.toRow("d1", now, null, JSON.readTree("{\"dynamic\":{\"screenOn\":true,\"networkType\":\"cellular\"}}"));
        assertEquals(Boolean.TRUE, row.getScreenOn());
        assertEquals("cellular", row.getNetwork());
        assertNull(row.getFailureKind());
    }

    @Test
    public void response_omits_backup_urls_unless_configured() throws Exception {
        assertFalse(JSON.writeValueAsString(new AgentCheckInResponse(Collections.emptyList())).contains("backupUrls"));
        AgentCheckInResponse r = new AgentCheckInResponse(Collections.emptyList());
        r.setBackupUrls(Arrays.asList("https://direct.example.com"));
        assertTrue(JSON.writeValueAsString(r).contains("\"backupUrls\":[\"https://direct.example.com\"]"));
    }

    @Test
    public void only_plain_https_backup_urls_are_handed_out() {
        assertEquals(Arrays.asList("https://direct.example.com", "https://b.example.com:8443"),
                ConnectionStatus.backupUrls("https://direct.example.com/, http://plain.example.com https://u:p@x.example.com,"
                        + "https://b.example.com:8443, https://c.example.com/path, nonsense, https://direct.example.com"));
        assertTrue(ConnectionStatus.backupUrls("${agent.backup.urls}").isEmpty());
        assertTrue(ConnectionStatus.backupUrls(null).isEmpty());
        assertTrue(ConnectionStatus.backupUrls("").isEmpty());
    }

    @Test
    public void view_for_the_console() {
        Map<String, Object> r = new HashMap<>();
        r.put("number", "d1");
        r.put("lastupdate", now - 30 * MIN);
        r.put("battery", 50);
        r.put("charging", false);
        r.put("screenon", false);
        r.put("failurekind", "wsUpgrade");
        r.put("failurehttpcode", 403);
        r.put("failurenetwork", "wifi");
        r.put("failuressid", "Sede72");
        r.put("failureat", now - 40 * MIN);
        Map<String, Object> v = ConnectionStatus.view(r, now);
        assertEquals("resting", v.get("state"));
        @SuppressWarnings("unchecked") Map<String, Object> f = (Map<String, Object>) v.get("lastFailure");
        assertEquals("WebSocket rechazado (HTTP 403)", f.get("label"));
        assertEquals("Wi‑Fi «Sede72»", f.get("networkLabel"));
        assertNull(v.get("lastStop"));
    }
}
