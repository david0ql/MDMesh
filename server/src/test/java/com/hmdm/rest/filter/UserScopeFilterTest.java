package com.hmdm.rest.filter;

import com.hmdm.rest.resource.support.UserScope;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** What a folder administrator may call: an explicit list, device/folder routes checked against their folders. */
public class UserScopeFilterTest {

    /** Reaches device "mine-1" (id 7) and folder 5 only. */
    private static final class Fake extends UserScope {
        Fake() { super(null, null); }
        @Override public boolean canDeviceNumber(int c, String n) { return "mine-1".equals(n); }
        @Override public boolean canDeviceId(int c, int id) { return id == 7; }
        @Override public boolean canGroup(Integer g) { return g != null && g == 5; }
    }

    private final UserScopeFilter f = new UserScopeFilter(new Fake());

    @Test
    public void ownDevicesAndFoldersOnly() {
        assertTrue(f.allowed("GET", "/rest/private/agent/v1/devices/mine-1/state", 1));
        assertTrue(f.allowed("POST", "/rest/private/agent/v1/devices/mine-1/commands", 1));
        assertFalse(f.allowed("GET", "/rest/private/agent/v1/devices/other-2/state", 1));
        assertFalse(f.allowed("POST", "/rest/private/agent/v1/devices/other-2/remote/start", 1));
        assertTrue(f.allowed("POST", "/rest/private/devices/7/description", 1));
        assertFalse(f.allowed("POST", "/rest/private/devices/8/description", 1));
        assertTrue(f.allowed("PUT", "/rest/private/fleet/v1/groups/5", 1));
        assertFalse(f.allowed("DELETE", "/rest/private/fleet/v1/groups/6", 1));
        assertFalse(f.allowed("POST", "/rest/private/fleet/v1/groups/6/commands", 1));
        assertTrue(f.allowed("GET", "/rest/private/fleet/v1/devices/7/scope", 1));
    }

    @Test
    public void everythingElseIsClosed() {
        assertFalse(f.allowed("PUT", "/rest/private/configurations", 1));
        assertFalse(f.allowed("DELETE", "/rest/private/configurations/3", 1));
        assertFalse(f.allowed("GET", "/rest/private/users/all", 1));
        assertFalse(f.allowed("PUT", "/rest/private/dc/users", 1));
        assertFalse(f.allowed("GET", "/rest/private/dc/users", 1));
        assertFalse(f.allowed("PUT", "/rest/private/fleet/v1/global", 1));
        assertFalse(f.allowed("POST", "/rest/private/fleet/v1/global/commands", 1));
        assertFalse(f.allowed("POST", "/rest/private/announcements", 1));
        assertFalse(f.allowed("PUT", "/rest/private/applications/android", 1));
        assertFalse(f.allowed("POST", "/rest/private/web-ui-files", 1));
        assertFalse(f.allowed("GET", "/rest/private/settings", 1));
        assertFalse(f.allowed("GET", "/rest/private/fleet/v1/export.xlsx", 1));
        assertFalse(f.allowed("POST", "/rest/private/agent/v1/rollout", 1));
    }

    @Test
    public void readsTheyNeedStayOpen() {
        assertTrue(f.allowed("GET", "/rest/private/users/current", 1));
        assertTrue(f.allowed("POST", "/rest/private/devices/search", 1));
        assertTrue(f.allowed("GET", "/rest/private/fleet/v1/groups", 1));
        assertTrue(f.allowed("GET", "/rest/private/agent/v1/live", 1));
        assertTrue(f.allowed("GET", "/rest/private/configurations/search", 1));
        assertFalse(f.allowed("PUT", "/rest/private/configurations/search", 1));
    }
}
