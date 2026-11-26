/*
 *
 * DallyControl — fleet reports DAO (new module, not part of upstream Headwind).
 *
 * Thin read-only wrapper over {@link ReportsMapper}. Every call is scoped by customerId.
 *
 */

package com.hmdm.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.mapper.ReportsMapper;

import java.util.List;
import java.util.Map;

/** DAO for the "Reportes" console module. Read-only aggregation over data the agent already reports. */
@Singleton
public class ReportsDAO {

    private final ReportsMapper mapper;

    @Inject
    public ReportsDAO(ReportsMapper mapper) {
        this.mapper = mapper;
    }

    public List<Map<String, Object>> offlineDevices(int customerId, long cutoff) {
        return mapper.offlineDevices(customerId, cutoff);
    }

    public List<Map<String, Object>> simEvents(int customerId, long from, long to) {
        return mapper.simEvents(customerId, from, to);
    }

    public List<Map<String, Object>> appInstallAggregate(int customerId) {
        return mapper.appInstallAggregate(customerId);
    }

    public List<Map<String, Object>> appInstallAggregateScoped(int customerId, String devs) {
        return mapper.appInstallAggregateScoped(customerId, devs);
    }

    public List<Map<String, Object>> appInstallDevices(int customerId, String pkg) {
        return mapper.appInstallDevices(customerId, pkg);
    }

    public List<Map<String, Object>> installedAppsCatalog(int customerId) {
        return mapper.installedAppsCatalog(customerId);
    }

    public List<Map<String, Object>> installedAppsCatalogScoped(int customerId, String devs) {
        return mapper.installedAppsCatalogScoped(customerId, devs);
    }

    public List<Map<String, Object>> devicesByApp(int customerId, String pkgsCsv) {
        return mapper.devicesByApp(customerId, pkgsCsv);
    }

    public List<Map<String, Object>> noCommandDevices(int customerId, long cutoff) {
        return mapper.noCommandDevices(customerId, cutoff);
    }

    public List<Map<String, Object>> outdatedAgents(int customerId, String latestVersion) {
        return mapper.outdatedAgents(customerId, latestVersion);
    }

    public List<Map<String, Object>> outdatedApps(int customerId) {
        return mapper.outdatedApps(customerId);
    }

    public List<Map<String, Object>> dataUsage(int customerId, String fromDay, int limit) {
        return mapper.dataUsage(customerId, fromDay, limit);
    }

    public List<Map<String, Object>> dataUsageByApp(int customerId, String number, String fromDay) {
        return mapper.dataUsageByApp(customerId, number, fromDay);
    }

    public List<Map<String, Object>> complianceRows(int customerId) {
        return mapper.complianceRows(customerId);
    }

    public List<Map<String, Object>> decommissioned(int customerId, long from, long to) {
        return mapper.decommissioned(customerId, from, to);
    }
}
