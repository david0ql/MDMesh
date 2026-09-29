package com.hmdm.rest.resource.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdm.notification.AgentWakeHub;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.mapper.AnnouncementMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;

/**
 * Delivers announcements to phones as a {@code device.announce} command. A receipt stays "not received" until the
 * agent reports it, and every check-in re-sends what is still missing (commands expire; announcements must not), so a
 * phone that was off gets it when it comes back.
 */
@Singleton
public class AnnouncementSender {

    private static final Logger logger = LoggerFactory.getLogger(AnnouncementSender.class);
    public static final String TYPE = "device.announce";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentCommandDAO commandDAO;
    private final AnnouncementMapper mapper;
    private final AgentWakeHub wakeHub;

    @Inject
    public AnnouncementSender(AgentCommandDAO commandDAO, AnnouncementMapper mapper, AgentWakeHub wakeHub) {
        this.commandDAO = commandDAO;
        this.mapper = mapper;
        this.wakeHub = wakeHub;
    }

    /** Queue one announcement row (as read by the mapper: lower-case keys) for a device and stamp its receipt. */
    public void send(String deviceNumber, Map<String, Object> a, boolean wake) {
        ObjectNode p = JSON.createObjectNode();
        int id = ((Number) a.get("id")).intValue();
        p.put("id", id);
        p.put("title", str(a.get("title")));
        if (a.get("body") != null) p.put("body", str(a.get("body")));
        if (a.get("mediaurl") != null) p.put("mediaUrl", str(a.get("mediaurl")));
        if (a.get("mediatype") != null) p.put("mediaType", str(a.get("mediatype")));
        p.put("mandatory", Boolean.TRUE.equals(a.get("mandatory")));
        if (a.get("createdat") != null) p.put("createdAt", ((Number) a.get("createdat")).longValue());
        if (a.get("expiresat") != null) p.put("expiresAt", ((Number) a.get("expiresat")).longValue());
        AgentCommand c = command(deviceNumber, p.toString());
        mapper.markSent(id, deviceNumber, c.getId(), System.currentTimeMillis());
        if (wake) wakeHub.wake(deviceNumber, "commands");
    }

    /** Tell a device to drop an announcement from its app. */
    public void withdraw(String deviceNumber, int id) {
        ObjectNode p = JSON.createObjectNode();
        p.put("id", id);
        p.put("withdraw", true);
        command(deviceNumber, p.toString());
        wakeHub.wake(deviceNumber, "commands");
    }

    /** On check-in: (re)send what this device still misses. Never breaks the check-in. */
    public void resendPending(String deviceNumber) {
        try {
            for (Map<String, Object> a : mapper.pendingFor(deviceNumber, System.currentTimeMillis())) {
                send(deviceNumber, a, false);
            }
        } catch (Exception e) {
            logger.warn("Could not resend announcements to {}: {}", deviceNumber, e.getMessage());
        }
    }

    /** Agent events: announcementReceived / announcementSeen / announcementAck with the id as detail. */
    public boolean record(String deviceNumber, String type, String detail, long ts) {
        if (type == null || !type.startsWith("announcement")) return false;
        int id;
        try {
            id = Integer.parseInt(detail == null ? "" : detail.trim().split("\\s+")[0]);
        } catch (NumberFormatException e) {
            return false;
        }
        switch (type) {
            case "announcementReceived": mapper.markReceived(id, deviceNumber, ts); return true;
            case "announcementSeen": mapper.markSeen(id, deviceNumber, ts); return true;
            case "announcementAck": mapper.markAck(id, deviceNumber, ts); return true;
            default: return false;
        }
    }

    private AgentCommand command(String deviceNumber, String payload) {
        AgentCommand c = new AgentCommand();
        c.setDeviceNumber(deviceNumber);
        c.setType(TYPE);
        c.setPayload(payload);
        c.setRequiresCapability(TYPE);
        c.setStatus("pending");
        c.setCreatedAt(System.currentTimeMillis());
        commandDAO.insert(c);
        return c;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
