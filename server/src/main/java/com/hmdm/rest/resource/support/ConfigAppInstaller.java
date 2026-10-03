/*
 * DallyControl agent-v1: queues a device's configuration apps for installation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hmdm.rest.resource.support;

import com.hmdm.notification.AgentWakeHub;
import com.hmdm.rest.json.InstallPayloadBuilder;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Device;
import com.hmdm.util.RolloutProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;

/**
 * Turns a device's configuration app list into queued {@code app.install} / {@code app.uninstall}
 * commands — the piece that makes a configuration a "golden image" for the command-driven agent
 * (which never reads the configuration itself). Used at enrollment and by the admin "sync apps" action.
 */
@Singleton
public class ConfigAppInstaller {

    private static final Logger logger = LoggerFactory.getLogger(ConfigAppInstaller.class);

    /** Action value in configurationApplications meaning "install this app". */
    private static final int ACTION_INSTALL = 1;
    /** Action value in configurationApplications meaning "remove this app from the device". */
    private static final int ACTION_REMOVE = 2;
    /** The agent's own packages (release + debug) — a configuration can never make it uninstall itself. */
    private static final String AGENT_PACKAGE_PREFIX = "com.dallycontrol.agent";

    private final UnsecureDAO unsecureDAO;
    private final PolicyApps policyApps;
    private final AgentCommandDAO commandDAO;
    private final AgentWakeHub wakeHub;

    @Inject
    public ConfigAppInstaller(UnsecureDAO unsecureDAO, AgentCommandDAO commandDAO, AgentWakeHub wakeHub, PolicyApps policyApps) {
        this.policyApps = policyApps;
        this.unsecureDAO = unsecureDAO;
        this.commandDAO = commandDAO;
        this.wakeHub = wakeHub;
    }

    /**
     * Queue an {@code app.install} for every action=install app of the device's configuration
     * that has a real hosted APK URL. Returns the number queued. Never throws — callers treat
     * this as best-effort (enrollment must not fail because an app list is dirty).
     */
    public int enqueueConfigApps(Device device) {
        if (device == null || device.getConfigurationId() == null) {
            return 0;
        }
        int queued = 0;
        try {
            List<Application> apps = unsecureDAO.getPlainConfigurationApplications(
                    device.getCustomerId(), device.getConfigurationId());
            // Plus the apps of the policy's app groups.
            if (policyApps != null) {
                com.hmdm.persistence.domain.Configuration cfg = unsecureDAO.getConfigurationById(device.getConfigurationId());
                if (cfg != null) apps = policyApps.resolve(cfg, apps, device.getId()).apps;
            }
            long now = System.currentTimeMillis();
            for (Application app : apps) {
                String uninstallPkg = uninstallTarget(app);
                if (uninstallPkg != null) {
                    if (commandDAO.hasOpenIdentical(device.getNumber(), "app.uninstall", uninstallPayload(uninstallPkg))) {
                        continue;
                    }
                    AgentCommand cmd = new AgentCommand();
                    cmd.setDeviceNumber(device.getNumber());
                    cmd.setType("app.uninstall");
                    cmd.setPayload(uninstallPayload(uninstallPkg));
                    cmd.setRequiresCapability(RolloutProgress.INSTALL_CAPABILITY);
                    cmd.setStatus("pending");
                    cmd.setCreatedAt(now);
                    commandDAO.insert(cmd);
                    queued++;
                    continue;
                }
                if (app == null || app.getAction() != ACTION_INSTALL) {
                    continue;
                }
                String url = firstUsableUrl(app);
                boolean hasParts = app.getParts() != null && !app.getParts().trim().isEmpty();
                if ((url == null && !hasParts) || app.getPkg() == null || app.getPkg().trim().isEmpty()) {
                    // Catalog placeholder / web app / seed leftover — nothing downloadable.
                    continue;
                }
                String payload = InstallPayloadBuilder.build(app.getPkg().trim(), app.getVersionCode(), app.getVersion(), url, app.getParts());
                // Re-queued on every configuration save: skip one already on its way. The agent itself skips an
                // app already at that version before downloading anything, so a repeat costs one tiny command.
                if (commandDAO.hasOpenIdentical(device.getNumber(), "app.install", payload)) {
                    continue;
                }
                AgentCommand cmd = new AgentCommand();
                cmd.setDeviceNumber(device.getNumber());
                cmd.setType("app.install");
                cmd.setPayload(payload);
                cmd.setRequiresCapability(RolloutProgress.INSTALL_CAPABILITY);
                cmd.setStatus("pending");
                cmd.setCreatedAt(now);
                commandDAO.insert(cmd);
                queued++;
            }
            if (queued > 0) {
                wakeHub.wake(device.getNumber(), "commands");
            }
        } catch (Exception e) {
            logger.warn("Failed to queue configuration apps for device {}", device.getNumber(), e);
        }
        return queued;
    }

    /**
     * Queue the configuration's apps for every device running it (after the configuration was saved), so an APK
     * added to a configuration reaches its phones without visiting each one. Returns the number of commands queued.
     */
    /** Queue installs for every device of these folders and the folders below them (app groups assigned to folders). */
    public int enqueueForFolders(int customerId, java.util.Set<Integer> folderIds) {
        if (folderIds == null || folderIds.isEmpty()) return 0;
        java.util.Set<Integer> branch = new java.util.HashSet<>();
        for (Integer f : folderIds) branch.addAll(commandDAO.groupSubtree(customerId, f));
        int queued = 0;
        for (com.hmdm.persistence.domain.DeviceScopeRow r : commandDAO.listDeviceScopes(customerId)) {
            if (r.getGroupId() == null || !branch.contains(r.getGroupId())) continue;
            Device d = unsecureDAO.getDeviceByNumber(r.getNumber());
            if (d == null) continue;
            queued += enqueueConfigApps(d);
            wakeHub.wake(r.getNumber(), "commands"); // its policy document changed too (allowed apps / kiosk)
        }
        return queued;
    }

    public int enqueueForConfiguration(int configurationId) {
        int queued = 0;
        for (String number : commandDAO.listDeviceNumbersByConfigurationId(configurationId)) {
            queued += enqueueConfigApps(unsecureDAO.getDeviceByNumber(number));
        }
        return queued;
    }

    /**
     * The package an action=remove configuration app asks the device to uninstall, or null when the
     * app is not a removal (or would remove the agent itself). Silent uninstall needs Device Owner,
     * the same capability gate as install.
     */
    static String uninstallTarget(Application app) {
        if (app == null || app.getAction() != ACTION_REMOVE || app.getPkg() == null) {
            return null;
        }
        String pkg = app.getPkg().trim();
        if (pkg.isEmpty() || pkg.startsWith(AGENT_PACKAGE_PREFIX)) {
            return null;
        }
        return pkg;
    }

    /** {@code {"packageName":"..."}}, JSON-escaped. */
    static String uninstallPayload(String pkg) {
        return JSON.createObjectNode().put("packageName", pkg).toString();
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Only http(s) URLs are installable by the agent, and the upstream seed ships literal
     * placeholder URLs (e.g. {@code .../_HMDM_APK_}) that must never reach a device.
     */
    private static String firstUsableUrl(Application app) {
        for (String candidate : new String[]{app.getUrl(), app.getUrlArm64(), app.getUrlArmeabi()}) {
            if (candidate == null) {
                continue;
            }
            String u = candidate.trim();
            if ((u.startsWith("https://") || u.startsWith("http://")) && !u.contains("_HMDM_")) {
                return u;
            }
        }
        return null;
    }
}
