package com.hmdm.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.domain.DeviceConnection;
import com.hmdm.persistence.mapper.DeviceConnectionMapper;
import com.hmdm.rest.json.agent.AgentCheckInRequest;
import org.apache.ibatis.annotations.Insert;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * A follow-up ("ack") round and the goodbye report are check-ins WITHOUT a census (no telemetry). They must not
 * erase what the last census said about the screen and the network — "En reposo" is decided from it.
 */
public class CensusLessCheckInTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void a_check_in_without_census_carries_no_screen_or_network_but_still_its_diagnostics() throws Exception {
        long now = 1_800_000_000_000L;
        AgentCheckInRequest ack = JSON.readValue("{\"deviceId\":\"d1\",\"results\":[{\"commandId\":\"7\",\"status\":\"done\"}],"
                + "\"connection\":{\"reconnects\":2,\"lastFailure\":{\"kind\":\"timeout\",\"at\":" + (now - 1000) + "}}}",
                AgentCheckInRequest.class);
        assertNull(ack.getTelemetry());
        DeviceConnection row = ConnectionStatus.toRow("d1", now, ack.getConnection(), ack.getTelemetry());
        // Null means "not said in this report": the upsert keeps the stored value (next test).
        assertNull(row.getScreenOn());
        assertNull(row.getNetwork());
        // The diagnostics block still arrives on an ack round.
        assertEquals("timeout", row.getFailureKind());
        assertEquals(Integer.valueOf(2), row.getReconnects());
    }

    @Test
    public void the_upsert_keeps_the_last_known_screen_and_network_when_a_report_says_nothing() throws Exception {
        String sql = String.join("", DeviceConnectionMapper.class
                .getMethod("upsert", DeviceConnection.class).getAnnotation(Insert.class).value());
        assertTrue(sql.contains("screenOn = COALESCE(EXCLUDED.screenOn, device_conn_diag.screenOn)"));
        assertTrue(sql.contains("network = COALESCE(EXCLUDED.network, device_conn_diag.network)"));
        // ...and a report with nothing new never erases the explanation of an earlier failure or silence.
        assertTrue(sql.contains("failureKind = CASE WHEN EXCLUDED.failureKind IS NULL THEN device_conn_diag.failureKind"));
        assertTrue(sql.contains("stopReason = CASE WHEN EXCLUDED.stopReason IS NULL THEN device_conn_diag.stopReason"));
    }

    @Test
    public void resting_survives_an_ack_round_because_the_stored_screen_state_is_what_classifies() {
        long now = 1_800_000_000_000L;
        // Stored screenOn=false (kept by the upsert) + 45 min of silence = resting; had it been blanked: offline.
        assertEquals("resting", ConnectionStatus.classify(now, now - 45 * 60_000L, 80, false, Boolean.FALSE, null, null, null).get("state"));
        assertEquals("offline", ConnectionStatus.classify(now, now - 45 * 60_000L, 80, false, null, null, null, null).get("state"));
    }
}
