package com.hmdm.util;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * <p>DallyControl-only configuration policies, stored as JSON in {@code configurations.dcPolicy}:</p>
 * <ul>
 *     <li>{@code kioskRoles} — device functions the kiosk allows besides its apps (phone, contacts, …); the agent
 *     resolves each to the packages that serve it on that device, so one configuration fits every brand;</li>
 *     <li>{@code browser} — Chrome's managed site lists ({@code open} / {@code allowlist} / {@code blocklist});</li>
 *     <li>{@code apps} — app policy ({@code open} / {@code allowlist}), extra allowed packages and functions, and
 *     whether to hide the Play Store;</li>
 *     <li>{@code trackingMinutes} — a location fix every N minutes (1–1440).</li>
 * </ul>
 * <p>{@link #parse} never throws and drops anything invalid, so a stored value can never break a device;
 * {@link #normalize} is what the configuration save stores (and refuses when the JSON is not an object).</p>
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DcPolicy {
    public static final List<String> ROLES =
            Collections.unmodifiableList(Arrays.asList("phone", "contacts", "messages", "browser", "camera", "maps"));
    public static final int MAX_LIST = 1000;
    public static final int MAX_ENTRY = 2048;
    private static final Pattern PACKAGE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private List<String> kioskRoles;
    private Browser browser;
    private Apps apps;
    private Integer trackingMinutes;
    /** Kiosk offers the agent's quick settings (brightness, volume, Wi-Fi, Bluetooth). */
    private Boolean kioskQuickSettings;
    /** Automatic device name: serial (default) / imei / model-serial / none. */
    private String deviceName;
    /** Wi-Fi networks saved on the devices (the agent adds them; one removed here is removed from the phones). */
    private List<Wifi> wifi;

    @Getter
    @Setter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Wifi {
        private String ssid;
        private String password;
        /** WPA (default), WEP or NONE. */
        private String security;
        private Boolean hidden;
    }

    @Getter
    @Setter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Browser {
        private String mode;
        private List<String> allow;
        private List<String> block;
    }

    @Getter
    @Setter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Apps {
        private String mode;
        private List<String> allowed;
        private List<String> roles;
        private Boolean hidePlayStore;
    }

    /** The stored value, cleaned; an empty policy for null/blank/invalid input. */
    public static DcPolicy parse(String json) {
        if (json == null || json.trim().isEmpty()) return new DcPolicy();
        try {
            DcPolicy p = JSON.readValue(json, DcPolicy.class);
            return p == null ? new DcPolicy() : p.cleaned();
        } catch (Exception e) {
            return new DcPolicy();
        }
    }

    /**
     * What a configuration save stores: the cleaned JSON, or null when nothing is set.
     *
     * @throws IllegalArgumentException when the value is not a JSON object
     */
    public static String normalize(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        DcPolicy p;
        try {
            p = JSON.readValue(json, DcPolicy.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("dcPolicy is not a valid policy object", e);
        }
        if (p == null) return null;
        p = p.cleaned();
        if (p.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(p);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @JsonIgnore
    public boolean isEmpty() {
        return kioskRoles == null && browser == null && apps == null && trackingMinutes == null && kioskQuickSettings == null && deviceName == null && wifi == null;
    }

    private DcPolicy cleaned() {
        DcPolicy c = new DcPolicy();
        c.kioskRoles = roles(kioskRoles);
        if (browser != null) {
            String mode = lower(browser.mode);
            if ("open".equals(mode) || "allowlist".equals(mode) || "blocklist".equals(mode)) {
                Browser b = new Browser();
                b.mode = mode;
                b.allow = urls(browser.allow);
                b.block = urls(browser.block);
                c.browser = b;
            }
        }
        if (apps != null) {
            String mode = lower(apps.mode);
            if ("open".equals(mode) || "allowlist".equals(mode)) {
                Apps a = new Apps();
                a.mode = mode;
                a.allowed = packages(apps.allowed);
                a.roles = roles(apps.roles);
                a.hidePlayStore = Boolean.TRUE.equals(apps.hidePlayStore) ? Boolean.TRUE : null;
                c.apps = a;
            }
        }
        if (trackingMinutes != null && trackingMinutes >= 1 && trackingMinutes <= 1440) c.trackingMinutes = trackingMinutes;
        c.kioskQuickSettings = Boolean.TRUE.equals(kioskQuickSettings) ? Boolean.TRUE : null;
        c.deviceName = DeviceNaming.normalizeRule(deviceName);
        if (wifi != null) {
            List<Wifi> out = new ArrayList<Wifi>();
            Set<String> seen = new LinkedHashSet<String>();
            for (Wifi w : wifi) {
                if (w == null || w.ssid == null) continue;
                String ssid = w.ssid.trim();
                if (ssid.isEmpty() || ssid.length() > 32 || containsControl(ssid) || !seen.add(ssid)) continue;
                String sec = w.security == null ? "WPA" : w.security.trim().toUpperCase(Locale.ROOT);
                if (!sec.equals("WPA") && !sec.equals("WEP") && !sec.equals("NONE")) sec = "WPA";
                Wifi n = new Wifi();
                n.ssid = ssid;
                n.security = sec;
                n.password = sec.equals("NONE") || w.password == null || w.password.length() > 63 ? null : w.password;
                n.hidden = Boolean.TRUE.equals(w.hidden) ? Boolean.TRUE : null;
                out.add(n);
                if (out.size() >= 20) break;
            }
            c.wifi = out.isEmpty() ? null : out;
        }
        return c;
    }

    /** Known roles only, in the canonical order; null when none. */
    static List<String> roles(List<String> in) {
        if (in == null) return null;
        Set<String> wanted = new LinkedHashSet<String>();
        for (String r : in) if (r != null) wanted.add(r.trim().toLowerCase(Locale.ROOT));
        List<String> out = new ArrayList<String>();
        for (String r : ROLES) if (wanted.contains(r)) out.add(r);
        return out.isEmpty() ? null : out;
    }

    /** Trimmed, non-blank, deduplicated URL filters (order kept); null when none. */
    static List<String> urls(List<String> in) {
        if (in == null) return null;
        Set<String> out = new LinkedHashSet<String>();
        for (String u : in) {
            if (u == null) continue;
            String t = u.trim();
            if (t.isEmpty() || t.length() > MAX_ENTRY || containsControl(t)) continue;
            out.add(t);
            if (out.size() >= MAX_LIST) break;
        }
        return out.isEmpty() ? null : new ArrayList<String>(out);
    }

    /** Valid Android package names, deduplicated and sorted; null when none. */
    static List<String> packages(List<String> in) {
        if (in == null) return null;
        Set<String> out = new java.util.TreeSet<String>();
        for (String p : in) {
            if (p == null) continue;
            String t = p.trim();
            if (PACKAGE.matcher(t).matches() && t.length() <= 255) out.add(t);
            if (out.size() >= MAX_LIST) break;
        }
        return out.isEmpty() ? null : new ArrayList<String>(out);
    }

    public static boolean isPackageName(String s) {
        return s != null && s.length() <= 255 && PACKAGE.matcher(s).matches();
    }

    private static boolean containsControl(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isISOControl(s.charAt(i))) return true;
        return false;
    }

    private static String lower(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }
}
