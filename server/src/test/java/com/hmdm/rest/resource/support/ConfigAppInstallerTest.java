package com.hmdm.rest.resource.support;

import com.hmdm.persistence.domain.Application;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ConfigAppInstallerTest {

    private static Application app(String pkg, int action) {
        Application a = new Application();
        a.setPkg(pkg);
        a.setAction(action);
        return a;
    }

    @Test
    public void removeActionTargetsThePackage() {
        assertEquals("org.example.game", ConfigAppInstaller.uninstallTarget(app(" org.example.game ", 2)));
    }

    @Test
    public void installAndSkipActionsAreNotRemovals() {
        assertNull(ConfigAppInstaller.uninstallTarget(app("org.example.app", 1)));
        assertNull(ConfigAppInstaller.uninstallTarget(app("org.example.app", 0)));
    }

    @Test
    public void neverRemovesTheAgentItself() {
        assertNull(ConfigAppInstaller.uninstallTarget(app("com.dallycontrol.agent", 2)));
        assertNull(ConfigAppInstaller.uninstallTarget(app("com.dallycontrol.agent.debug", 2)));
    }

    @Test
    public void blankOrMissingPackageIsIgnored() {
        assertNull(ConfigAppInstaller.uninstallTarget(app("  ", 2)));
        assertNull(ConfigAppInstaller.uninstallTarget(app(null, 2)));
        assertNull(ConfigAppInstaller.uninstallTarget(null));
    }

    @Test
    public void payloadIsEscapedJson() {
        assertEquals("{\"packageName\":\"a\\\"b\"}", ConfigAppInstaller.uninstallPayload("a\"b"));
    }
}
