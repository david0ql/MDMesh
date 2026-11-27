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
        DcPolicy dc = DcPolicy.parse(cfg.getDcPolicy());
        return build(cfg, apps, dc.getNotInKiosk() == null ? Collections.<String>emptySet() : new java.util.HashSet<String>(dc.getNotInKiosk()));
    }

    /**
     * @param apps       the policy's apps plus those of its app groups (see the server's PolicyApps)
     * @param notInKiosk packages installed and allowed but kept out of the kiosk (never the pinned main app)
     */
    public static DesiredConfig build(Configuration cfg, List<Application> apps, Set<String> notInKiosk) {
        return build(cfg, apps, notInKiosk, null);
    }

    /** @param folderBrands the device's folder chain branding, nearest first (see {@link KioskBrand#effective}) */
    public static DesiredConfig build(Configuration cfg, List<Application> apps, Set<String> notInKiosk, List<KioskBrand> folderBrands) {
        return build(cfg, apps, notInKiosk, folderBrands, Collections.<String>emptySet());
    }

    /**
     * @param extraKioskPackages device-level packages to also allow/show in the kiosk — the "show this app on
     *   THIS device's kiosk" override, so one phone can get an app in its kiosk without a per-device policy.
     */
    public static DesiredConfig build(Configuration cfg, List<Application> apps, Set<String> notInKiosk,
                                      List<KioskBrand> folderBrands, Set<String> extraKioskPackages) {
        return build(cfg, apps, notInKiosk, folderBrands, extraKioskPackages, null);
    }

    /**
     * @param dataSharingOverride per-device "share data" (tethering) override, {@code "allow"} / {@code "block"} /
     *   {@code null} (follow the policy). When set it wins over {@link DcPolicy}'s {@code device.tethering}, so the
     *   config.apply the device applies — and the kiosk's "share data" icon — match the per-device toggle. This is the
     *   durable half of the override; an immediate {@code device.dataSharing} command handles the "now".
     */
    public static DesiredConfig build(Configuration cfg, List<Application> apps, Set<String> notInKiosk,
                                      List<KioskBrand> folderBrands, Set<String> extraKioskPackages,
                                      String dataSharingOverride) {
        List<Application> list = apps == null ? Collections.<Application>emptyList() : apps;
        DcPolicy dc = DcPolicy.parse(cfg.getDcPolicy());
        DesiredConfig d = new DesiredConfig();
        d.setConfigurationId(cfg.getId());
        d.setPolicies(policies(cfg));
        d.setKiosk(cfg.isKioskMode() ? kiosk(cfg, list, dc.getKioskRoles(), notInKiosk) : null);
        // Quick settings (Wi-Fi, Bluetooth, brightness) are part of a kiosk unless the policy turns them off.
        if (d.getKiosk() != null && !Boolean.FALSE.equals(dc.getKioskQuickSettings())) d.getKiosk().setQuickSettings(Boolean.TRUE);
        if (d.getKiosk() != null && dc.getPowerPin() != null) {
            // Anti-theft: the power menu is closed; the kiosk's quick settings ask for the PIN to switch off / restart.
            d.getKiosk().setPowerPin(dc.getPowerPin());
            d.getKiosk().getFeatures().setLockButtons(Boolean.TRUE);
            d.getKiosk().setQuickSettings(Boolean.TRUE);
        }
        if (d.getKiosk() != null) d.getKiosk().setHidden(dc.getKioskHidden());
        if (d.getKiosk() != null) {
            KioskBrand brand = KioskBrand.effective(dc.getKioskBrand(), folderBrands);
            if (brand != null) {
                d.getKiosk().getTheme().setLogoUrl(brand.getLogoUrl());
                d.getKiosk().getTheme().setFooterLogoUrl(brand.getFooterLogoUrl());
                d.getKiosk().getTheme().setShowSerial(brand.getShowSerial());
                d.getKiosk().getTheme().setBackgroundUrl(brand.getBackgroundUrl());
                d.getKiosk().getTheme().setSupportPhone(brand.getSupportPhone());
                d.getKiosk().getTheme().setSupportLabel(brand.getSupportLabel());
            }
        }
        DesiredLocation loc = new DesiredLocation();
        loc.setMode(cfg.getRequestUpdates() == RequestUpdatesType.GPS ? "active" : "passive");
        if (dc.getLocationFixSeconds() != null) loc.setFixTimeoutSec(dc.getLocationFixSeconds());
        d.setLocation(loc);
        d.setBrowser(browser(dc));
        d.setApps(appPolicy(dc, list));
        if (dc.getTrackingMinutes() != null) {
            DesiredTracking t = new DesiredTracking();
            t.setIntervalMinutes(dc.getTrackingMinutes());
            d.setTracking(t);
        }
        d.setSystemUpdate(systemUpdate(cfg));
        com.hmdm.rest.json.agent.DesiredDevice rules = dc.getDevice();
        // The anti-theft PIN implies "no factory reset from Settings".
        if (dc.getPowerPin() != null && cfg.isKioskMode() && (rules == null || rules.getFactoryReset() == null)) {
            com.hmdm.rest.json.agent.DesiredDevice r = new com.hmdm.rest.json.agent.DesiredDevice();
            if (rules != null) {
                r.setTethering(rules.getTethering()); r.setGoogleAccounts(rules.getGoogleAccounts());
                r.setAccountDomain(rules.getAccountDomain()); r.setAccountDomains(rules.getAccountDomains());
                r.setFrpAccounts(rules.getFrpAccounts());
            }
            r.setFactoryReset("block");
            rules = r;
        }
        // Per-device "share data" override wins over the policy's tethering (and so over the kiosk's "share data" icon).
        if (dataSharingOverride != null) {
            String t = dataSharingOverride.trim().toLowerCase(java.util.Locale.ROOT);
            if ("allow".equals(t) || "block".equals(t)) {
                if (rules == null) rules = new com.hmdm.rest.json.agent.DesiredDevice();
                rules.setTethering(t);
            }
        }
        d.setDevice(rules);
        if (dc.getWifi() != null) {
            List<com.hmdm.rest.json.agent.DesiredWifi> nets = new ArrayList<com.hmdm.rest.json.agent.DesiredWifi>();
            // Securities an older agent cannot save travel in their own list, which it ignores.
            List<com.hmdm.rest.json.agent.DesiredWifi> advanced = new ArrayList<com.hmdm.rest.json.agent.DesiredWifi>();
            for (DcPolicy.Wifi w : dc.getWifi()) {
                com.hmdm.rest.json.agent.DesiredWifi n = new com.hmdm.rest.json.agent.DesiredWifi();
                n.setSsid(w.getSsid()); n.setPassword(w.getPassword()); n.setSecurity(w.getSecurity()); n.setHidden(w.getHidden());
                n.setAutoConnect(w.getAutoConnect()); n.setPriority(w.getPriority()); n.setProxy(w.getProxy()); n.setIp(w.getIp());
                n.setMac(w.getMac()); n.setMetered(w.getMetered());
                if (w.getEap() != null && DcWifi.isEnterprise(w.getSecurity())) {
                    com.hmdm.rest.json.agent.DesiredWifi.Eap e = new com.hmdm.rest.json.agent.DesiredWifi.Eap();
                    e.setMethod(w.getEap().getMethod()); e.setPhase2(w.getEap().getPhase2()); e.setIdentity(w.getEap().getIdentity());
                    e.setAnonymousIdentity(w.getEap().getAnonymousIdentity()); e.setPassword(w.getEap().getPassword());
                    e.setDomain(w.getEap().getDomain()); e.setAltSubjectMatch(w.getEap().getAltSubjectMatch());
                    e.setCaCertIds(w.getEap().getCaCertIds()); e.setClientCertId(w.getEap().getClientCertId());
                    n.setEap(e);
                }
                (DcWifi.isLegacy(w.getSecurity()) ? nets : advanced).add(n);
            }
            // An empty list still means "the policy manages Wi-Fi" (so dropped networks are removed), as before.
            if (!nets.isEmpty() || advanced.isEmpty()) d.setWifi(nets);
            if (!advanced.isEmpty()) d.setWifiAdvanced(advanced);
        }
        d.setWifiRules(dc.getWifiRules());
        // Device-level kiosk apps: add the phone's own "show in kiosk" packages before the revision is hashed,
        // so the device re-applies and the app appears in its kiosk without touching the shared policy.
        if (d.getKiosk() != null && extraKioskPackages != null && !extraKioskPackages.isEmpty()) {
            List<String> allowed = new ArrayList<String>(
                    d.getKiosk().getAllowedPackages() == null ? Collections.<String>emptyList() : d.getKiosk().getAllowedPackages());
            boolean added = false;
            for (String pkg : extraKioskPackages) {
                if (pkg == null) continue;
                String p = pkg.trim();
                if (!p.isEmpty() && !allowed.contains(p)) { allowed.add(p); added = true; }
            }
            if (added) {
                d.getKiosk().setAllowedPackages(allowed);
                // Extra apps turn a single-pinned kiosk into a launcher grid so they are reachable.
                if (allowed.size() > 1) d.getKiosk().setMode("launcher");
            }
        }
        // Mobile data per app: the policy's own section (a device's override is added by the server's reconciler).
        d.setMobileData(MobileDataPolicy.desired(dc.getMobileData(), null));
        d.setRevision(revision(d));
        return d;
    }

    /** systemUpdateType: 0 device default (absent), 1 immediately, 2 in the from/to window (HH:MM), 3 postpone. */
    static com.hmdm.rest.json.agent.DesiredSystemUpdate systemUpdate(Configuration cfg) {
        com.hmdm.rest.json.agent.DesiredSystemUpdate u = new com.hmdm.rest.json.agent.DesiredSystemUpdate();
        switch (cfg.getSystemUpdateType()) {
            case 1:
                u.setType("automatic");
                return u;
            case 2: {
                Integer from = minutes(cfg.getSystemUpdateFrom()), to = minutes(cfg.getSystemUpdateTo());
                if (from == null || to == null || from.equals(to)) return null;
                u.setType("windowed");
                u.setFromMinutes(from);
                u.setToMinutes(to);
                return u;
            }
            case 3:
                u.setType("postpone");
                return u;
            default:
                return null;
        }
    }

    private static Integer minutes(String hhmm) {
        if (hhmm == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(\\d{1,2}):(\\d{2})\\s*$").matcher(hhmm);
        if (!m.matches()) return null;
        int h = Integer.parseInt(m.group(1)), min = Integer.parseInt(m.group(2));
        return h < 24 && min < 60 ? h * 60 + min : null;
    }

    private static DesiredBrowser browser(DcPolicy dc) {
        if (dc.getBrowser() == null) return null;
        DesiredBrowser b = new DesiredBrowser();
        b.setMode(dc.getBrowser().getMode());
        b.setAllow(dc.getBrowser().getAllow());
        b.setBlock(dc.getBrowser().getBlock());
        String home = dc.getBrowser().getHomeUrl();
        if (home != null) {
            b.setHomeUrl(home);
            // With "only these sites", the company page itself must stay reachable.
            if ("allowlist".equals(b.getMode())) {
                String host = home.replaceFirst("^https?://", "").replaceFirst("[/?#].*$", "");
                List<String> allow = b.getAllow() == null ? new ArrayList<String>() : new ArrayList<String>(b.getAllow());
                if (!host.isEmpty() && !allow.contains(host)) allow.add(host);
                b.setAllow(allow);
            }
        }
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
    private static DesiredKiosk kiosk(Configuration cfg, List<Application> apps, List<String> roles, Set<String> notInKiosk) {
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
            if (notInKiosk != null && notInKiosk.contains(pkg)) continue; // installed and allowed, not in the kiosk
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
        // "Auto" (unset) shows the navigation buttons and the status bar: people in a kiosk still need Back/Home/
        // Recents, the clock and battery, and the pull-down. The policy turns each off with an explicit "No".
        f.setHome(shown(cfg.getKioskHome())); f.setRecents(shown(cfg.getKioskRecents())); f.setNotifications(shown(cfg.getKioskNotifications()));
        f.setSystemInfo(shown(cfg.getKioskSystemInfo())); f.setKeyguard(cfg.getKioskKeyguard()); f.setLockButtons(cfg.getKioskLockButtons());
        k.setFeatures(f);
        k.setExitMode(Boolean.TRUE.equals(cfg.getKioskExit()) ? "visible" : "gesture");
        k.setPassword(cfg.getPassword());
        DesiredKioskTheme t = new DesiredKioskTheme();
        t.setBackgroundColor(cfg.getBackgroundColor()); t.setTextColor(cfg.getTextColor());
        t.setIconSize(cfg.getIconSize() == null ? null : cfg.getIconSize().name());
        k.setTheme(t);
        return k;
    }

    private static Boolean shown(Boolean v) {
        return v == null ? Boolean.TRUE : v;
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
