package com.hmdm.util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.rest.json.agent.DesiredMobileData;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Mobile data per app: the policy's section ({@link DcPolicy.MobileData}), a device's own override and what the
 * device finally receives ({@link DesiredMobileData}). Pure: the same policy and override always give the same
 * document, so the configuration revision only changes when something really changed.
 *
 * <p>Policy limits are in megabytes (what a person types); the device gets bytes.</p>
 */
public final class MobileDataPolicy {
    public static final long MB = 1024L * 1024L;
    /** A limit above this (1 TB) is a typing mistake. */
    public static final long MAX_LIMIT_MB = 1024L * 1024L;
    public static final int DEFAULT_WARN_PERCENT = 80;
    public static final int MAX_APP_LIMITS = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    private MobileDataPolicy() {}

    /**
     * A device's own setting on top of its policy. {@code mode}: {@code unrestricted} = this device's mobile data is
     * not restricted at all (lists and limits ignored); anything else follows the policy. {@code allow}: apps that
     * always keep mobile data here; {@code block}: apps that never have it here; {@code ignoreLimits}: the limits of
     * the policy do not apply to this device (e.g. after buying more data), the lists still do.
     */
    @Getter
    @Setter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DeviceOverride {
        private String mode;
        private List<String> allow;
        private List<String> block;
        private Boolean ignoreLimits;
    }

    // --- policy section -------------------------------------------------------------------------------------

    /** The section cleaned (invalid values dropped); null when it asks for nothing. */
    static DcPolicy.MobileData clean(DcPolicy.MobileData in) {
        if (in == null) return null;
        DcPolicy.MobileData c = new DcPolicy.MobileData();
        String mode = lower(in.getMode());
        c.setMode("blocklist".equals(mode) || "allowlist".equals(mode) ? mode : "open");
        c.setApps("open".equals(c.getMode()) ? null : DcPolicy.packages(in.getApps()));
        String mech = lower(in.getMechanism());
        c.setMechanism("dpm".equals(mech) || "vpn".equals(mech) ? mech : null);
        c.setDeviceLimitMb(limit(in.getDeviceLimitMb()));
        if (in.getAppLimits() != null) {
            Map<String, Long> limits = new TreeMap<String, Long>();
            for (DcPolicy.AppLimit l : in.getAppLimits()) {
                if (l == null || l.getPkg() == null) continue;
                String pkg = l.getPkg().trim();
                Long mb = limit(l.getLimitMb());
                if (!DcPolicy.isPackageName(pkg) || mb == null || limits.size() >= MAX_APP_LIMITS) continue;
                limits.put(pkg, mb);
            }
            List<DcPolicy.AppLimit> out = new ArrayList<DcPolicy.AppLimit>();
            for (Map.Entry<String, Long> e : limits.entrySet()) {
                DcPolicy.AppLimit l = new DcPolicy.AppLimit();
                l.setPkg(e.getKey());
                l.setLimitMb(e.getValue());
                out.add(l);
            }
            c.setAppLimits(out.isEmpty() ? null : out);
        }
        boolean limits = c.getDeviceLimitMb() != null || c.getAppLimits() != null;
        if (limits) {
            c.setPeriod("daily".equals(lower(in.getPeriod())) ? "daily" : "monthly");
            Integer cut = in.getCutDay();
            c.setCutDay("monthly".equals(c.getPeriod()) && cut != null && cut >= 2 && cut <= 28 ? cut : null);
            Integer warn = in.getWarnPercent();
            c.setWarnPercent(warn != null && warn >= 1 && warn <= 100 && warn != DEFAULT_WARN_PERCENT ? warn : null);
        }
        // Essentials only matter where something can take an app's data away without naming it.
        c.setEssential(c.getDeviceLimitMb() != null || "allowlist".equals(c.getMode()) ? DcPolicy.packages(in.getEssential()) : null);
        boolean lists = "allowlist".equals(c.getMode()) || ("blocklist".equals(c.getMode()) && c.getApps() != null);
        if (!lists && !limits) return null;
        if (!lists) c.setMode("open");
        return c;
    }

    private static Long limit(Long mb) {
        return mb != null && mb >= 1 && mb <= MAX_LIMIT_MB ? mb : null;
    }

    // --- device override ------------------------------------------------------------------------------------

    /** The stored override, cleaned; null for none/blank/invalid (a stored value can never break a device). */
    public static DeviceOverride parseOverride(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            return cleanOverride(JSON.readValue(json, DeviceOverride.class));
        } catch (Exception e) {
            return null;
        }
    }

    /** What is stored for a device: the cleaned JSON, or null when the override asks for nothing. */
    public static String normalizeOverride(DeviceOverride o) {
        DeviceOverride c = cleanOverride(o);
        if (c == null) return null;
        try {
            return JSON.writeValueAsString(c);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static DeviceOverride cleanOverride(DeviceOverride o) {
        if (o == null) return null;
        DeviceOverride c = new DeviceOverride();
        if ("unrestricted".equals(lower(o.getMode()))) {
            c.setMode("unrestricted");
            return c;
        }
        c.setAllow(DcPolicy.packages(o.getAllow()));
        List<String> block = DcPolicy.packages(o.getBlock());
        if (block != null && c.getAllow() != null) block.removeAll(c.getAllow()); // "allow" wins when both name an app
        c.setBlock(block == null || block.isEmpty() ? null : block);
        c.setIgnoreLimits(Boolean.TRUE.equals(o.getIgnoreLimits()) ? Boolean.TRUE : null);
        return c.getAllow() == null && c.getBlock() == null && c.getIgnoreLimits() == null ? null : c;
    }

    // --- what the device receives ---------------------------------------------------------------------------

    /**
     * The device's document section: the policy with the device's override on top. Null when nothing is restricted
     * (the agent then lifts whatever an earlier document set).
     */
    public static DesiredMobileData desired(DcPolicy.MobileData policy, DeviceOverride override) {
        DcPolicy.MobileData p = clean(policy);
        DeviceOverride o = cleanOverride(override);
        if (o != null && "unrestricted".equals(o.getMode())) return null;
        String mode = p == null ? "open" : p.getMode();
        TreeSet<String> apps = new TreeSet<String>(p == null || p.getApps() == null ? new ArrayList<String>() : p.getApps());
        TreeSet<String> essential = new TreeSet<String>(p == null || p.getEssential() == null ? new ArrayList<String>() : p.getEssential());
        Long deviceLimitMb = p == null ? null : p.getDeviceLimitMb();
        Map<String, Long> limits = new TreeMap<String, Long>();
        if (p != null && p.getAppLimits() != null) for (DcPolicy.AppLimit l : p.getAppLimits()) limits.put(l.getPkg(), l.getLimitMb());
        if (o != null) {
            if (Boolean.TRUE.equals(o.getIgnoreLimits())) {
                deviceLimitMb = null;
                limits.clear();
            }
            if (o.getAllow() != null) {
                for (String pkg : o.getAllow()) {
                    limits.remove(pkg);
                    essential.add(pkg);
                    if ("blocklist".equals(mode)) apps.remove(pkg);
                    else if ("allowlist".equals(mode)) apps.add(pkg);
                }
            }
            if (o.getBlock() != null) {
                for (String pkg : o.getBlock()) {
                    essential.remove(pkg);
                    if ("allowlist".equals(mode)) {
                        apps.remove(pkg);
                    } else {
                        mode = "blocklist";
                        apps.add(pkg);
                    }
                }
            }
        }
        boolean lists = "allowlist".equals(mode) || ("blocklist".equals(mode) && !apps.isEmpty());
        boolean hasLimits = deviceLimitMb != null || !limits.isEmpty();
        if (!lists && !hasLimits) return null;
        DesiredMobileData d = new DesiredMobileData();
        d.setMode(lists ? mode : "open");
        d.setApps(lists && !apps.isEmpty() ? new ArrayList<String>(apps) : null);
        d.setMechanism(p == null ? null : p.getMechanism());
        if (hasLimits) {
            d.setPeriod(p != null && "daily".equals(p.getPeriod()) ? "daily" : null);
            d.setCutDay(p == null ? null : p.getCutDay());
            d.setWarnPercent(p == null ? null : p.getWarnPercent());
            d.setDeviceLimitBytes(deviceLimitMb == null ? null : deviceLimitMb * MB);
            if (!limits.isEmpty()) {
                List<DesiredMobileData.Limit> out = new ArrayList<DesiredMobileData.Limit>();
                for (Map.Entry<String, Long> e : limits.entrySet()) {
                    DesiredMobileData.Limit l = new DesiredMobileData.Limit();
                    l.setPkg(e.getKey());
                    l.setBytes(e.getValue() * MB);
                    out.add(l);
                }
                d.setAppLimits(out);
            }
        }
        boolean essentialMatters = d.getDeviceLimitBytes() != null || "allowlist".equals(d.getMode());
        d.setEssential(essentialMatters && !essential.isEmpty() ? new ArrayList<String>(essential) : null);
        return d;
    }

    // --- period and consumption (console and reports) -------------------------------------------------------

    /** First local day of the period that contains {@code today}: the day itself, or the month's cut day (1..28). */
    public static LocalDate periodStart(LocalDate today, String period, Integer cutDay) {
        if ("daily".equals(period)) return today;
        int cut = cutDay == null ? 1 : Math.max(1, Math.min(28, cutDay));
        LocalDate start = today.withDayOfMonth(cut);
        return today.getDayOfMonth() < cut ? start.minusMonths(1) : start;
    }

    /** First local day of the NEXT period (when the limits start again). */
    public static LocalDate periodEnd(LocalDate today, String period, Integer cutDay) {
        LocalDate start = periodStart(today, period, cutDay);
        return "daily".equals(period) ? start.plusDays(1) : start.plusMonths(1);
    }

    /** {@code ok}, {@code warn} (at or over the warning threshold) or {@code over} (limit spent); null without a limit. */
    public static String level(long used, Long limit, Integer warnPercent) {
        if (limit == null || limit <= 0) return null;
        if (used >= limit) return "over";
        int warn = warnPercent == null ? DEFAULT_WARN_PERCENT : Math.max(1, Math.min(100, warnPercent));
        return warn < 100 && used > 0 && used * 100 >= limit * warn ? "warn" : "ok";
    }

    public static String levelLabel(String level) {
        if (level == null) return "Sin límite";
        switch (level) {
            case "over": return "Límite superado";
            case "warn": return "Cerca del límite";
            default: return "Dentro del límite";
        }
    }

    /**
     * Rows "use against limit" of one device for the console and the export: first the device as a whole (when the
     * policy sets a device limit, or always with {@code always}), then one per app with a limit, in package order.
     *
     * @param usage mobile bytes per package in the period
     * @return maps with {@code scope} (device | app), {@code pkg}, {@code label}, {@code usedbytes},
     *         {@code limitbytes}, {@code percent}, {@code level}, {@code estado}
     */
    public static List<Map<String, Object>> usageRows(DesiredMobileData d, Map<String, Long> usage, Map<String, String> labels,
                                                      long deviceBytes, boolean always) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Integer warn = d == null ? null : d.getWarnPercent();
        Long deviceLimit = d == null ? null : d.getDeviceLimitBytes();
        if (deviceLimit != null || always) out.add(row("device", null, "Todo el equipo", deviceBytes, deviceLimit, warn));
        if (d != null && d.getAppLimits() != null) {
            for (DesiredMobileData.Limit l : d.getAppLimits()) {
                Long used = usage == null ? null : usage.get(l.getPkg());
                String label = labels == null ? null : labels.get(l.getPkg());
                out.add(row("app", l.getPkg(), label == null || label.trim().isEmpty() ? l.getPkg() : label, used == null ? 0L : used, l.getBytes(), warn));
            }
        }
        return out;
    }

    private static Map<String, Object> row(String scope, String pkg, String label, long used, Long limit, Integer warn) {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        String level = level(used, limit, warn);
        r.put("scope", scope);
        r.put("pkg", pkg);
        r.put("label", label);
        r.put("usedbytes", used);
        r.put("limitbytes", limit);
        r.put("percent", limit == null || limit <= 0 ? null : (Object) Long.valueOf(used * 100 / limit));
        r.put("level", level);
        r.put("estado", levelLabel(level));
        return r;
    }

    private static String lower(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }
}
