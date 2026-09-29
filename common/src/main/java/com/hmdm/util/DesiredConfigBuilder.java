package com.hmdm.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.RequestUpdatesType;
import com.hmdm.rest.json.agent.DesiredAppPolicy;
import com.hmdm.rest.json.agent.DesiredBrowser;
import com.hmdm.rest.json.agent.DesiredConfig;
import com.hmdm.rest.json.agent.DesiredKiosk;
import com.hmdm.rest.json.agent.DesiredKioskFeatures;
import com.hmdm.rest.json.agent.DesiredKioskTheme;
import com.hmdm.rest.json.agent.DesiredLocation;
import com.hmdm.rest.json.agent.DesiredTracking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The ONLY place a {@link Configuration} row becomes a {@code config.apply} desired-state document.
 * Pure and deterministic: same inputs -&gt; same canonical JSON -&gt; same revision, on every server node,
 * so a device's reported {@code appliedConfigRevision} can be compared without storing anything.
 *
 * <p>Note: {@link Configuration#getMainAppId()} is an {@code applicationVersions.id}, NOT an
 * {@code applications.id} (Liquibase remapped the column; {@code recheckConfigurationMainApplication}
 * sets it from {@code configurationApplications.applicationVersionId}). The main app is therefore the
 * configuration app whose {@link Application#getUsedVersionId()} equals it.</p>
 */
public final class DesiredConfigBuilder {
    public static final String COMMAND_TYPE = "config.apply";
    public static final String CAPABILITY = "device.configApply";
    public static final String POLICY_PREFIX = "policies.";
    public static final String KEY_KIOSK = "kiosk";
    public static final String KEY_LOCATION = "location";
    private static final int ACTION_INSTALL = 1;

    private static final ObjectMapper PLAIN = new ObjectMapper();

    private DesiredConfigBuilder() {}

    public static DesiredConfig build(Configuration cfg, List<Application> apps) {
        List<Application> list = apps == null ? Collections.<Application>emptyList() : apps;
        DcPolicy dc = DcPolicy.parse(cfg.getDcPolicy());
        DesiredConfig d = new DesiredConfig();
        d.setConfigurationId(cfg.getId());
        d.setPolicies(policies(cfg));
        d.setKiosk(cfg.isKioskMode() ? kiosk(cfg, list, dc.getKioskRoles()) : null);
        if (d.getKiosk() != null && Boolean.TRUE.equals(dc.getKioskQuickSettings())) d.getKiosk().setQuickSettings(Boolean.TRUE);
        DesiredLocation loc = new DesiredLocation();
        loc.setMode(cfg.getRequestUpdates() == RequestUpdatesType.GPS ? "active" : "passive");
        d.setLocation(loc);
        d.setBrowser(browser(dc));
        d.setApps(appPolicy(dc, list));
        if (dc.getTrackingMinutes() != null) {
            DesiredTracking t = new DesiredTracking();
            t.setIntervalMinutes(dc.getTrackingMinutes());
            d.setTracking(t);
        }
        d.setRevision(revision(d));
        return d;
    }

    private static DesiredBrowser browser(DcPolicy dc) {
        if (dc.getBrowser() == null) return null;
        DesiredBrowser b = new DesiredBrowser();
        b.setMode(dc.getBrowser().getMode());
        b.setAllow(dc.getBrowser().getAllow());
        b.setBlock(dc.getBrowser().getBlock());
        return b;
    }

    /** The configuration's installed apps are always allowed; the policy adds packages and functions. */
    private static DesiredAppPolicy appPolicy(DcPolicy dc, List<Application> apps) {
        if (dc.getApps() == null) return null;
        DesiredAppPolicy a = new DesiredAppPolicy();
        a.setMode(dc.getApps().getMode());
        Set<String> allowed = new TreeSet<String>();
        for (Application app : apps) {
            if (app == null || app.getAction() != ACTION_INSTALL || app.getPkg() == null) continue;
            String pkg = app.getPkg().trim();
            if (!pkg.isEmpty()) allowed.add(pkg);
        }
        if (dc.getApps().getAllowed() != null) allowed.addAll(dc.getApps().getAllowed());
        a.setAllowed(allowed.isEmpty() ? null : new ArrayList<String>(allowed));
        a.setRoles(dc.getApps().getRoles());
        a.setHidePlayStore(dc.getApps().getHidePlayStore());
        return a;
    }

    private static Map<String, Boolean> policies(Configuration cfg) {
        Map<String, Boolean> p = new TreeMap<String, Boolean>();
        putIfManaged(p, "wifi", cfg.getWifi());
        putIfManaged(p, "bluetooth", cfg.getBluetooth());
        putIfManaged(p, "usbStorage", cfg.getUsbStorage());
        putIfManaged(p, "screenshots", cfg.getDisableScreenshots() == null ? null : !cfg.getDisableScreenshots());
        return p;
    }

    private static void putIfManaged(Map<String, Boolean> p, String key, Boolean v) {
        if (v != null) p.put(key, v);
    }

    /**
     * Builds the kiosk block. The main (pinned) app is matched by application VERSION id:
     * {@code cfg.mainAppId == app.usedVersionId}, never by {@code app.id}.
     */
    private static DesiredKiosk kiosk(Configuration cfg, List<Application> apps, List<String> roles) {
        String mainPkg = null;
        for (Application a : apps) {
            if (a == null || a.getPkg() == null || a.getPkg().trim().isEmpty() || a.getAction() != ACTION_INSTALL) continue;
            if (cfg.getMainAppId() != null && cfg.getMainAppId().equals(a.getUsedVersionId())) mainPkg = a.getPkg().trim();
        }
        // Dedupe by package name (not row id): another Application row can carry the same pkg as
        // the main app under a different id, and must not appear twice in allowedPackages or
        // spuriously flip mode from "single" to "launcher".
        Set<String> distinctOthers = new TreeSet<String>();
        for (Application a : apps) {
            if (a == null || a.getPkg() == null || a.getPkg().trim().isEmpty() || a.getAction() != ACTION_INSTALL) continue;
            String pkg = a.getPkg().trim();
            if (mainPkg != null && mainPkg.equals(pkg)) continue;
            distinctOthers.add(pkg);
        }
        List<String> allowed = new ArrayList<String>(distinctOthers);
        if (mainPkg != null) allowed.add(0, mainPkg);

        DesiredKiosk k = new DesiredKiosk();
        // Functions (phone, browser, …) are apps the user opens from the kiosk home, so they make it a launcher.
        boolean hasRoles = roles != null && !roles.isEmpty();
        k.setMode(mainPkg != null && allowed.size() == 1 && !hasRoles ? "single" : "launcher");
        k.setRoles(hasRoles ? roles : null);
        k.setAllowedPackages(allowed);
        k.setPinPackage(mainPkg);
        DesiredKioskFeatures f = new DesiredKioskFeatures();
        f.setHome(cfg.getKioskHome()); f.setRecents(cfg.getKioskRecents()); f.setNotifications(cfg.getKioskNotifications());
        f.setSystemInfo(cfg.getKioskSystemInfo()); f.setKeyguard(cfg.getKioskKeyguard()); f.setLockButtons(cfg.getKioskLockButtons());
        k.setFeatures(f);
        k.setExitMode(Boolean.TRUE.equals(cfg.getKioskExit()) ? "visible" : "gesture");
        k.setPassword(cfg.getPassword());
        DesiredKioskTheme t = new DesiredKioskTheme();
        t.setBackgroundColor(cfg.getBackgroundColor()); t.setTextColor(cfg.getTextColor());
        t.setIconSize(cfg.getIconSize() == null ? null : cfg.getIconSize().name());
        k.setTheme(t);
        return k;
    }

    /** Recursively sorts map keys and drops null values so nested objects canonicalise too. */
    private static Object sorted(Object v) {
        if (v instanceof Map) {
            TreeMap<String, Object> m = new TreeMap<String, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (e.getValue() != null) m.put(String.valueOf(e.getKey()), sorted(e.getValue()));
            }
            return m;
        }
        if (v instanceof List) {
            List<Object> l = new ArrayList<Object>();
            for (Object o : (List<?>) v) l.add(sorted(o));
            return l;
        }
        return v;
    }

    /** Canonical JSON of the document WITHOUT the revision field. */
    public static String canonicalJson(DesiredConfig doc) {
        try {
            Map<?, ?> asMap = PLAIN.convertValue(doc, Map.class);
            asMap.remove("revision");
            return PLAIN.writeValueAsString(sorted(asMap));
        } catch (Exception e) {
            throw new IllegalStateException("cannot canonicalise desired config", e);
        }
    }

    public static String revision(DesiredConfig doc) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(canonicalJson(doc).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Full JSON (with revision) - the {@code agentCommand.payload} string. */
    public static String toPayloadJson(DesiredConfig doc) {
        try { return PLAIN.writeValueAsString(doc); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
