package com.hmdm.rest.resource.support;

import com.hmdm.persistence.AgentEnrollmentTokenDAO;
import com.hmdm.persistence.domain.AgentEnrollAttempt;
import com.hmdm.persistence.domain.AgentEnrollmentToken;
import com.hmdm.rest.json.agent.AgentEnrollDiagnostics;
import com.hmdm.rest.json.agent.AgentEnrollRequest;
import com.hmdm.util.EnrollmentCodes;
import com.hmdm.util.EnrollmentIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The log of enrollment attempts behind the console's Enroll page. Every call to {@code /enroll} leaves a row (who
 * asked, with what, and why it was refused), and so does the agent download a QR triggers, so an enrollment that
 * failed on a phone nobody has in hand still says where it stopped. Logging is best-effort: it never changes the
 * outcome of an enrollment.
 */
public class EnrollAttemptLog {

    private static final Logger logger = LoggerFactory.getLogger(EnrollAttemptLog.class);
    private static final String ERROR_PREFIX = "error.agent.";
    private static final int MAX_DETAIL = 500;

    private final AgentEnrollmentTokenDAO tokenDAO;

    public EnrollAttemptLog(AgentEnrollmentTokenDAO tokenDAO) {
        this.tokenDAO = tokenDAO;
    }

    /** One {@code /enroll} call: errorKey null = enrolled. Also notes the failure the agent had before this call. */
    public void record(AgentEnrollRequest request, String ip, String deviceNumber, String errorKey, String detail) {
        if (tokenDAO == null || request == null) {
            return;
        }
        try {
            AgentEnrollmentToken token = findToken(request.getEnrollToken());
            // A report without a token: never let the phone's own "no token" note duplicate the row.
            if (errorKey != null && errorKey.endsWith("device.no-token") && request.getDiagnostics() != null) {
                request.getDiagnostics().setLastError(null);
            }
            for (AgentEnrollAttempt attempt : build(request, token, ip, deviceNumber, errorKey, detail,
                    System.currentTimeMillis())) {
                tokenDAO.logAttempt(attempt);
            }
        } catch (Exception e) {
            logger.warn("Could not log the enrollment attempt: {}", e.toString());
        }
    }

    /** The phone asked for the agent APK through the QR's tracked address. */
    public void recordDownload(String tokenRef, String ip, String userAgent, boolean resumed) {
        if (tokenDAO == null || resumed) {
            return;
        }
        try {
            AgentEnrollmentToken token = tokenDAO.findByPrefix(tokenRef);
            String[] ua = EnrollmentIdentity.parseDownloadUserAgent(userAgent);
            AgentEnrollAttempt a = base(token, ip, System.currentTimeMillis());
            a.setOutcome(AgentEnrollAttempt.DOWNLOAD);
            a.setReason("agent.download");
            a.setAndroidRelease(ua[0]);
            a.setModel(ua[1]);
            a.setDetail(EnrollmentIdentity.cap(userAgent, MAX_DETAIL));
            a.setPath("qr");
            tokenDAO.logAttempt(a);
        } catch (Exception e) {
            logger.warn("Could not log the agent download: {}", e.toString());
        }
    }

    private AgentEnrollmentToken findToken(String presented) {
        if (presented == null || presented.trim().isEmpty()) {
            return null;
        }
        AgentEnrollmentToken token = tokenDAO.findByToken(presented.trim());
        if (token == null) {
            String code = EnrollmentCodes.normalize(presented);
            token = code == null ? null : tokenDAO.findByToken(code);
        }
        return token;
    }

    /** The rows one enroll call leaves: the call itself and, before it, the failure the agent reports having had. */
    static List<AgentEnrollAttempt> build(AgentEnrollRequest request, AgentEnrollmentToken token, String ip,
                                          String deviceNumber, String errorKey, String detail, long now) {
        List<AgentEnrollAttempt> out = new ArrayList<>();
        AgentEnrollDiagnostics d = request.getDiagnostics();
        if (d != null && d.getLastError() != null && !d.getLastError().trim().isEmpty()) {
            AgentEnrollAttempt note = fill(base(token, ip, now), request);
            note.setOutcome(AgentEnrollAttempt.NOTE);
            note.setReason("device." + EnrollmentIdentity.cap(d.getLastError(), 48));
            note.setDetail(EnrollmentIdentity.cap(d.getLastErrorDetail(), MAX_DETAIL));
            // When it happened on the phone, unless its clock is off (then: just before this call).
            long at = d.getLastErrorAt() == null ? now : d.getLastErrorAt();
            if (at > now || at < now - 7L * 24 * 3600 * 1000) {
                at = now;
            }
            note.setAt(at);
            note.setLastAt(at);
            out.add(note);
        }
        AgentEnrollAttempt a = fill(base(token, ip, now), request);
        a.setOutcome(errorKey == null ? AgentEnrollAttempt.OK : AgentEnrollAttempt.FAILED);
        a.setReason(errorKey == null ? "enrolled" : reason(errorKey));
        a.setDetail(EnrollmentIdentity.cap(detail, MAX_DETAIL));
        a.setDeviceNumber(deviceNumber);
        out.add(a);
        return out;
    }

    /** error.agent.token.used -> token.used (the console translates the short code). */
    static String reason(String errorKey) {
        String r = errorKey.startsWith(ERROR_PREFIX) ? errorKey.substring(ERROR_PREFIX.length()) : errorKey;
        return EnrollmentIdentity.cap(r, 64);
    }

    private static AgentEnrollAttempt base(AgentEnrollmentToken token, String ip, long now) {
        AgentEnrollAttempt a = new AgentEnrollAttempt();
        if (token != null) {
            a.setCustomerId(token.getCustomerId());
            a.setTokenId(token.getId());
            a.setGroupId(token.getGroupId());
        }
        a.setIp(EnrollmentIdentity.cap(ip, 64));
        a.setAt(now);
        a.setLastAt(now);
        return a;
    }

    private static AgentEnrollAttempt fill(AgentEnrollAttempt a, AgentEnrollRequest request) {
        if (request.getDevice() != null) {
            a.setManufacturer(EnrollmentIdentity.cap(request.getDevice().getManufacturer(), 100));
            a.setModel(EnrollmentIdentity.cap(request.getDevice().getModel(), 100));
            a.setAndroidRelease(EnrollmentIdentity.cap(request.getDevice().getAndroidRelease(), 32));
            a.setSdkInt(request.getDevice().getAndroidSdkInt());
        }
        if (request.getAgent() != null) {
            a.setAgentVersion(EnrollmentIdentity.cap(request.getAgent().getVersion(), 32));
        }
        a.setHardwareId(EnrollmentIdentity.cap(request.getHardwareId(), 128));
        a.setSerial(EnrollmentIdentity.cap(request.getSerial(), 64));
        AgentEnrollDiagnostics d = request.getDiagnostics();
        if (d != null) {
            a.setPath(EnrollmentIdentity.cap(d.getPath(), 24));
            a.setTries(d.getAttempts());
        }
        return a;
    }
}
