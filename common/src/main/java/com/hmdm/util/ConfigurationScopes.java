package com.hmdm.util;

import com.hmdm.persistence.domain.DeviceScopeRow;

/**
 * <p>Where a device's configuration comes from. Three levels, the most specific wins:</p>
 * <ol>
 *     <li><b>device</b> — the device's own configuration, when an operator pinned it;</li>
 *     <li><b>group</b> — its group's (company's) configuration, when the group has one;</li>
 *     <li><b>global</b> — the customer's default configuration (settings.newDeviceConfigurationId).</li>
 * </ol>
 * <p>{@code devices.configurationId} always holds the effective configuration, so the desired-state reconcile
 * (config.apply) keeps working on it unchanged.</p>
 */
public final class ConfigurationScopes {

    public static final String DEVICE = "device";
    public static final String GROUP = "group";
    public static final String GLOBAL = "global";

    private ConfigurationScopes() {
    }

    /** The level that decides this device's configuration. */
    public static String source(DeviceScopeRow row, Integer globalConfigurationId) {
        if (row.isConfigurationPinned()) {
            return DEVICE;
        }
        if (row.getGroupConfigurationId() != null) {
            return GROUP;
        }
        if (globalConfigurationId != null) {
            return GLOBAL;
        }
        return DEVICE; // nothing above it: the device keeps what it has
    }

    /** The configuration the device must run, or null to leave it as it is. */
    public static Integer effective(DeviceScopeRow row, Integer globalConfigurationId) {
        switch (source(row, globalConfigurationId)) {
            case GROUP:
                return row.getGroupConfigurationId();
            case GLOBAL:
                return globalConfigurationId;
            default:
                return row.getConfigurationId();
        }
    }
}
