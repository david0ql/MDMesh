package com.hmdm.rest.resource.support;

import com.hmdm.persistence.domain.AgentEnrollAttempt;
import com.hmdm.persistence.domain.AgentEnrollmentToken;
import com.hmdm.rest.json.agent.AgentDeviceInfo;
import com.hmdm.rest.json.agent.AgentEnrollDiagnostics;
import com.hmdm.rest.json.agent.AgentEnrollRequest;
import com.hmdm.rest.json.agent.AgentInfo;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class EnrollAttemptLogTest {

    private static final long NOW = 1_800_000_000_000L;

    private static AgentEnrollRequest request() {
        AgentEnrollRequest r = new AgentEnrollRequest();
        r.setEnrollToken("tok");
        r.setHardwareId("hw-1");
        r.setSerial("ZY32FCBNM5");
        AgentDeviceInfo d = new AgentDeviceInfo();
        d.setManufacturer("samsung");
        d.setModel("SM-A125M");
        d.setAndroidRelease("11");
        d.setAndroidSdkInt(30);
        r.setDevice(d);
        AgentInfo a = new AgentInfo();
        a.setVersion("0.9.8");
        r.setAgent(a);
        return r;
    }

    private static AgentEnrollmentToken token() {
        AgentEnrollmentToken t = new AgentEnrollmentToken();
        t.setId(7);
        t.setCustomerId(1);
        t.setGroupId(3);
        return t;
    }

    @Test
    public void a_refused_enroll_says_why_and_which_phone() {
        List<AgentEnrollAttempt> rows = EnrollAttemptLog.build(request(), token(), "190.0.0.1", null,
                "error.agent.token.expired", null, NOW);
        assertEquals(1, rows.size());
        AgentEnrollAttempt a = rows.get(0);
        assertEquals("failed", a.getOutcome());
        assertEquals("token.expired", a.getReason());
        assertEquals("samsung", a.getManufacturer());
        assertEquals("SM-A125M", a.getModel());
        assertEquals("11", a.getAndroidRelease());
        assertEquals(Integer.valueOf(30), a.getSdkInt());
        assertEquals("0.9.8", a.getAgentVersion());
        assertEquals("190.0.0.1", a.getIp());
        assertEquals(Integer.valueOf(1), a.getCustomerId());
        assertEquals(Integer.valueOf(7), a.getTokenId());
        assertEquals(Integer.valueOf(3), a.getGroupId());
    }

    @Test
    public void an_unknown_token_is_logged_without_a_customer() {
        AgentEnrollAttempt a = EnrollAttemptLog.build(request(), null, null, null, "error.agent.token.invalid", null, NOW).get(0);
        assertEquals("token.invalid", a.getReason());
        assertNull(a.getCustomerId());
        assertNull(a.getTokenId());
    }

    @Test
    public void a_successful_enroll_names_the_device_and_the_path() {
        AgentEnrollRequest r = request();
        AgentEnrollDiagnostics d = new AgentEnrollDiagnostics();
        d.setPath("qr-legacy");
        d.setAttempts(3);
        r.setDiagnostics(d);
        List<AgentEnrollAttempt> rows = EnrollAttemptLog.build(r, token(), null, "dev-1", null, null, NOW);
        assertEquals(1, rows.size());
        assertEquals("ok", rows.get(0).getOutcome());
        assertEquals("enrolled", rows.get(0).getReason());
        assertEquals("dev-1", rows.get(0).getDeviceNumber());
        assertEquals("qr-legacy", rows.get(0).getPath());
        assertEquals(Integer.valueOf(3), rows.get(0).getTries());
    }

    @Test
    public void the_failure_the_phone_had_before_reaching_the_server_is_noted_first() {
        AgentEnrollRequest r = request();
        AgentEnrollDiagnostics d = new AgentEnrollDiagnostics();
        d.setPath("qr-modern");
        d.setAttempts(4);
        d.setLastError("clock");
        d.setLastErrorDetail("CertificateNotYetValidException");
        d.setLastErrorAt(NOW - 60_000);
        r.setDiagnostics(d);
        List<AgentEnrollAttempt> rows = EnrollAttemptLog.build(r, token(), null, "dev-1", null, null, NOW);
        assertEquals(2, rows.size());
        assertEquals("note", rows.get(0).getOutcome());
        assertEquals("device.clock", rows.get(0).getReason());
        assertEquals("CertificateNotYetValidException", rows.get(0).getDetail());
        assertEquals(NOW - 60_000, rows.get(0).getAt());
        assertEquals("ok", rows.get(1).getOutcome());
    }

    @Test
    public void a_note_dated_by_a_wrong_phone_clock_is_dated_now() {
        AgentEnrollRequest r = request();
        AgentEnrollDiagnostics d = new AgentEnrollDiagnostics();
        d.setLastError("clock");
        d.setLastErrorAt(1_000L); // 1970: the phone's clock was wrong when it failed
        r.setDiagnostics(d);
        assertEquals(NOW, EnrollAttemptLog.build(r, token(), null, null, null, null, NOW).get(0).getAt());
    }

    @Test
    public void logging_without_a_dao_is_a_no_op() {
        new EnrollAttemptLog(null).record(request(), null, null, "error.agent.token.used", null);
        new EnrollAttemptLog(null).recordDownload("abcdefgh", null, null, false);
    }
}
