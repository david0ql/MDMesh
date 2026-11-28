package com.hmdm.util;

import com.hmdm.persistence.domain.AppPermissionsType;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.rest.json.agent.DesiredConfig;
import com.hmdm.rest.json.agent.DesiredPermissions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <p>The {@code permissions} section of a {@code config.apply} document, from three layers (later wins):</p>
 * <ol>
 *     <li>the configuration's "Permisos de apps" option ({@code configurations.appPermissions}): the default answer
 *     Android gives to a permission request, plus the location variants for the policy's apps;</li>
 *     <li>the policy's per-app rules ({@code dcPolicy.permissions});</li>
 *     <li>the device's own rules ({@code device_permission_override}).</li>
 * </ol>
 * <p>Absent (null) when nothing is set — default "grant" and no rule anywhere: the agent then keeps its baseline (grant
 * on request) and the document, and so its revision, is exactly what it was before this section existed. Only the
 * packages that carry a rule are listed. Pure and deterministic, like {@link DesiredConfigBuilder}.</p>
 */
public final class DesiredPermissionsBuilder {
    private static final int ACTION_INSTALL = 1;

    private DesiredPermissionsBuilder() {}

    /** What Android answers to a permission request under this configuration option. */
    public static String defaultPolicy(AppPermissionsType type) {
        if (type == null) return AppPermissionRules.GRANT;
        switch (type) {
            case ASKALL:
            case ASKLOCATION:   // everything else is granted app by app, so only location is left to ask
                return AppPermissionRules.PROMPT;
            case DENYALL:
                return AppPermissionRules.DENY;
            default:
                return AppPermissionRules.GRANT;
        }
    }

    /** The rules the configuration option alone implies for each of the policy's apps (null = none). */
    static AppPermissionRules optionRules(AppPermissionsType type) {
        if (type != AppPermissionsType.ASKLOCATION && type != AppPermissionsType.DENYLOCATION) return null;
        AppPermissionRules r = new AppPermissionRules();
        boolean ask = type == AppPermissionsType.ASKLOCATION;
        if (ask) r.setAll(AppPermissionRules.GRANT);
        Map<String, String> p = new TreeMap<String, String>();
        for (String g : AppPermissionRules.LOCATION_GROUPS) p.put(g, ask ? AppPermissionRules.PROMPT : AppPermissionRules.DENY);
        r.setPermissions(p);
        return r;
    }

    /**
     * @param apps            the policy's apps (plus those of its app groups)
     * @param deviceOverrides the device's own rules by package, or null
     */
    public static DesiredPermissions build(Configuration cfg, DcPolicy dc, List<Application> apps,
                                           Map<String, AppPermissionRules> deviceOverrides) {
        String defaultPolicy = defaultPolicy(cfg.getAppPermissions());
        AppPermissionRules option = optionRules(cfg.getAppPermissions());
        Map<String, AppPermissionRules> policyRules = dc == null ? null : dc.getPermissions();

        // Every package with a rule: the policy's installed apps when the option implies one, and any package a rule
        // names (a preinstalled or user-installed app can be given permissions too).
        Map<String, AppPermissionRules> byPackage = new TreeMap<String, AppPermissionRules>();
        if (apps != null && option != null) {
            for (Application a : apps) {
                if (a == null || a.getAction() != ACTION_INSTALL || a.getPkg() == null) continue;
                String pkg = a.getPkg().trim();
                if (!pkg.isEmpty()) byPackage.put(pkg, option);
            }
        }
        if (policyRules != null) {
            for (Map.Entry<String, AppPermissionRules> e : policyRules.entrySet()) {
                byPackage.put(e.getKey(), AppPermissionRules.merge(byPackage.get(e.getKey()), e.getValue()));
            }
        }
        if (deviceOverrides != null) {
            for (Map.Entry<String, AppPermissionRules> e : deviceOverrides.entrySet()) {
                byPackage.put(e.getKey(), AppPermissionRules.merge(byPackage.get(e.getKey()), e.getValue()));
            }
        }
        if (byPackage.isEmpty() && AppPermissionRules.GRANT.equals(defaultPolicy)) return null;

        DesiredPermissions d = new DesiredPermissions();
        d.setDefaultPolicy(defaultPolicy);
        List<DesiredPermissions.App> out = new ArrayList<DesiredPermissions.App>();
        for (Map.Entry<String, AppPermissionRules> e : byPackage.entrySet()) {
            DesiredPermissions.App a = new DesiredPermissions.App();
            a.setPackageName(e.getKey());
            AppPermissionRules r = e.getValue();
            if (r != null) {
                a.setAll(r.getAll());
                a.setPermissions(r.getPermissions() == null || r.getPermissions().isEmpty() ? null : new TreeMap<String, String>(r.getPermissions()));
                a.setSpecial(r.getSpecial() == null || r.getSpecial().isEmpty() ? null : new ArrayList<String>(r.getSpecial()));
            }
            out.add(a);
        }
        d.setApps(out.isEmpty() ? null : out);
        return d;
    }

    /**
     * Puts the device's own rules on top of an already built document and re-hashes it (a no-op without overrides),
     * so the device re-applies when its rules change without touching the shared policy.
     */
    public static DesiredConfig withDeviceOverrides(DesiredConfig doc, Configuration cfg, List<Application> apps,
                                                    Map<String, AppPermissionRules> deviceOverrides) {
        if (doc == null || deviceOverrides == null || deviceOverrides.isEmpty()) return doc;
        doc.setPermissions(build(cfg, DcPolicy.parse(cfg.getDcPolicy()), apps, deviceOverrides));
        doc.setRevision(DesiredConfigBuilder.revision(doc));
        return doc;
    }
}
