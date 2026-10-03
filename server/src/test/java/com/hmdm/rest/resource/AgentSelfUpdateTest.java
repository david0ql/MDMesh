package com.hmdm.rest.resource;

import com.hmdm.persistence.domain.AgentCommand;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentSelfUpdateTest {
    private static AgentCommand cmd(String type, String payload) {
        AgentCommand c = new AgentCommand();
        c.setType(type);
        c.setPayload(payload);
        return c;
    }

    @Test
    public void only_an_install_of_the_agent_package_is_a_self_update() {
        assertTrue(AgentResource.isAgentSelfUpdate(cmd("app.install", "{\"url\":\"https://x/a.apk\",\"packageName\":\"com.dallycontrol.agent\",\"versionCode\":27}")));
        assertTrue(AgentResource.isAgentSelfUpdate(cmd("app.install", "{\"packageName\":\"com.dallycontrol.agent.debug\"}")));
        assertFalse(AgentResource.isAgentSelfUpdate(cmd("app.install", "{\"packageName\":\"co.amovil.preventa\"}")));
        assertFalse(AgentResource.isAgentSelfUpdate(cmd("app.uninstall", "{\"packageName\":\"com.dallycontrol.agent\"}")));
        assertFalse(AgentResource.isAgentSelfUpdate(cmd("app.install", null)));
        assertFalse(AgentResource.isAgentSelfUpdate(null));
        assertTrue(AgentResource.isAgentSelfUpdate(cmd("app.install", "{\"url\": \"https://x/a.apk\", \"packageName\": \"com.dallycontrol.agent\"}")));
    }

    @Test
    public void store_bundles_wait_for_an_agent_that_picks_splits() {
        assertTrue(AgentResource.isStoreBundleInstall(cmd("app.install", "{\"packageName\":\"com.waze\",\"parts\":[{\"url\":\"u\",\"split\":\"base\"},{\"url\":\"v\",\"split\":\"config.arm64_v8a\"}]}")));
        assertFalse(AgentResource.isStoreBundleInstall(cmd("app.install", "{\"packageName\":\"x.y\",\"url\":\"u\"}")));
        assertTrue(AgentResource.atLeast("0.7.0", 0, 7, 0));
        assertTrue(AgentResource.atLeast("0.10.1", 0, 7, 0));
        assertTrue(AgentResource.atLeast("1.0", 0, 7, 0));
        assertFalse(AgentResource.atLeast("0.5.3", 0, 7, 0));
        assertFalse(AgentResource.atLeast(null, 0, 7, 0));
        assertFalse(AgentResource.atLeast("dev", 0, 7, 0));
    }
}
