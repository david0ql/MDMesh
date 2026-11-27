/*
 *
 * DallyControl — audit trail DAO (new module, not part of upstream Headwind). Thin wrapper over AuditTrailMapper.
 * There is deliberately no update and no delete of a single row: the only way a row leaves the table is the
 * retention purge, which is itself recorded.
 *
 */

package com.hmdm.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.domain.AuditEntry;
import com.hmdm.persistence.domain.AuditQuery;
import com.hmdm.persistence.mapper.AuditTrailMapper;
import com.hmdm.util.AuditHash;
import org.mybatis.guice.transactional.Transactional;

import java.util.List;
import java.util.Map;

@Singleton
public class AuditTrailDAO {

    public static final int DEFAULT_RETENTION_DAYS = 365;

    private final AuditTrailMapper mapper;

    @Inject
    public AuditTrailDAO(AuditTrailMapper mapper) {
        this.mapper = mapper;
    }

    /** Appends one row, chained to the previous one by hash. */
    @Transactional
    public void append(AuditEntry entry) {
        mapper.lockChain();
        entry.prevHash = mapper.lastHash();
        entry.hash = AuditHash.of(entry);
        mapper.insert(entry);
    }

    public List<AuditEntry> search(AuditQuery q) {
        return mapper.search(q);
    }

    public long count(AuditQuery q) {
        return mapper.count(q);
    }

    public List<Map<String, Object>> users(AuditQuery q) {
        return mapper.users(q);
    }

    public List<Map<String, Object>> actions(AuditQuery q) {
        return mapper.actions(q);
    }

    public Long lastRemoteStart(String number, long since) {
        return mapper.lastRemoteStart(number, since);
    }

    public List<AuditEntry> chain(long afterId, int limit) {
        return mapper.chain(afterId, limit);
    }

    public long total() {
        return mapper.total();
    }

    public int retentionDays(int customerId) {
        Integer d = mapper.retentionDays(customerId);
        return d == null ? DEFAULT_RETENTION_DAYS : d;
    }

    public void setRetentionDays(int customerId, int days) {
        mapper.setRetentionDays(customerId, days);
    }

    public List<Integer> customerIds() {
        return mapper.customerIds();
    }

    /** Deletes a customer's rows older than {@code before}. The only delete the table accepts. */
    @Transactional
    public int purge(Integer customerId, long before) {
        mapper.allowPurge();
        return customerId == null ? mapper.purgeNoCustomer(before) : mapper.purge(customerId, before);
    }

    public AuditTrailMapper lookups() {
        return mapper;
    }
}
