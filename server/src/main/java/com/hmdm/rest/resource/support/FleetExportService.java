/*
 * DallyControl — fleet inventory export (new module, not part of upstream Headwind).
 */
package com.hmdm.rest.resource.support;

import com.hmdm.notification.AgentWakeHub;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.domain.DeviceGroupView;
import com.hmdm.rest.resource.AgentPackageResource;
import com.hmdm.rest.resource.AgentResource;
import com.hmdm.util.DeviceExport;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Builds the fleet export's input (devices, folders, connection history) for one customer, narrowed to what the
 * caller may see, to a folder branch and to a list of device numbers. One builder for the device list's Excel/CSV
 * download, the "Inventario de la flota" report and the scheduled e-mails, so they cannot disagree.
 */
@Singleton
public class FleetExportService {

    private final AgentCommandDAO commandDAO;
    private final AgentWakeHub wakeHub;
    private final AgentPackageResource agentPackage;
    private final String baseUrl;

    @Inject
    public FleetExportService(AgentCommandDAO commandDAO, AgentWakeHub wakeHub, AgentPackageResource agentPackage,
                              @Named("base.url") String baseUrl) {
        this.commandDAO = commandDAO;
        this.wakeHub = wakeHub;
        this.agentPackage = agentPackage;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    /** What to export. Every field is optional. */
    public static class Request {
        /** A folder and its sub-folders. */
        public Integer groupId;
        /** Connection history window (default 30, 1-366). */
        public Integer days;
        public String tz;
        /** Only these device numbers (the list's filter or selection); null = all. */
        public Collection<String> numbers;
        public Integer lowBatteryPct;
        public Integer storageUsedPct;
        public Integer offlineDays;
    }

    /** A built export: the input plus a file-name stem such as "dispositivos-Bogota-20261127-0930". */
    public static class Built {
        public DeviceExport.Input input;
        public String fileBase;
    }

    /**
     * @param visibleNumbers the device numbers the caller reaches (a folder administrator's), or null for all
     * @param visibleGroups  the folders the caller reaches, or null for all
     * @return null when the requested folder does not exist or is not the caller's
     */
    public Built build(int customerId, Request rq, Set<String> visibleNumbers, Set<Integer> visibleGroups) {
        Request r = rq == null ? new Request() : rq;
        DeviceExport.Input in = new DeviceExport.Input();
        in.baseUrl = baseUrl;
        in.zone = zone(r.tz);
        in.now = System.currentTimeMillis();
        int d = r.days == null ? 30 : Math.max(1, Math.min(r.days, 366));
        in.from = in.now - d * 86_400_000L;
        in.to = in.now;
        in.connectionGapMs = AgentResource.CONNECTION_GAP_MS;
        in.groups = commandDAO.listGroups(customerId);
        in.devices = commandDAO.listDeviceExportRows(customerId);
        in.connections = commandDAO.listConnections(customerId, in.from, in.to);
        if (visibleNumbers != null) {
            in.devices.removeIf(x -> !visibleNumbers.contains(String.valueOf(x.get("number"))));
            in.connections.removeIf(x -> !visibleNumbers.contains(String.valueOf(x.get("devicenumber"))));
            Set<Integer> mine = visibleGroups == null ? new HashSet<>() : visibleGroups;
            in.groups.removeIf(g -> !mine.contains(g.getId()));
        }
        String fileBase = "dispositivos";
        if (r.groupId != null) {
            DeviceGroupView g = null;
            for (DeviceGroupView x : in.groups) if (r.groupId.equals(x.getId())) g = x;
            if (g == null) return null;
            in.onlyGroups = DeviceExport.branch(r.groupId, in.groups);
            in.scopeLabel = "Carpeta " + g.getName() + " y sus subcarpetas";
            fileBase += "-" + g.getName().replaceAll("[^A-Za-z0-9._-]+", "_");
        }
        if (r.numbers != null) {
            in.onlyNumbers = new HashSet<>();
            for (String n : r.numbers) if (n != null && !n.trim().isEmpty()) in.onlyNumbers.add(n.trim());
            in.scopeLabel = (r.groupId == null ? "" : in.scopeLabel + " · ") + in.onlyNumbers.size()
                    + " dispositivo(s) filtrados o seleccionados";
            fileBase += "-seleccion";
        }
        if (r.lowBatteryPct != null) in.lowBatteryPct = Math.max(1, Math.min(100, r.lowBatteryPct));
        if (r.storageUsedPct != null) in.storageUsedPct = Math.max(1, Math.min(100, r.storageUsedPct));
        if (r.offlineDays != null) in.offlineDays = Math.max(1, Math.min(3660, r.offlineDays));
        try {
            in.live = new HashSet<>(wakeHub.liveNumbers());
        } catch (Exception e) {
            in.live = null; // the live channel is a bonus: the last report still decides
        }
        try {
            in.latestAgent = agentPackage == null ? null : agentPackage.latestVersion();
        } catch (Exception e) {
            in.latestAgent = null;
        }
        Built b = new Built();
        b.input = in;
        b.fileBase = fileBase + "-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(Instant.ofEpochMilli(in.now).atZone(in.zone));
        return b;
    }

    public static ZoneId zone(String tz) {
        try {
            if (tz != null && !tz.trim().isEmpty()) return ZoneId.of(tz.trim());
        } catch (Exception ignored) {
            // keep the default
        }
        return ZoneId.of("America/Bogota");
    }
}
