/*
 *
 * DallyControl — retention of the audit trail. Once a day (and shortly after boot) the rows older than each
 * customer's retention (audit_settings; 365 days when not set) are deleted, and every purge that removed something
 * leaves its own row in the trail: how many rows, up to which date, with which retention. It is the only code path
 * that deletes from audit_trail. Bound as an eager singleton, so it schedules itself at boot.
 *
 */

package com.hmdm.audit;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdm.persistence.AuditTrailDAO;
import com.hmdm.util.BackgroundTaskRunnerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

@Singleton
public class AuditPurgeTask {
    private static final Logger logger = LoggerFactory.getLogger(AuditPurgeTask.class);
    private static final long DAY_MS = 24L * 3600_000L;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("America/Bogota"));

    private final AuditTrailDAO dao;
    private final AuditTrailService service;

    @Inject
    public AuditPurgeTask(BackgroundTaskRunnerService runner, AuditTrailDAO dao, AuditTrailService service) {
        this.dao = dao;
        this.service = service;
        runner.submitRepeatableTask(this::run, 15, 24 * 60, TimeUnit.MINUTES);
    }

    /** Never throws. */
    public void run() {
        try {
            purge(System.currentTimeMillis());
        } catch (Exception e) {
            logger.warn("Audit retention purge failed: {}", e.toString());
        }
    }

    /** Purges every customer as of {@code now}; returns how many rows were removed in total. */
    public int purge(long now) {
        int total = 0;
        for (Integer customerId : dao.customerIds()) {
            int days = dao.retentionDays(customerId);
            total += purgeOne(customerId, days, now);
        }
        // Rows of no customer (failed sign-ins with an unknown user name) follow the default retention.
        total += purgeOne(null, AuditTrailDAO.DEFAULT_RETENTION_DAYS, now);
        return total;
    }

    private int purgeOne(Integer customerId, int days, long now) {
        if (days <= 0) return 0;
        long before = now - days * DAY_MS;
        int n = dao.purge(customerId, before);
        if (n > 0) {
            ObjectNode detail = JsonNodeFactory.instance.objectNode();
            detail.put("registrosEliminados", n);
            detail.put("retencionDias", days);
            detail.put("anterioresA", DAY.format(Instant.ofEpochMilli(before)));
            service.recordSystem(customerId, AuditCatalog.AUDIT, "audit.purge",
                    "Purga automática de la auditoría por retención", detail);
            logger.info("Audit trail: {} rows older than {} days purged for customer {}", n, days, customerId);
        }
        return n;
    }
}
