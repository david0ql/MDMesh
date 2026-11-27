package com.hmdm.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.domain.SupportSession;
import com.hmdm.persistence.domain.SupportSessionEvent;
import com.hmdm.persistence.mapper.SupportSessionMapper;

import java.util.List;

/** Remote-support session log (who, when, how long, what was used). */
@Singleton
public class SupportSessionDAO {

    public static final int MAX_DETAIL = 2000;

    private final SupportSessionMapper mapper;

    @Inject
    public SupportSessionDAO(SupportSessionMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(SupportSession s) {
        mapper.insert(s);
    }

    public void markConnected(long id, long at) {
        mapper.markConnected(id, at);
    }

    public void setEngine(long id, String engine) {
        mapper.setEngine(id, engine);
    }

    public void end(long id, long at, String reason, int chat, int files, int voiceSeconds, int input) {
        mapper.end(id, at, reason, chat, files, voiceSeconds, input);
    }

    public int closeOrphans(long at) {
        return mapper.closeOrphans(at);
    }

    public SupportSession get(long id) {
        return mapper.get(id);
    }

    /** Newest first. {@code numbers}: null = every device of the customer; empty = none. */
    public List<SupportSession> list(int customerId, String deviceNumber, List<String> numbers, Long from, Long to, int limit) {
        if (numbers != null && numbers.isEmpty()) return java.util.Collections.emptyList();
        return mapper.list(customerId, deviceNumber, numbers, from, to, Math.max(1, Math.min(limit, 1000)));
    }

    public void addEvent(long sessionId, long ts, String kind, String origin, String detail) {
        SupportSessionEvent e = new SupportSessionEvent();
        e.setSessionId(sessionId);
        e.setTs(ts);
        e.setKind(kind);
        e.setOrigin(origin);
        e.setDetail(detail == null ? null : detail.substring(0, Math.min(detail.length(), MAX_DETAIL)));
        mapper.insertEvent(e);
    }

    public List<SupportSessionEvent> events(long sessionId) {
        return mapper.events(sessionId);
    }
}
