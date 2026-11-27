package com.hmdm.rest.resource.support;

import com.hmdm.persistence.GeofenceDAO;
import com.hmdm.util.BackgroundTaskRunnerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * DallyControl — background work of the geofence/alert modules: the alert e-mails (every minute) and the purge of
 * the location history older than each customer's retention (every six hours; 0 days = keep forever). Bound as an
 * eager singleton, so it schedules itself at boot; the first runs are delayed until the schema is migrated.
 */
@Singleton
public class GeofenceTasks {
    private static final Logger logger = LoggerFactory.getLogger(GeofenceTasks.class);
    private static final long DAY_MS = 24L * 3600_000L;

    private final GeofenceDAO dao;

    @Inject
    public GeofenceTasks(BackgroundTaskRunnerService runner, AlertNotifier notifier, GeofenceDAO dao) {
        this.dao = dao;
        runner.submitRepeatableTask(notifier, 2, 1, TimeUnit.MINUTES);
        runner.submitRepeatableTask(this::purge, 10, 360, TimeUnit.MINUTES);
    }

    /** Delete positions older than the retention of each customer that set one. Never throws. */
    public void purge() {
        try {
            long now = System.currentTimeMillis();
            for (Map<String, Object> r : dao.listRetention()) {
                Integer customerId = GeofenceDAO.intOf(r.get("customerid"));
                long days = GeofenceDAO.longOf(r.get("retentiondays"));
                if (customerId == null || days <= 0) continue;
                int n = dao.purgeLocations(customerId, now - days * DAY_MS);
                if (n > 0) logger.info("Location history: {} positions older than {} days purged for customer {}", n, days, customerId);
            }
            dao.purgeOrphanStates();
        } catch (Exception e) {
            logger.warn("Location history purge failed: {}", e.getMessage());
        }
    }
}
