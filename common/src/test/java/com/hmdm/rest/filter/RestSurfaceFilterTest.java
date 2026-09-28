package com.hmdm.rest.filter;

import org.junit.Assert;
import org.junit.Test;

/** The unauthenticated REST surface is an allowlist; encodings and matrix params must not get around it. */
public class RestSurfaceFilterTest {

    private static void allowed(String p) { Assert.assertTrue(p, RestSurfaceFilter.isAllowed(p)); }
    private static void blocked(String p) { Assert.assertFalse(p, RestSurfaceFilter.isAllowed(p)); }

    @Test
    public void usedEndpointsStayReachable() {
        allowed("/rest/private/devices/search");
        allowed("/rest/private/agent/v1/devices/abc/state");
        allowed("/rest/public/agent/v1/checkin");
        allowed("/rest/public/agent/v1/enroll");
        allowed("/rest/public/agent/v1/remote/tunnel");
        allowed("/rest/public/auth/login");
        allowed("/rest/public/auth/logout");
        allowed("/rest/public/auth/options");
        allowed("/rest/public/passwordReset/reset");
        allowed("/rest/public/name");
    }

    @Test
    public void legacyAndInternalEndpointsAreClosed() {
        blocked("/rest/public/sync/configuration/123");
        blocked("/rest/public/sync/info");
        blocked("/rest/notifications/device/123");
        blocked("/rest/plugins/messaging/public/status/1/1");
        blocked("/rest/plugins/deviceinfo/deviceinfo/public/123");
        blocked("/rest/plugins/devicelog/log/rules/123");
        blocked("/rest/public/stats");
        blocked("/rest/public/signup/verifyEmail");
        blocked("/rest/public/qr/abc");
        blocked("/rest/swagger.json");
        blocked("/rest/swagger.yaml");
        blocked("/rest/public/auth/loginX");
        blocked("/rest/public/agent/v1");
        blocked("/rest/private");
        blocked("/rest/");
    }

    @Test
    public void encodingTricksDoNotBypass() {
        blocked("/rest/public/%73ync/configuration/1");         // %73 = s
        blocked("/rest/public/auth/login/../../sync/info");
        blocked("/rest/public/agent/v1/..%2f..%2fsync/info");
        blocked("/rest/public/agent/v1/%2e%2e/%2e%2e/sync/info");
        blocked("/rest/public/sync;x=1/configuration/1");
        blocked("/rest/public/agent/v1/x\\..\\..\\sync");
        blocked("/rest/public/%zz");
        allowed("/rest/public/agent;jsessionid=1/v1/checkin");  // matrix params are stripped, still the agent path
        allowed("//rest//private//devices");
    }
}
