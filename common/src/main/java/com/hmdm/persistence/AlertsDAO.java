/*
 *
 * DallyControl — fleet alerts DAO (new module, not part of upstream Headwind).
 *
 * Thin wrapper over {@link AlertsMapper}. Generation is idempotent and run on read; everything is scoped by
 * customerId. See {@link AlertsMapper} for the derivation rules and dedup.
 *
 */

package com.hmdm.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.mapper.AlertsMapper;

import java.util.List;
import java.util.Map;

/** DAO for the "Alertas" console module. */
@Singleton
public class AlertsDAO {

    private final AlertsMapper mapper;

    @Inject
    public AlertsDAO(AlertsMapper mapper) {
        this.mapper = mapper;
    }

    /** Derive and persist any new alerts for this customer (idempotent). {@code cutoff} = now − offline threshold. */
    public void generate(int customerId, long cutoff, long now, String zone, long eventSince) {
        mapper.generateDisconnected(customerId, cutoff, now, zone);
        mapper.generateKioskExit(customerId, eventSince);
        mapper.generateSimChange(customerId, eventSince);
    }

    public List<Map<String, Object>> list(int customerId, String type, Long from, Long to, String status, int limit) {
        return mapper.list(customerId, type, from, to, status, limit);
    }

    public String deviceNumberOf(long id, int customerId) {
        return mapper.deviceNumberOf(id, customerId);
    }

    public int setReviewed(long id, boolean reviewed, Integer userId, Long now, int customerId) {
        return mapper.setReviewed(id, reviewed, userId, now, customerId);
    }
}
