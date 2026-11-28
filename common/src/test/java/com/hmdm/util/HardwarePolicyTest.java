package com.hmdm.util;

import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.rest.json.agent.DesiredConfig;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HardwarePolicyTest {

    private static Configuration cfg(String dcPolicy) {
        Configuration c = new Configuration();
        c.setId(7);
        c.setDcPolicy(dcPolicy);
        return c;
    }

    private static DesiredConfig build(Configuration c, Map<String, String> override) {
        return DesiredConfigBuilder.build(c, Collections.<Application>emptyList(), Collections.<String>emptySet(), null,
                Collections.<String>emptySet(), null, override);
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static HardwarePolicy.Item item(HardwarePolicy.Status s, String key) {
        for (HardwarePolicy.Item i : s.items) if (i.key.equals(key)) return i;
        throw new AssertionError("no item " + key);
    }

    // ---- what the policy stores ----

    @Test
    public void the_policy_keeps_only_known_rules_with_a_valid_value() {
        String stored = DcPolicy.normalize("{\"hardware\":{\"camera\":\"BLOCK\",\"usbDebug\":\"block\",\"usbData\":\"allow\","
                + "\"teleport\":\"block\",\"nfc\":\"maybe\",\"usbStorage\":\"block\",\"tethering\":\"block\"}}");
        assertEquals("{\"hardware\":{\"camera\":\"block\",\"usbData\":\"allow\",\"usbDebug\":\"block\"}}", stored);
        assertNull("nothing valid -> nothing stored", DcPolicy.normalize("{\"hardware\":{\"teleport\":\"block\"}}"));
    }

    @Test
    public void a_policy_without_hardware_rules_builds_the_same_document_as_before() {
        DesiredConfig d = build(cfg(null), null);
        assertNull(d.getHardware());
        assertFalse(d.getPolicies().containsKey("camera"));
        assertFalse("no key is added to the canonical document", DesiredConfigBuilder.canonicalJson(d).contains("hardware"));
    }

    // ---- what the device is sent ----

    @Test
    public void rules_travel_in_the_hardware_section_and_the_camera_also_as_a_toggle_for_older_agents() {
        DesiredConfig d = build(cfg("{\"hardware\":{\"camera\":\"block\",\"usbDebug\":\"block\",\"nfc\":\"allow\"}}"), null);
        assertEquals(map("camera", "block", "usbDebug", "block", "nfc", "allow"), d.getHardware().getRules());
        assertEquals("agents before the hardware section apply the camera through policies.camera",
                Boolean.FALSE, d.getPolicies().get("camera"));
        assertNull(d.getHardware().getRestrictions());
        assertNotEquals("the revision changes, so devices re-apply", build(cfg(null), null).getRevision(), d.getRevision());
    }

    @Test
    public void the_device_override_wins_over_the_policy_and_can_also_flip_the_toggles() {
        Configuration c = cfg("{\"hardware\":{\"camera\":\"block\",\"usbDebug\":\"block\"}}");
        c.setUsbStorage(true);
        c.setDisableScreenshots(false);
        DesiredConfig d = build(c, map("camera", "allow", "usbStorage", "block", "screenshots", "block", "bluetooth", "block",
                "tethering", "block", "nope", "block"));
        assertEquals("allow", d.getHardware().getRules().get("camera"));
        assertEquals("block", d.getHardware().getRules().get("usbDebug"));
        assertEquals(Boolean.TRUE, d.getPolicies().get("camera"));
        assertEquals(Boolean.FALSE, d.getPolicies().get("usbStorage"));
        assertEquals(Boolean.FALSE, d.getPolicies().get("screenshots"));
        assertEquals(Boolean.FALSE, d.getPolicies().get("bluetooth"));
        assertFalse("toggles are not duplicated as rules", d.getHardware().getRules().containsKey("usbStorage"));
        assertNull("tethering keeps its own override", d.getDevice());
        assertNotEquals(build(c, null).getRevision(), d.getRevision());
    }

    @Test
    public void a_blocked_camera_takes_the_camera_icon_out_of_the_kiosk_but_keeps_the_kiosk() {
        Configuration c = cfg("{\"kioskRoles\":[\"phone\",\"camera\"],\"hardware\":{\"camera\":\"block\"}}");
        c.setKioskMode(true);
        assertEquals(Collections.singletonList("phone"), build(c, null).getKiosk().getRoles());
        assertEquals("the device override lets this phone use it again",
                Arrays.asList("phone", "camera"), build(c, map("camera", "allow")).getKiosk().getRoles());
        Configuration open = cfg("{\"kioskRoles\":[\"phone\",\"camera\"]}");
        open.setKioskMode(true);
        assertEquals(Collections.singletonList("phone"), build(open, map("camera", "block")).getKiosk().getRoles());
    }

    // ---- the free "Restricciones de Android" field ----

    @Test
    public void the_free_field_only_sends_restrictions_on_the_validated_list() {
        HardwarePolicy.Restrictions r = HardwarePolicy.parseRestrictions(
                " no_sms, DISALLOW_OUTGOING_CALLS;UserManager.DISALLOW_SAFE_BOOT\nno_sms no_factory_reset,DISALLOW_MADE_UP, rm -rf");
        assertEquals(Arrays.asList("no_outgoing_calls", "no_safe_boot", "no_sms"), r.accepted);
        assertEquals(Collections.singletonList("no_factory_reset"), r.ownOption);
        assertEquals(Arrays.asList("DISALLOW_MADE_UP", "rm", "-rf"), r.unknown);
        assertTrue(HardwarePolicy.parseRestrictions(null).accepted.isEmpty());

        Configuration c = cfg(null);
        c.setRestrictions("no_sms,no_factory_reset,bogus");
        DesiredConfig d = build(c, null);
        assertEquals(Collections.singletonList("no_sms"), d.getHardware().getRestrictions());
        assertNull(d.getHardware().getRules());
        c.setRestrictions("bogus");
        assertNull("nothing valid -> nothing sent", build(c, null).getHardware());
    }

    @Test
    public void every_rule_with_its_own_option_is_refused_in_the_free_field() {
        for (String key : HardwarePolicy.freeRestrictions().keySet()) {
            assertTrue(key, HardwarePolicy.parseRestrictions(key).accepted.contains(key));
        }
        for (String own : Arrays.asList("no_debugging_features", "no_config_tethering", "no_bluetooth", "no_usb_file_transfer")) {
            assertTrue(own, HardwarePolicy.parseRestrictions(own).accepted.isEmpty());
        }
    }

    // ---- status ("Restricciones aplicadas") ----

    @Test
    public void status_shows_what_the_device_reported_for_the_current_document() {
        Configuration c = cfg("{\"hardware\":{\"camera\":\"block\",\"usbData\":\"block\",\"nfc\":\"block\",\"printing\":\"block\"},"
                + "\"device\":{\"tethering\":\"block\"}}");
        c.setUsbStorage(false);
        c.setRestrictions("no_sms,bogus,no_bluetooth");
        Map<String, String> policy = DcPolicy.parse(c.getDcPolicy()).getHardware();
        Map<String, String> override = map("usbDebug", "block");
        DesiredConfig d = build(c, override);
        String detail = "{\"revision\":\"" + d.getRevision() + "\",\"outcomes\":{\"policies.usbStorage\":\"applied\",\"device\":\"applied\","
                + "\"hardware.camera\":\"applied\",\"hardware.usbData\":\"unsupported: device\",\"hardware.nfc\":\"unsupported: sdk-35\","
                + "\"hardware.printing\":\"failed: not-effective\",\"hardware.usbDebug\":\"applied\",\"restrictions.no_sms\":\"applied\"}}";
        HardwarePolicy.Status s = HardwarePolicy.status(policy, override, d, detail, c.getRestrictions());
        assertTrue(s.reported);
        assertEquals(HardwarePolicy.RULES.size(), s.items.size());
        assertEquals("applied", item(s, "camera").outcome);
        assertEquals("block", item(s, "camera").policy);
        assertEquals("unsupported: device", item(s, "usbData").outcome);
        assertEquals("unsupported: sdk-35", item(s, "nfc").outcome);
        assertEquals(35, item(s, "nfc").minSdk);
        assertEquals("failed: not-effective", item(s, "printing").outcome);
        assertEquals("applied", item(s, "usbDebug").outcome);
        assertEquals("block", item(s, "usbDebug").override);
        assertNull(item(s, "usbDebug").policy);
        assertEquals("block", item(s, "usbStorage").desired);
        assertEquals("applied", item(s, "usbStorage").outcome);
        assertEquals("block", item(s, "tethering").desired);
        assertEquals("applied", item(s, "tethering").outcome);
        assertNull("not managed -> nothing to report", item(s, "microphone").desired);
        assertNull(item(s, "microphone").outcome);
        assertEquals(1, s.restrictions.size());
        assertEquals("applied", s.restrictions.get(0).outcome);
        assertEquals(Collections.singletonList("bogus"), s.unknownRestrictions);
        assertEquals(Collections.singletonList("no_bluetooth"), s.ownOptionRestrictions);
    }

    @Test
    public void status_is_pending_until_the_device_reports_this_revision_and_flags_agents_too_old_for_a_rule() {
        Configuration c = cfg("{\"hardware\":{\"camera\":\"block\",\"usbDebug\":\"block\"}}");
        Map<String, String> policy = DcPolicy.parse(c.getDcPolicy()).getHardware();
        DesiredConfig d = build(c, null);
        HardwarePolicy.Status stale = HardwarePolicy.status(policy, null, d,
                "{\"revision\":\"older\",\"outcomes\":{\"hardware.camera\":\"applied\"}}", null);
        assertFalse(stale.reported);
        assertEquals("pending", item(stale, "camera").outcome);
        assertEquals("pending", HardwarePolicy.status(policy, null, d, null, null).items.get(0).outcome);
        assertEquals("pending", HardwarePolicy.status(policy, null, d, "not json", null).items.get(0).outcome);

        // An agent older than the hardware section: it applied the camera toggle and knows nothing of the rest.
        HardwarePolicy.Status old = HardwarePolicy.status(policy, null, d,
                "{\"revision\":\"" + d.getRevision() + "\",\"outcomes\":{\"policies.camera\":\"applied\"}}", null);
        assertEquals("applied", item(old, "camera").outcome);
        assertEquals("unsupported: agent", item(old, "usbDebug").outcome);
    }

    @Test
    public void override_json_round_trips_and_ignores_garbage() {
        assertEquals(map("camera", "block"), HardwarePolicy.parseOverride("{\"camera\":\"block\",\"x\":\"block\",\"nfc\":7}"));
        assertTrue(HardwarePolicy.parseOverride("[1]").isEmpty());
        assertTrue(HardwarePolicy.parseOverride(null).isEmpty());
        assertEquals("{\"camera\":\"block\"}", HardwarePolicy.toJson(map("camera", "block")));
        List<String> keys = new java.util.ArrayList<String>();
        for (HardwarePolicy.Rule r : HardwarePolicy.RULES) keys.add(r.key);
        assertEquals("no duplicated rule", keys.size(), new java.util.HashSet<String>(keys).size());
    }
}
