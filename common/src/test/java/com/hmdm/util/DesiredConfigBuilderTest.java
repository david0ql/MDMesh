package com.hmdm.util;

import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.IconSize;
import com.hmdm.persistence.domain.RequestUpdatesType;
import com.hmdm.rest.json.agent.DesiredConfig;
import org.junit.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Scanner;
import static org.junit.Assert.*;

public class DesiredConfigBuilderTest {

    /** Application ids and version ids are deliberately distinct: mainAppId is an applicationVersions.id. */
    private static Application app(int id, int versionId, String pkg, int action) {
        Application a = new Application(); a.setId(id); a.setUsedVersionId(versionId); a.setPkg(pkg); a.setAction(action); return a;
    }

    private static Configuration kioskConfig() {
        Configuration c = new Configuration();
        c.setId(12); c.setWifi(true); c.setBluetooth(false); c.setUsbStorage(null); c.setDisableScreenshots(true);
        c.setKioskMode(true); c.setMainAppId(505); c.setKioskExit(true); c.setKioskHome(true); c.setKioskRecents(false);
        c.setPassword("s3cret"); c.setBackgroundColor("#000000"); c.setTextColor("#ffffff"); c.setIconSize(IconSize.LARGE);
        c.setRequestUpdates(RequestUpdatesType.GPS);
        return c;
    }

    private static String resource(String name) {
        InputStream in = DesiredConfigBuilderTest.class.getResourceAsStream("/contract/v1/" + name);
        return new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A").next().trim();
    }

    @Test
    public void tri_state_policies_only_include_managed_keys_and_screenshots_is_inverted() {
        DesiredConfig d = DesiredConfigBuilder.build(kioskConfig(), Collections.emptyList());
        assertEquals(Boolean.TRUE, d.getPolicies().get("wifi"));
        assertEquals(Boolean.FALSE, d.getPolicies().get("bluetooth"));
        assertFalse("null usbStorage = not managed", d.getPolicies().containsKey("usbStorage"));
        assertEquals("disableScreenshots=true -> screenshots=false", Boolean.FALSE, d.getPolicies().get("screenshots"));
    }

    @Test
    public void kiosk_single_when_main_app_is_the_only_install_app() {
        DesiredConfig d = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(5, 505, "com.acme.pos", 1), app(9, 909, "com.acme.old", 2)));
        assertEquals("single", d.getKiosk().getMode());
        assertEquals("com.acme.pos", d.getKiosk().getPinPackage());
        assertEquals(Collections.singletonList("com.acme.pos"), d.getKiosk().getAllowedPackages());
        assertEquals("visible", d.getKiosk().getExitMode());
        assertEquals("LARGE", d.getKiosk().getTheme().getIconSize());
        assertEquals("active", d.getLocation().getMode());
    }

    @Test
    public void kiosk_launcher_when_several_apps_and_main_app_first() {
        DesiredConfig d = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(1, 101, "com.b", 1), app(5, 505, "com.acme.pos", 1)));
        assertEquals("launcher", d.getKiosk().getMode());
        assertEquals(Arrays.asList("com.acme.pos", "com.b"), d.getKiosk().getAllowedPackages());
    }

    @Test
    public void duplicate_package_rows_for_the_main_app_are_deduplicated() {
        DesiredConfig d = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(5, 505, "com.acme.pos", 1), app(6, 606, "com.acme.pos", 1)));
        assertEquals(Collections.singletonList("com.acme.pos"), d.getKiosk().getAllowedPackages());
        assertEquals("single", d.getKiosk().getMode());
    }

    @Test
    public void application_id_equal_to_mainAppId_is_not_the_main_app() {
        // App 505 (id) has version 777; the main app is the row whose usedVersionId is 505.
        DesiredConfig d = DesiredConfigBuilder.build(kioskConfig(),
                Arrays.asList(app(505, 777, "com.decoy", 1), app(5, 505, "com.acme.pos", 1)));
        assertEquals("com.acme.pos", d.getKiosk().getPinPackage());
        assertEquals(Arrays.asList("com.acme.pos", "com.decoy"), d.getKiosk().getAllowedPackages());

        DesiredConfig onlyDecoy = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(505, 777, "com.decoy", 1)));
        assertNull("id match without version match must not pin", onlyDecoy.getKiosk().getPinPackage());
        assertEquals("launcher", onlyDecoy.getKiosk().getMode());
    }

    @Test
    public void kiosk_absent_when_kioskMode_off() {
        Configuration c = kioskConfig(); c.setKioskMode(false);
        DesiredConfig d = DesiredConfigBuilder.build(c, Collections.emptyList());
        assertNull(d.getKiosk());
        assertFalse(DesiredConfigBuilder.canonicalJson(d).contains("kiosk"));
    }

    @Test
    public void revision_is_stable_and_independent_of_field_order_and_revision_field() {
        DesiredConfig a = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(5, 505, "com.acme.pos", 1)));
        DesiredConfig b = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(5, 505, "com.acme.pos", 1)));
        assertEquals(a.getRevision(), b.getRevision());
        assertEquals(64, a.getRevision().length());
        a.setRevision("tampered");
        assertEquals(b.getRevision(), DesiredConfigBuilder.revision(a));
    }

    /** GOLDEN: any change to canonicalisation changes every device's revision fleet-wide. Update deliberately. */
    @Test
    public void golden_canonical_json_and_revision() {
        DesiredConfig d = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(5, 505, "com.acme.pos", 1)));
        assertEquals(resource("desired-config-kiosk.json"), DesiredConfigBuilder.canonicalJson(d));
        assertEquals(resource("desired-config-kiosk.sha256"), d.getRevision());
    }

    @Test
    public void no_dc_policy_leaves_the_document_and_revision_unchanged() {
        Configuration c = kioskConfig();
        String before = DesiredConfigBuilder.build(c, Arrays.asList(app(5, 505, "com.acme.pos", 1))).getRevision();
        c.setDcPolicy("   ");
        DesiredConfig d = DesiredConfigBuilder.build(c, Arrays.asList(app(5, 505, "com.acme.pos", 1)));
        assertEquals(before, d.getRevision());
        assertNull(d.getBrowser()); assertNull(d.getApps()); assertNull(d.getTracking()); assertNull(d.getKiosk().getRoles());
    }

    @Test
    public void kiosk_roles_make_a_launcher_and_travel_to_the_agent() {
        Configuration c = kioskConfig();
        c.setDcPolicy("{\"kioskRoles\":[\"browser\",\"PHONE\",\"teleport\"]}");
        DesiredConfig d = DesiredConfigBuilder.build(c, Arrays.asList(app(5, 505, "com.acme.pos", 1)));
        assertEquals("functions are opened from the kiosk home", "launcher", d.getKiosk().getMode());
        assertEquals("known roles only, canonical order", Arrays.asList("phone", "browser"), d.getKiosk().getRoles());
        assertEquals("com.acme.pos", d.getKiosk().getPinPackage());
    }

    @Test
    public void browser_app_policy_and_tracking_sections() {
        Configuration c = kioskConfig();
        c.setDcPolicy("{\"browser\":{\"mode\":\"allowlist\",\"allow\":[\" amovil.com.co \",\"\",\"*.gov.co\"]},"
                + "\"apps\":{\"mode\":\"allowlist\",\"allowed\":[\"com.whatsapp\",\"not a pkg\"],\"roles\":[\"phone\"],\"hidePlayStore\":true},"
                + "\"trackingMinutes\":5}");
        DesiredConfig d = DesiredConfigBuilder.build(c, Arrays.asList(app(5, 505, "com.acme.pos", 1), app(9, 909, "com.acme.old", 2)));
        assertEquals("allowlist", d.getBrowser().getMode());
        assertEquals(Arrays.asList("amovil.com.co", "*.gov.co"), d.getBrowser().getAllow());
        assertEquals("allowlist", d.getApps().getMode());
        assertEquals("config install apps + extra allowed; removed apps and junk are not",
                Arrays.asList("com.acme.pos", "com.whatsapp"), d.getApps().getAllowed());
        assertEquals(Collections.singletonList("phone"), d.getApps().getRoles());
        assertEquals(Boolean.TRUE, d.getApps().getHidePlayStore());
        assertEquals(Integer.valueOf(5), d.getTracking().getIntervalMinutes());
    }

    @Test
    public void invalid_dc_policy_is_ignored_not_fatal() {
        Configuration c = kioskConfig();
        c.setDcPolicy("{not json");
        DesiredConfig d = DesiredConfigBuilder.build(c, Collections.<Application>emptyList());
        assertNull(d.getBrowser()); assertNull(d.getApps()); assertNull(d.getTracking());
        c.setDcPolicy("{\"trackingMinutes\":0,\"browser\":{\"mode\":\"sometimes\"}}");
        d = DesiredConfigBuilder.build(c, Collections.<Application>emptyList());
        assertNull("0 minutes is not a trail", d.getTracking());
        assertNull("unknown browser mode = not managed", d.getBrowser());
    }

    @Test
    public void system_update_policy() {
        Configuration c = kioskConfig();
        assertNull("default: not sent (revision unchanged)", DesiredConfigBuilder.build(c, Collections.<Application>emptyList()).getSystemUpdate());
        c.setSystemUpdateType(1);
        assertEquals("automatic", DesiredConfigBuilder.build(c, Collections.<Application>emptyList()).getSystemUpdate().getType());
        c.setSystemUpdateType(2); c.setSystemUpdateFrom("22:00"); c.setSystemUpdateTo("04:30");
        com.hmdm.rest.json.agent.DesiredSystemUpdate u = DesiredConfigBuilder.build(c, Collections.<Application>emptyList()).getSystemUpdate();
        assertEquals("windowed", u.getType());
        assertEquals(Integer.valueOf(1320), u.getFromMinutes());
        assertEquals(Integer.valueOf(270), u.getToMinutes());
        c.setSystemUpdateTo("bad");
        assertNull("a broken window is not sent", DesiredConfigBuilder.build(c, Collections.<Application>emptyList()).getSystemUpdate());
    }

    @Test
    public void not_in_kiosk_apps_stay_installed_and_allowed_but_out_of_the_kiosk() {
        Configuration c = kioskConfig();
        c.setDcPolicy("{\"apps\":{\"mode\":\"allowlist\"},\"notInKiosk\":[\"com.acme.tool\",\"com.acme.pos\"]}");
        DesiredConfig d = DesiredConfigBuilder.build(c, Arrays.asList(app(5, 505, "com.acme.pos", 1), app(7, 707, "com.acme.tool", 1), app(8, 808, "com.acme.chat", 1)));
        // The pinned main app is never taken out; the tool is.
        assertEquals(Arrays.asList("com.acme.pos", "com.acme.chat"), d.getKiosk().getAllowedPackages());
        assertTrue(d.getApps().getAllowed().contains("com.acme.tool"));
        // Group apps passed as not-in-kiosk by the server resolver.
        DesiredConfig g = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(5, 505, "com.acme.pos", 1), app(9, 909, "com.acme.group", 1)),
                new java.util.HashSet<String>(Arrays.asList("com.acme.group")));
        assertEquals(Arrays.asList("com.acme.pos"), g.getKiosk().getAllowedPackages());
    }

    @Test
    public void dc_policy_keeps_app_groups_clean() {
        String n = DcPolicy.normalize("{\"appGroups\":[{\"id\":3,\"kiosk\":true},{\"id\":3},{\"id\":-1},{\"id\":4,\"kiosk\":false}],\"notInKiosk\":[\"com.a.b\",\"not a pkg\"]}");
        assertEquals("{\"appGroups\":[{\"id\":3,\"kiosk\":true},{\"id\":4}],\"notInKiosk\":[\"com.a.b\"]}", n);
    }

    @Test
    public void kiosk_brand_nearest_folder_wins_per_field_then_the_policy() {
        Configuration c = kioskConfig();
        c.setDcPolicy("{\"kioskBrand\":{\"logoUrl\":\"https://x/policy-top.png\",\"footerLogoUrl\":\"https://x/policy-foot.png\",\"showSerial\":true}}");
        java.util.List<Application> apps = Arrays.asList(app(5, 505, "com.acme.pos", 1));
        DesiredConfig plain = DesiredConfigBuilder.build(c, apps);
        assertEquals("https://x/policy-top.png", plain.getKiosk().getTheme().getLogoUrl());
        assertEquals(Boolean.TRUE, plain.getKiosk().getTheme().getShowSerial());
        // Nearest folder sets only the top logo; its parent sets both: top from the nearest, footer from the parent.
        DesiredConfig d = DesiredConfigBuilder.build(c, apps, Collections.<String>emptySet(), Arrays.asList(
                KioskBrand.parse("{\"logoUrl\":\"https://x/bogota.png\"}"),
                KioskBrand.parse("{\"logoUrl\":\"https://x/colombia.png\",\"footerLogoUrl\":\"https://x/colombia-foot.png\"}")));
        assertEquals("https://x/bogota.png", d.getKiosk().getTheme().getLogoUrl());
        assertEquals("https://x/colombia-foot.png", d.getKiosk().getTheme().getFooterLogoUrl());
        assertEquals(Boolean.TRUE, d.getKiosk().getTheme().getShowSerial());
        assertNotEquals(plain.getRevision(), d.getRevision());
        // Only http(s) URLs survive; nothing set = no branding and the same revision as before the feature.
        assertNull(KioskBrand.parse("{\"logoUrl\":\"javascript:alert(1)\"}"));
        Configuration none = kioskConfig();
        assertNull(DesiredConfigBuilder.build(none, apps).getKiosk().getTheme().getLogoUrl());
    }

    @Test
    public void kiosk_support_line_and_wallpaper_reach_the_theme_and_folders_override() {
        Configuration c = kioskConfig();
        c.setDcPolicy("{\"kioskBrand\":{\"supportPhone\":\"+57 (300) 123-4567\",\"supportLabel\":\" Mesa de ayuda \",\"backgroundUrl\":\"https://x/bg.png\"}}");
        java.util.List<Application> apps = Arrays.asList(app(5, 505, "com.acme.pos", 1));
        DesiredConfig d = DesiredConfigBuilder.build(c, apps);
        assertEquals("+573001234567", d.getKiosk().getTheme().getSupportPhone());
        assertEquals("Mesa de ayuda", d.getKiosk().getTheme().getSupportLabel());
        assertEquals("https://x/bg.png", d.getKiosk().getTheme().getBackgroundUrl());
        DesiredConfig f = DesiredConfigBuilder.build(c, apps, Collections.<String>emptySet(),
                Arrays.asList(KioskBrand.parse("{\"supportPhone\":\"018000123\"}")));
        assertEquals("018000123", f.getKiosk().getTheme().getSupportPhone());
        assertNull(f.getKiosk().getTheme().getSupportLabel()); // the folder's number comes with the folder's (empty) label
        assertNull(KioskBrand.parse("{\"supportPhone\":\"llamar ya\"}"));
    }

    @Test
    public void kiosk_shows_navigation_status_bar_and_quick_settings_unless_turned_off() {
        Configuration c = new Configuration();
        c.setId(1); c.setKioskMode(true); c.setRequestUpdates(RequestUpdatesType.GPS);
        DesiredConfig d = DesiredConfigBuilder.build(c, Arrays.asList(app(5, 505, "com.acme.pos", 1)));
        assertEquals(Boolean.TRUE, d.getKiosk().getFeatures().getHome());
        assertEquals(Boolean.TRUE, d.getKiosk().getFeatures().getRecents());
        assertEquals(Boolean.TRUE, d.getKiosk().getFeatures().getNotifications());
        assertEquals(Boolean.TRUE, d.getKiosk().getFeatures().getSystemInfo());
        assertEquals(Boolean.TRUE, d.getKiosk().getQuickSettings());
        c.setKioskRecents(false); c.setKioskNotifications(false); c.setDcPolicy("{\"kioskQuickSettings\":false}");
        DesiredConfig off = DesiredConfigBuilder.build(c, Arrays.asList(app(5, 505, "com.acme.pos", 1)));
        assertEquals(Boolean.FALSE, off.getKiosk().getFeatures().getRecents());
        assertEquals(Boolean.FALSE, off.getKiosk().getFeatures().getNotifications());
        assertNull(off.getKiosk().getQuickSettings());
    }

    @Test
    public void power_pin_closes_the_power_menu_and_offers_the_quick_settings() {
        Configuration c = kioskConfig();
        c.setDcPolicy("{\"powerPin\":\"4821\",\"kioskQuickSettings\":false}");
        DesiredConfig d = DesiredConfigBuilder.build(c, Arrays.asList(app(5, 505, "com.acme.pos", 1)));
        assertEquals("4821", d.getKiosk().getPowerPin());
        assertEquals(Boolean.TRUE, d.getKiosk().getFeatures().getLockButtons());
        assertEquals(Boolean.TRUE, d.getKiosk().getQuickSettings()); // the PIN prompt lives there
        assertNull(DcPolicy.parse("{\"powerPin\":\"12\"}").getPowerPin());
        assertNull(DcPolicy.parse("{\"powerPin\":\"abcd\"}").getPowerPin());
        assertNull(DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(5, 505, "com.acme.pos", 1))).getKiosk().getPowerPin());
    }
}
