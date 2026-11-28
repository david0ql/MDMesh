package com.hmdm.notification.push;

import com.hmdm.persistence.DevicePushDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.DevicePush;
import com.hmdm.rest.json.agent.AgentWakeConfig;
import com.hmdm.rest.json.agent.AgentWakeReport;
import com.hmdm.util.DcPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The wake part of a check-in: stores what the agent reports about how it can be woken, notes the check-in as the
 * answer to a pending push, and builds the wake block of the answer (the policy's backup schedule plus, when the
 * server has a push project, the values the agent registers with). Never fails a check-in.
 */
@Singleton
public class WakeCheckIn {

    private static final Logger log = LoggerFactory.getLogger(WakeCheckIn.class);
    private static final Set<String> STATES = new HashSet<>(Arrays.asList("ready", "unconfigured", "unavailable", "error"));
    private static final Set<String> WOKEN_BY = new HashSet<>(Arrays.asList(
            "push", "socket", "alarm", "screen", "unlock", "power", "network", "worker", "boot", "other"));
    static final int MAX_TOKEN = 4096;

    private static final long CACHE_MS = 30_000L;
    private final java.util.Map<Integer, Object[]> policyWake = new java.util.concurrent.ConcurrentHashMap<>();

    private final DevicePushDAO dao;
    private final PushWaker waker;
    private final UnsecureDAO unsecureDAO;

    @Inject
    public WakeCheckIn(DevicePushDAO dao, PushWaker waker, UnsecureDAO unsecureDAO) {
        this.dao = dao;
        this.waker = waker;
        this.unsecureDAO = unsecureDAO;
    }

    public void record(String deviceNumber, AgentWakeReport report) {
        try {
            waker.onCheckIn(deviceNumber);
            if (report == null) return;
            dao.upsertReport(row(deviceNumber, report, System.currentTimeMillis()));
        } catch (Exception e) {
            log.warn("Could not record the wake report of {}: {}", deviceNumber, e.getMessage());
        }
    }

    /** The agent is untrusted input: unknown values are dropped, long ones cut. */
    static DevicePush row(String deviceNumber, AgentWakeReport report, long now) {
        DevicePush row = new DevicePush();
        row.setDeviceNumber(deviceNumber);
        row.setUpdatedAt(now);
        row.setProvider("fcm".equals(report.getProvider()) ? "fcm" : null);
        String token = report.getToken() == null ? null : report.getToken().trim();
        boolean usable = row.getProvider() != null && token != null && !token.isEmpty() && token.length() <= MAX_TOKEN;
        row.setToken(usable ? token : null);
        row.setTokenAt(usable ? Long.valueOf(now) : null);
        row.setState(report.getState() != null && STATES.contains(report.getState()) ? report.getState() : null);
        String error = report.getError();
        row.setError(error == null ? null : error.length() > 380 ? error.substring(0, 380) : error);
        row.setBatteryExempt(report.getBatteryExempt());
        row.setExactAlarms(report.getExactAlarms());
        row.setBgRestricted(report.getBgRestricted());
        Integer poll = report.getPollMinutes();
        row.setPollMinutes(poll != null && poll >= 1 && poll <= 1440 ? poll : null);
        row.setLastWokenBy(report.getWokenBy() != null && WOKEN_BY.contains(report.getWokenBy()) ? report.getWokenBy() : null);
        return row;
    }

    /** @return null when there is neither a schedule in the policy nor a push project. */
    public AgentWakeConfig configFor(Device device) {
        try {
            DcPolicy.Wake wake = null;
            Integer cfgId = device == null ? null : device.getConfigurationId();
            if (cfgId != null) {
                // Read at most every CACHE_MS per policy: check-ins come in bursts while a console is open.
                long now = System.currentTimeMillis();
                Object[] cached = policyWake.get(cfgId);
                if (cached == null || now - (Long) cached[0] > CACHE_MS) {
                    Configuration cfg = unsecureDAO.getConfigurationById(cfgId);
                    cached = new Object[]{now, cfg == null ? null : DcPolicy.parse(cfg.getDcPolicy()).getWake()};
                    policyWake.put(cfgId, cached);
                }
                wake = (DcPolicy.Wake) cached[1];
            }
            return config(wake, waker.settings());
        } catch (Exception e) {
            log.warn("Could not build the wake configuration: {}", e.getMessage());
            return null;
        }
    }

    static AgentWakeConfig config(DcPolicy.Wake wake, FcmSettings fcm) {
        boolean push = fcm != null && fcm.isConfigured();
        if (wake == null && !push) return null;
        AgentWakeConfig out = new AgentWakeConfig();
        if (wake != null) {
            out.setPollMinutes(wake.getPollMinutes());
            out.setFastPollMinutes(wake.getFastPollMinutes());
            out.setFastWindowMinutes(wake.getFastWindowMinutes());
            out.setAlarmClock(wake.getAlarmClock());
        }
        if (push) {
            AgentWakeConfig.Push p = new AgentWakeConfig.Push();
            p.setProvider("fcm");
            p.setProjectId(fcm.projectId);
            p.setApiKey(fcm.apiKey);
            p.setSenderId(fcm.senderId);
            p.setAppIds(fcm.appIds);
            out.setPush(p);
        }
        return out;
    }
}
