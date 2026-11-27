package com.hmdm.rest.resource.support;

import com.hmdm.notification.AgentWakeHub;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.mapper.AppInstallStatusMapper;
import com.hmdm.util.AgentCapabilityTokens;
import com.hmdm.util.ExecutorRegistry;
import com.hmdm.util.InstallFleetStatus;
import com.hmdm.util.InstallRetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sends a failed install again: by itself after a growing wait, a limited number of times ({@link InstallRetryPolicy}),
 * and at once when the console asks ("Reintentar"). It only ADDS a new command to the queue — the failed one stays as
 * it is, and delivery, expiry and re-delivery of commands are not its business.
 */
@Singleton
public class InstallRetrier {
    private static final Logger logger = LoggerFactory.getLogger(InstallRetrier.class);

    /** How far back the commands of a device are read to count its failed attempts in a row. */
    static final long HISTORY_MS = 5L * 24 * 3600_000L;
    /** A failure older than this is no longer retried by itself (the last wait is 24 h). */
    static final long FAILED_WINDOW_MS = 2L * 24 * 3600_000L;
    private static final long PERIOD_MIN = 5;
    private static final String FORCE_TYPE = "device.appInstallForce";
    /** The agent updates itself through its own rollout: never retried from here. */
    private static final String AGENT_PACKAGE_PREFIX = "com.dallycontrol.agent";

    private final AppInstallStatusMapper mapper;
    private final AgentCommandDAO commandDAO;
    private final UnsecureDAO unsecureDAO;
    private final ConfigAppInstaller installer;
    private final AgentWakeHub wakeHub;

    private final ScheduledExecutorService scheduler = ExecutorRegistry.register(
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "install-retry");
                t.setDaemon(true);
                return t;
            }));

    @Inject
    public InstallRetrier(AppInstallStatusMapper mapper, AgentCommandDAO commandDAO, UnsecureDAO unsecureDAO,
                          ConfigAppInstaller installer, AgentWakeHub wakeHub) {
        this.mapper = mapper;
        this.commandDAO = commandDAO;
        this.unsecureDAO = unsecureDAO;
        this.installer = installer;
        this.wakeHub = wakeHub;
        scheduler.scheduleWithFixedDelay(this::runSafely, 2, PERIOD_MIN, TimeUnit.MINUTES);
    }

    private void runSafely() {
        try {
            int n = retryDue(System.currentTimeMillis());
            if (n > 0) logger.info("{} failed install(s) sent again automatically", n);
        } catch (Throwable t) {
            logger.warn("Automatic retry of failed installs did not run: {}", t.toString());
        }
    }

    /** One stored install command, as the status mapper returns it. */
    public static final class Row {
        public final int id;
        public final String deviceNumber;
        public final String type;
        public final String payload;
        public final String pkg;
        public final InstallFleetStatus.Cmd cmd;

        Row(Map<String, Object> m) {
            this.id = ((Number) m.get("id")).intValue();
            this.deviceNumber = (String) m.get("devicenumber");
            this.type = (String) m.get("type");
            this.payload = (String) m.get("payload");
            Object[] target = InstallFleetStatus.payloadTarget(payload);
            this.pkg = (String) target[1];
            Number created = (Number) m.get("createdat");
            Number completed = (Number) m.get("completedat");
            this.cmd = new InstallFleetStatus.Cmd(id, (String) m.get("status"), (String) m.get("detail"), (Long) target[0],
                    (String) m.get("requirescapability"), created == null ? 0L : created.longValue(),
                    completed == null ? null : completed.longValue());
        }
    }

    public static List<Row> rows(List<Map<String, Object>> raw) {
        List<Row> out = new ArrayList<>(raw.size());
        for (Map<String, Object> m : raw) out.add(new Row(m));
        return out;
    }

    /** The rows grouped by "deviceNumber|package", each group oldest first (rows without a readable package are left out). */
    public static Map<String, List<Row>> byDeviceAndPackage(List<Row> rows) {
        Map<String, List<Row>> out = new LinkedHashMap<>();
        for (Row r : rows) {
            if (r.pkg == null || r.deviceNumber == null) continue;
            out.computeIfAbsent(r.deviceNumber + "|" + r.pkg, k -> new ArrayList<>()).add(r);
        }
        return out;
    }

    /** Where the install of the newest version ordered in [group] stands. */
    public static InstallFleetStatus.Result classify(List<Row> group, Set<String> deviceTokens) {
        List<InstallFleetStatus.Cmd> cmds = new ArrayList<>(group.size());
        for (Row r : group) cmds.add(r.cmd);
        long target = group.isEmpty() ? 0 : group.get(group.size() - 1).cmd.versionCode;
        return InstallFleetStatus.classify(target, cmds, deviceTokens);
    }

    /** The groups whose install failed and whose automatic retry is due at [now]. Pure: decides, changes nothing. */
    static List<List<Row>> dueGroups(Map<String, List<Row>> groups, long now) {
        List<List<Row>> due = new ArrayList<>();
        for (List<Row> g : groups.values()) {
            Row last = g.get(g.size() - 1);
            if (last.pkg.startsWith(AGENT_PACKAGE_PREFIX)) continue;
            // Capabilities do not matter for a command that already finished: null = not looked at.
            InstallFleetStatus.Result r = classify(g, null);
            if (r.state != InstallFleetStatus.State.FAILED || r.nextRetryAt == null || now < r.nextRetryAt) continue;
            due.add(g);
        }
        return due;
    }

    /** Send again the failed installs whose wait is over; returns how many commands were queued. */
    public int retryDue(long now) {
        List<Row> rows = rows(mapper.commandsOfDevicesWithFailures(now - HISTORY_MS, now - FAILED_WINDOW_MS));
        int queued = 0;
        for (List<Row> g : dueGroups(byDeviceAndPackage(rows), now)) {
            Row last = g.get(g.size() - 1);
            try {
                if (resend(last, false)) queued++;
            } catch (Exception e) {
                logger.warn("Could not retry the install of {} on {}: {}", last.pkg, last.deviceNumber, e.toString());
            }
        }
        return queued;
    }

    /** What the phone's policy and the queue say NOW about the package of a failed install. */
    public static final class Facts {
        /** The payload the phone's policy would install the package with; null when the policy does not install it. */
        public final String policyPayload;
        public final boolean policyRemoves;
        /** The failed order was queued by a policy (not by a person). */
        public final boolean orderedByPolicy;
        /** An uninstall of the package was sent to the phone after the failed order. */
        public final boolean uninstalledSince;

        public Facts(String policyPayload, boolean policyRemoves, boolean orderedByPolicy, boolean uninstalledSince) {
            this.policyPayload = policyPayload;
            this.policyRemoves = policyRemoves;
            this.orderedByPolicy = orderedByPolicy;
            this.uninstalledSince = uninstalledSince;
        }
    }

    /**
     * The payload to send for a retry of [last], or null when it must NOT be retried: the app was taken out of the
     * phone's policy (or removed on purpose) between the failure and the retry. Pure — see {@link InstallRetryPolicy#decide}.
     */
    static String retryPayload(Row last, Facts facts) {
        switch (InstallRetryPolicy.decide(facts.policyPayload != null, facts.policyRemoves, facts.orderedByPolicy,
                facts.uninstalledSince)) {
            case RESEND_POLICY:
                return facts.policyPayload;
            case RESEND_SAME:
                return last.payload;
            default:
                return null;
        }
    }

    private Facts facts(Device device, Row last) {
        String policyPayload = null;
        for (ConfigAppInstaller.DesiredInstall d : installer.desiredInstalls(device)) {
            if (d.pkg.equals(last.pkg)) {
                policyPayload = d.payload;
                break;
            }
        }
        boolean removes = policyPayload == null
                && installer.policyWish(device, last.pkg) == ConfigAppInstaller.PolicyWish.REMOVE;
        boolean byPolicy = ConfigAppInstaller.ORIGIN_POLICY.equals(mapper.originOf(last.id));
        boolean uninstalled = mapper.uninstallsSince(last.deviceNumber,
                "%\"packageName\":\"" + last.pkg + "\"%", last.cmd.createdAt) > 0;
        return new Facts(policyPayload, removes, byPolicy, uninstalled);
    }

    /**
     * Queue the install of [last]'s package on its device again — only while that is still wanted: what the device's
     * policy wants now (a newer version may have replaced the one that failed), or the same order again when a person
     * gave it and nobody undid it. An app taken out of the policy since the failure is NOT installed again. With
     * [force], an agent that knows how first forgets that the phone's system refused that version. Returns false
     * when nothing was queued.
     */
    public boolean resend(Row last, boolean force) {
        Device device = unsecureDAO.getDeviceByNumber(last.deviceNumber);
        if (device == null) return false;
        Facts facts = facts(device, last);
        String payload = retryPayload(last, facts);
        if (payload == null) {
            logger.info("Install of {} on {} not retried: no longer assigned to the device", last.pkg, last.deviceNumber);
            return false;
        }
        boolean byPolicy = facts.policyPayload != null;
        boolean canForce = force && AgentCapabilityTokens.isAllowed(FORCE_TYPE,
                AgentCapabilityTokens.flatten(commandDAO.getDeviceCapabilities(last.deviceNumber)));
        String type = canForce ? FORCE_TYPE : "app.install";
        if (commandDAO.hasOpenIdentical(last.deviceNumber, type, payload)) return false;
        AgentCommand cmd = new AgentCommand();
        cmd.setDeviceNumber(last.deviceNumber);
        cmd.setType(type);
        cmd.setPayload(payload);
        cmd.setRequiresCapability(canForce ? FORCE_TYPE
                : byPolicy ? com.hmdm.util.RolloutProgress.INSTALL_CAPABILITY : last.cmd.requiresCapability);
        cmd.setStatus("pending");
        cmd.setCreatedAt(System.currentTimeMillis());
        commandDAO.insert(cmd);
        // A policy's retry stays a policy order: it stops being retried once the policy drops the app.
        if (byPolicy && cmd.getId() != null) {
            mapper.recordOrigin(cmd.getId(), ConfigAppInstaller.ORIGIN_POLICY, cmd.getCreatedAt());
        }
        wakeHub.wake(last.deviceNumber, "commands");
        return true;
    }
}
