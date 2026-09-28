package com.hmdm.util;

import com.hmdm.persistence.domain.DeviceScopeRow;
import org.junit.Assert;
import org.junit.Test;

/** Precedence of configuration levels: device (pinned) > group > global. */
public class ConfigurationScopesTest {

    private static DeviceScopeRow row(Integer own, boolean pinned, Integer group) {
        DeviceScopeRow r = new DeviceScopeRow();
        r.setConfigurationId(own);
        r.setConfigurationPinned(pinned);
        r.setGroupConfigurationId(group);
        return r;
    }

    @Test
    public void pinnedDeviceWinsOverGroupAndGlobal() {
        DeviceScopeRow r = row(7, true, 3);
        Assert.assertEquals(ConfigurationScopes.DEVICE, ConfigurationScopes.source(r, 1));
        Assert.assertEquals(Integer.valueOf(7), ConfigurationScopes.effective(r, 1));
    }

    @Test
    public void groupWinsOverGlobal() {
        DeviceScopeRow r = row(7, false, 3);
        Assert.assertEquals(ConfigurationScopes.GROUP, ConfigurationScopes.source(r, 1));
        Assert.assertEquals(Integer.valueOf(3), ConfigurationScopes.effective(r, 1));
    }

    @Test
    public void globalAppliesWithoutGroupConfiguration() {
        DeviceScopeRow r = row(7, false, null);
        Assert.assertEquals(ConfigurationScopes.GLOBAL, ConfigurationScopes.source(r, 1));
        Assert.assertEquals(Integer.valueOf(1), ConfigurationScopes.effective(r, 1));
    }

    @Test
    public void nothingAboveKeepsTheDevicesOwn() {
        DeviceScopeRow r = row(7, false, null);
        Assert.assertEquals(Integer.valueOf(7), ConfigurationScopes.effective(r, null));
    }
}
