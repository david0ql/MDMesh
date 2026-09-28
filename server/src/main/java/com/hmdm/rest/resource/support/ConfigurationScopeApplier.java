package com.hmdm.rest.resource.support;

import com.hmdm.notification.AgentWakeHub;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.domain.DeviceScopeRow;
import com.hmdm.util.ConfigurationScopes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.function.Predicate;

/**
 * Re-resolves the effective configuration (device > group > global, {@link ConfigurationScopes}) of a customer's
 * devices after something above them changed, writes it to devices.configurationId and wakes each device whose
 * configuration changed, so it checks in and the desired-state reconcile queues config.apply at once.
 */
@Singleton
public class ConfigurationScopeApplier {

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationScopeApplier.class);

    private final AgentCommandDAO commandDAO;
    private final AgentWakeHub wakeHub;

    @Inject
    public ConfigurationScopeApplier(AgentCommandDAO commandDAO, AgentWakeHub wakeHub) {
        this.commandDAO = commandDAO;
        this.wakeHub = wakeHub;
    }

    /** Apply to the customer's devices that match [which]; returns how many changed configuration. */
    public int apply(int customerId, Predicate<DeviceScopeRow> which) {
        Integer global = commandDAO.getGlobalConfigurationId(customerId);
        int changed = 0;
        for (DeviceScopeRow row : commandDAO.listDeviceScopes(customerId)) {
            if (!which.test(row)) {
                continue;
            }
            Integer target = ConfigurationScopes.effective(row, global);
            if (target != null && !target.equals(row.getConfigurationId())) {
                commandDAO.updateDeviceConfiguration(row.getId(), target);
                wakeHub.wake(row.getNumber(), "commands");
                changed++;
            }
        }
        if (changed > 0) {
            logger.info("Configuration scopes: {} device(s) of customer {} moved to a new effective configuration",
                    changed, customerId);
        }
        return changed;
    }

    public int applyAll(int customerId) {
        return apply(customerId, r -> true);
    }
}
