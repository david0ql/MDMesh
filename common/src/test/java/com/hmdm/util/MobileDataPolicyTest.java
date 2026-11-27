package com.hmdm.util;

import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.RequestUpdatesType;
import com.hmdm.rest.json.agent.DesiredConfig;
import com.hmdm.rest.json.agent.DesiredMobileData;
import org.junit.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Mobile data per app: the policy section, a device's override and the document the device receives. */
public class MobileDataPolicyTest {

    private static DcPolicy.MobileData policy(String json) {
        return DcPolicy.parse("{\"mobileData\":" + json + "}").getMobileData();
    }

    private static MobileDataPolicy.DeviceOverride override(String json) {
        return MobileDataPolicy.parseOverride(json);
    }

    private static Configuration config(String dcPolicy) {
        Configuration c = new Configuration();
        c.setId(7);
        c.setRequestUpdates(RequestUpdatesType.DONOTTRACK);
        c.setDcPolicy(dcPolicy);
        return c;
    }

    @Test
    public void aPolicyWithoutTheSectionKeepsItsRevision() {
        DesiredConfig plain = DesiredConfigBuilder.build(config(null), Collections.emptyList());
        assertNull(plain.getMobileData());
        assertFalse("devices already in the field must not re-apply", DesiredConfigBuilder.canonicalJson(plain).contains("mobileData"));
        // A section that asks for nothing is the same as no section.
        DesiredConfig idle = DesiredConfigBuilder.build(config("{\"mobileData\":{\"mode\":\"open\"}}"), Collections.emptyList());
        assertEquals(plain.getRevision(), idle.getRevision());
        assertNull(DcPolicy.normalize("{\"mobileData\":{\"mode\":\"blocklist\",\"apps\":[]}}"));
    }

    @Test
    public void blocklistReachesTheDeviceAndChangesTheRevision() {
        String dc = "{\"mobileData\":{\"mode\":\"blocklist\",\"apps\":[\"com.video\",\"not a package\",\"com.audio\"]}}";
        DesiredConfig d = DesiredConfigBuilder.build(config(dc), Collections.emptyList());
        assertEquals("blocklist", d.getMobileData().getMode());
        assertEquals(Arrays.asList("com.audio", "com.video"), d.getMobileData().getApps());
        assertNull("automatic mechanism is the agent's default", d.getMobileData().getMechanism());
        assertNull(d.getMobileData().getDeviceLimitBytes());
        assertNotEquals(DesiredConfigBuilder.build(config(null), Collections.emptyList()).getRevision(), d.getRevision());
        assertTrue(DesiredConfigBuilder.toPayloadJson(d).contains("\"mobileData\":{\"mode\":\"blocklist\""));
    }

    @Test
    public void limitsAreStoredInMegabytesAndSentInBytes() {
        DcPolicy.MobileData p = policy("{\"mode\":\"open\",\"period\":\"monthly\",\"cutDay\":15,\"warnPercent\":90,\"deviceLimitMb\":2048,"
                + "\"appLimits\":[{\"pkg\":\"com.video\",\"limitMb\":500},{\"pkg\":\"bad\",\"limitMb\":5},{\"pkg\":\"com.zero\",\"limitMb\":0}],"
                + "\"essential\":[\"com.chat\"],\"mechanism\":\"VPN\"}");
        assertEquals(Long.valueOf(2048), p.getDeviceLimitMb());
        assertEquals(1, p.getAppLimits().size());
        DesiredMobileData d = MobileDataPolicy.desired(p, null);
        assertEquals("open", d.getMode());
        assertEquals("vpn", d.getMechanism());
        assertNull("monthly is the default", d.getPeriod());
        assertEquals(Integer.valueOf(15), d.getCutDay());
        assertEquals(Integer.valueOf(90), d.getWarnPercent());
        assertEquals(Long.valueOf(2048L * 1024 * 1024), d.getDeviceLimitBytes());
        assertEquals("com.video", d.getAppLimits().get(0).getPkg());
        assertEquals(Long.valueOf(500L * 1024 * 1024), d.getAppLimits().get(0).getBytes());
        assertEquals(Collections.singletonList("com.chat"), d.getEssential());
    }

    @Test
    public void invalidValuesAreDropped() {
        DcPolicy.MobileData p = policy("{\"mode\":\"weird\",\"cutDay\":31,\"warnPercent\":500,\"deviceLimitMb\":100,\"period\":\"yearly\",\"mechanism\":\"magic\"}");
        assertEquals("open", p.getMode());
        assertNull(p.getCutDay());
        assertNull(p.getWarnPercent());
        assertNull(p.getMechanism());
        assertEquals("monthly", p.getPeriod());
        assertNull("essentials need a device limit or an allowlist", policy("{\"mode\":\"blocklist\",\"apps\":[\"com.a\"],\"essential\":[\"com.b\"]}").getEssential());
        assertNull(policy("{\"deviceLimitMb\":-5}"));
        assertNull(policy("{\"deviceLimitMb\":99999999999}"));
    }

    @Test
    public void allowlistWithNoAppsStillRestrictsEverything() {
        DesiredMobileData d = MobileDataPolicy.desired(policy("{\"mode\":\"allowlist\"}"), null);
        assertEquals("allowlist", d.getMode());
        assertNull(d.getApps());
    }

    @Test
    public void deviceOverrideUnrestrictedLiftsEverything() {
        DcPolicy.MobileData p = policy("{\"mode\":\"allowlist\",\"apps\":[\"com.chat\"],\"deviceLimitMb\":100}");
        assertNull(MobileDataPolicy.desired(p, override("{\"mode\":\"unrestricted\",\"block\":[\"com.video\"]}")));
        assertEquals("{\"mode\":\"unrestricted\"}", MobileDataPolicy.normalizeOverride(override("{\"mode\":\"UNRESTRICTED\",\"block\":[\"com.video\"]}")));
    }

    @Test
    public void deviceOverrideAllowAndBlockOnTopOfABlocklist() {
        DcPolicy.MobileData p = policy("{\"mode\":\"blocklist\",\"apps\":[\"com.video\",\"com.audio\"],\"appLimits\":[{\"pkg\":\"com.chat\",\"limitMb\":10}]}");
        DesiredMobileData d = MobileDataPolicy.desired(p, override("{\"allow\":[\"com.video\",\"com.chat\"],\"block\":[\"com.game\"]}"));
        assertEquals(Arrays.asList("com.audio", "com.game"), d.getApps());
        assertNull("an app allowed on this device has no limit here", d.getAppLimits());
    }

    @Test
    public void deviceOverrideOnTopOfAnAllowlist() {
        DcPolicy.MobileData p = policy("{\"mode\":\"allowlist\",\"apps\":[\"com.chat\",\"com.mail\"],\"essential\":[\"com.mail\"],\"deviceLimitMb\":10}");
        DesiredMobileData d = MobileDataPolicy.desired(p, override("{\"allow\":[\"com.maps\"],\"block\":[\"com.mail\"]}"));
        assertEquals(Arrays.asList("com.chat", "com.maps"), d.getApps());
        assertEquals("allowed here = essential here; blocked here = not essential", Collections.singletonList("com.maps"), d.getEssential());
    }

    @Test
    public void deviceOverrideAloneCreatesASectionAndIgnoreLimitsKeepsTheLists() {
        DesiredMobileData only = MobileDataPolicy.desired(null, override("{\"block\":[\"com.video\"]}"));
        assertEquals("blocklist", only.getMode());
        assertEquals(Collections.singletonList("com.video"), only.getApps());

        DcPolicy.MobileData p = policy("{\"mode\":\"blocklist\",\"apps\":[\"com.video\"],\"deviceLimitMb\":10,\"appLimits\":[{\"pkg\":\"com.chat\",\"limitMb\":10}]}");
        DesiredMobileData d = MobileDataPolicy.desired(p, override("{\"ignoreLimits\":true}"));
        assertEquals(Collections.singletonList("com.video"), d.getApps());
        assertNull(d.getDeviceLimitBytes());
        assertNull(d.getAppLimits());
        assertNull("only limits + ignoreLimits = nothing left", MobileDataPolicy.desired(policy("{\"deviceLimitMb\":10}"), override("{\"ignoreLimits\":true}")));
    }

    @Test
    public void overrideIsCleanedAndNeverBreaks() {
        assertNull(override(null));
        assertNull(override("not json"));
        assertNull(override("{\"allow\":[\"???\"]}"));
        assertEquals("{\"allow\":[\"com.a\"]}", MobileDataPolicy.normalizeOverride(override("{\"allow\":[\"com.a\"],\"block\":[\"com.a\"]}")));
        assertNull(MobileDataPolicy.normalizeOverride(new MobileDataPolicy.DeviceOverride()));
    }

    @Test
    public void periodStartsOnTheCutDay() {
        LocalDate d = LocalDate.of(2026, 11, 27);
        assertEquals(LocalDate.of(2026, 11, 15), MobileDataPolicy.periodStart(d, "monthly", 15));
        assertEquals(LocalDate.of(2026, 12, 15), MobileDataPolicy.periodEnd(d, "monthly", 15));
        assertEquals(LocalDate.of(2026, 10, 28), MobileDataPolicy.periodStart(d, "monthly", 28));
        assertEquals(LocalDate.of(2026, 11, 1), MobileDataPolicy.periodStart(d, null, null));
        assertEquals(LocalDate.of(2026, 12, 20), MobileDataPolicy.periodStart(LocalDate.of(2027, 1, 3), "monthly", 20));
        assertEquals(d, MobileDataPolicy.periodStart(d, "daily", 9));
        assertEquals(d.plusDays(1), MobileDataPolicy.periodEnd(d, "daily", 9));
    }

    @Test
    public void usageRowsCompareUseWithLimits() {
        DesiredMobileData d = MobileDataPolicy.desired(policy("{\"deviceLimitMb\":100,\"appLimits\":[{\"pkg\":\"com.video\",\"limitMb\":10},{\"pkg\":\"com.chat\",\"limitMb\":10}]}"), null);
        Map<String, Long> usage = new HashMap<String, Long>();
        usage.put("com.video", 12L * MobileDataPolicy.MB);
        usage.put("com.other", 50L * MobileDataPolicy.MB);
        List<Map<String, Object>> rows = MobileDataPolicy.usageRows(d, usage, Collections.singletonMap("com.video", "Video"), 85L * MobileDataPolicy.MB, false);
        assertEquals(3, rows.size());
        assertEquals("device", rows.get(0).get("scope"));
        assertEquals("warn", rows.get(0).get("level"));
        assertEquals(85L, rows.get(0).get("percent"));
        assertEquals("com.chat", rows.get(1).get("pkg"));
        assertEquals("ok", rows.get(1).get("level"));
        assertEquals("Video", rows.get(2).get("label"));
        assertEquals("over", rows.get(2).get("level"));
        assertEquals("Límite superado", rows.get(2).get("estado"));
        // A device whose policy has no limit still shows its consumption when asked.
        List<Map<String, Object>> noLimit = MobileDataPolicy.usageRows(null, usage, null, 5L, true);
        assertEquals(1, noLimit.size());
        assertNull(noLimit.get(0).get("level"));
        assertEquals("Sin límite", noLimit.get(0).get("estado"));
        assertNotNull(MobileDataPolicy.levelLabel("ok"));
    }
}
