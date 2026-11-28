package com.hmdm.util;

import com.hmdm.persistence.domain.AppPermissionsType;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.rest.json.agent.DesiredConfig;
import com.hmdm.rest.json.agent.DesiredPermissions;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DesiredPermissionsBuilderTest {

    private static Application app(String pkg, int action) {
        Application a = new Application(); a.setId(pkg.hashCode()); a.setPkg(pkg); a.setAction(action); return a;
    }

    private static Configuration cfg(AppPermissionsType type, String dcPolicy) {
        Configuration c = new Configuration();
        c.setId(7);
        c.setAppPermissions(type);
        c.setDcPolicy(dcPolicy);
        return c;
    }

    private static DesiredPermissions.App of(DesiredPermissions p, String pkg) {
        for (DesiredPermissions.App a : p.getApps()) if (pkg.equals(a.getPackageName())) return a;
        return null;
    }

    private static final List<Application> APPS = Arrays.asList(app("com.acme.pos", 1), app("com.acme.old", 2));

    @Test
    public void nothing_to_say_means_no_section_so_old_documents_keep_their_revision() {
        Configuration c = cfg(AppPermissionsType.GRANTALL, null);
        assertNull(DesiredPermissionsBuilder.build(c, DcPolicy.parse(null), Collections.<Application>emptyList(), null));
        assertNull(DesiredConfigBuilder.build(c, Collections.<Application>emptyList()).getPermissions());
        // Apps alone, with no rule, add nothing either: the document a policy produced before this section is unchanged.
        assertNull(DesiredPermissionsBuilder.build(c, DcPolicy.parse(null), APPS, null));
        assertNull(DesiredConfigBuilder.build(c, APPS).getPermissions());
    }

    @Test
    public void only_packages_with_a_rule_are_listed() {
        String dc = "{\"permissions\":{\"com.acme.pos\":{\"all\":\"grant\"}}}";
        List<Application> apps = Arrays.asList(app("com.acme.pos", 1), app("com.acme.other", 1));
        DesiredPermissions p = DesiredPermissionsBuilder.build(cfg(AppPermissionsType.GRANTALL, dc), DcPolicy.parse(dc), apps, null);
        assertEquals("grant", p.getDefaultPolicy());
        assertEquals(1, p.getApps().size());
        assertEquals("com.acme.pos", p.getApps().get(0).getPackageName());
        assertEquals("grant", p.getApps().get(0).getAll());
    }

    @Test
    public void every_console_option_maps_to_a_real_default_policy() {
        assertEquals("grant", DesiredPermissionsBuilder.defaultPolicy(AppPermissionsType.GRANTALL));
        assertEquals("grant", DesiredPermissionsBuilder.defaultPolicy(null));
        assertEquals("prompt", DesiredPermissionsBuilder.defaultPolicy(AppPermissionsType.ASKALL));
        assertEquals("deny", DesiredPermissionsBuilder.defaultPolicy(AppPermissionsType.DENYALL));
        assertEquals("grant", DesiredPermissionsBuilder.defaultPolicy(AppPermissionsType.DENYLOCATION));
        assertEquals("prompt", DesiredPermissionsBuilder.defaultPolicy(AppPermissionsType.ASKLOCATION));
        // A default other than "grant" is sent even with no app at all.
        DesiredPermissions p = DesiredPermissionsBuilder.build(cfg(AppPermissionsType.DENYALL, null), DcPolicy.parse(null), null, null);
        assertEquals("deny", p.getDefaultPolicy());
        assertNull(p.getApps());
    }

    @Test
    public void deny_location_denies_the_three_location_permissions_of_the_policys_apps() {
        DesiredPermissions p = DesiredPermissionsBuilder.build(cfg(AppPermissionsType.DENYLOCATION, null), DcPolicy.parse(null), APPS, null);
        DesiredPermissions.App a = of(p, "com.acme.pos");
        assertNull(a.getAll());
        assertEquals("deny", a.getPermissions().get("locationFine"));
        assertEquals("deny", a.getPermissions().get("locationCoarse"));
        assertEquals("deny", a.getPermissions().get("locationBackground"));
    }

    @Test
    public void ask_only_for_location_grants_the_rest_app_by_app_and_leaves_location_to_the_user() {
        DesiredPermissions p = DesiredPermissionsBuilder.build(cfg(AppPermissionsType.ASKLOCATION, null), DcPolicy.parse(null), APPS, null);
        assertEquals("prompt", p.getDefaultPolicy());
        DesiredPermissions.App a = of(p, "com.acme.pos");
        assertEquals("grant", a.getAll());
        assertEquals("prompt", a.getPermissions().get("locationFine"));
    }

    @Test
    public void policy_rules_win_over_the_option_and_device_rules_win_over_the_policy() {
        String dc = "{\"permissions\":{\"com.acme.pos\":{\"all\":\"grant\",\"permissions\":{\"camera\":\"deny\",\"locationFine\":\"grant\"},"
                + "\"special\":[\"allFiles\"]},\"com.other.app\":{\"permissions\":{\"microphone\":\"deny\"}}}}";
        Configuration c = cfg(AppPermissionsType.DENYLOCATION, dc);
        DesiredPermissions policy = DesiredPermissionsBuilder.build(c, DcPolicy.parse(dc), APPS, null);
        DesiredPermissions.App a = of(policy, "com.acme.pos");
        assertEquals("grant", a.getAll());
        assertEquals("deny", a.getPermissions().get("camera"));
        assertEquals("the policy's own rule beats the option", "grant", a.getPermissions().get("locationFine"));
        assertEquals("the option still covers what the policy does not name", "deny", a.getPermissions().get("locationCoarse"));
        assertEquals(Collections.singletonList("allFiles"), a.getSpecial());
        assertEquals("a package outside the policy's apps can carry rules", "deny", of(policy, "com.other.app").getPermissions().get("microphone"));

        Map<String, AppPermissionRules> device = AppPermissionRules.parseMap(
                "{\"com.acme.pos\":{\"permissions\":{\"camera\":\"grant\"},\"special\":[\"overlay\"]}}");
        DesiredPermissions eff = DesiredPermissionsBuilder.build(c, DcPolicy.parse(dc), APPS, device);
        DesiredPermissions.App e = of(eff, "com.acme.pos");
        assertEquals("grant", e.getPermissions().get("camera"));
        assertEquals("grant", e.getAll());
        assertEquals(Arrays.asList("allFiles", "overlay"), e.getSpecial());
    }

    @Test
    public void device_rules_change_the_revision_only_for_that_device() {
        Configuration c = cfg(AppPermissionsType.GRANTALL, null);
        DesiredConfig base = DesiredConfigBuilder.build(c, APPS);
        String revision = base.getRevision();
        assertNull(base.getPermissions());
        DesiredConfig same = DesiredPermissionsBuilder.withDeviceOverrides(DesiredConfigBuilder.build(c, APPS), c, APPS,
                Collections.<String, AppPermissionRules>emptyMap());
        assertEquals(revision, same.getRevision());
        DesiredConfig over = DesiredPermissionsBuilder.withDeviceOverrides(DesiredConfigBuilder.build(c, APPS), c, APPS,
                AppPermissionRules.parseMap("{\"com.acme.pos\":{\"all\":\"grant\"}}"));
        assertNotEquals(revision, over.getRevision());
        assertEquals("grant", of(over.getPermissions(), "com.acme.pos").getAll());
        assertEquals(DesiredConfigBuilder.revision(over), over.getRevision());
    }

    @Test
    public void rules_are_cleaned_unknown_keys_values_and_packages_are_dropped() {
        Map<String, AppPermissionRules> m = AppPermissionRules.parseMap(
                "{\"com.acme.pos\":{\"all\":\"GRANT\",\"permissions\":{\"camera\":\"deny\",\"bogus\":\"grant\",\"sms\":\"always\"},"
                        + "\"special\":[\"overlay\",\"root\"]},\"not a package\":{\"all\":\"grant\"},\"com.empty.app\":{\"all\":\"maybe\"}}");
        assertEquals(1, m.size());
        AppPermissionRules r = m.get("com.acme.pos");
        assertEquals("grant", r.getAll());
        assertEquals(Collections.singletonMap("camera", "deny"), r.getPermissions());
        assertEquals(Collections.singletonList("overlay"), r.getSpecial());
        assertTrue(AppPermissionRules.parseMap("[1,2]").isEmpty());
        assertTrue(AppPermissionRules.parseMap(null).isEmpty());
        assertNull(AppPermissionRules.toJson(Collections.<String, AppPermissionRules>emptyMap()));
    }

    @Test
    public void the_policy_json_keeps_its_permission_rules_through_normalize() {
        String stored = DcPolicy.normalize("{\"permissions\":{\"com.acme.pos\":{\"all\":\"grant\",\"permissions\":{\"camera\":\"deny\"}},"
                + "\"com.x.y\":{\"all\":\"nope\"}}}");
        assertNotNull(stored);
        DcPolicy p = DcPolicy.parse(stored);
        assertEquals(1, p.getPermissions().size());
        assertEquals("deny", p.getPermissions().get("com.acme.pos").getPermissions().get("camera"));
        assertFalse(p.isEmpty());
        assertNull(DcPolicy.normalize("{\"permissions\":{\"com.x.y\":{\"all\":\"nope\"}}}"));
    }
}
