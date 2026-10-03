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
    /** Kiosk offers the agent's quick settings (brightness, volume, Wi-Fi, Bluetooth): on by default, false = off. */
    private Boolean kioskQuickSettings;
    /** Automatic device name: serial (default) / imei / model-serial / none. */
    private String deviceName;
    /** Wi-Fi networks saved on the devices (the agent adds them; one removed here is removed from the phones). */
    private List<Wifi> wifi;
    /** App groups this policy uses: their apps are installed and allowed; shown in the kiosk only when kiosk=true. */
    private List<AppGroupRef> appGroups;
    /** Packages of this policy's own apps that are installed and allowed but NOT shown in the kiosk. */
    private List<String> notInKiosk;
    /** Packages never shown as kiosk icons, whatever brings them (a function such as the browser, a group…). */
    private List<String> kioskHidden;
    /** Kiosk branding: logo above the apps, logo below, serial at the bottom (folders may override the logos). */
    private KioskBrand kioskBrand;
    /** Anti-theft in the kiosk: 4-12 digits asked before switching the phone off or restarting it. */
    private String powerPin;
    /** Device security rules: data sharing, Google accounts, factory reset. */
    private com.hmdm.rest.json.agent.DesiredDevice device;

    @Getter
    @Setter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AppGroupRef {
        private Integer id;
        private Boolean kiosk;
    }

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
        /** The company page: Chrome's home page, and every search typed in the address bar goes there. */
        private String homeUrl;
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
        return kioskRoles == null && browser == null && apps == null && trackingMinutes == null && kioskQuickSettings == null
                && deviceName == null && wifi == null && appGroups == null && notInKiosk == null && kioskHidden == null && kioskBrand == null
                && powerPin == null && device == null;
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
                String h = browser.homeUrl == null ? "" : browser.homeUrl.trim();
                b.homeUrl = (h.startsWith("https://") || h.startsWith("http://")) && h.length() <= MAX_ENTRY && !containsControl(h)
                        && !h.contains(" ") ? h : null;
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
        c.kioskQuickSettings = Boolean.FALSE.equals(kioskQuickSettings) ? Boolean.FALSE : null; // on unless turned off
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
        if (appGroups != null) {
            List<AppGroupRef> out = new ArrayList<AppGroupRef>();
            Set<Integer> seen = new LinkedHashSet<Integer>();
            for (AppGroupRef g : appGroups) {
                if (g == null || g.id == null || g.id <= 0 || !seen.add(g.id)) continue;
                AppGroupRef n = new AppGroupRef();
                n.id = g.id;
                n.kiosk = Boolean.TRUE.equals(g.kiosk) ? Boolean.TRUE : null;
                out.add(n);
                if (out.size() >= 50) break;
            }
            c.appGroups = out.isEmpty() ? null : out;
        }
        c.notInKiosk = packages(notInKiosk);
        c.kioskHidden = packages(kioskHidden);
        c.kioskBrand = kioskBrand == null ? null : kioskBrand.cleaned();
        c.powerPin = powerPin != null && powerPin.trim().matches("^[0-9]{4,12}$") ? powerPin.trim() : null;
        if (device != null) {
            com.hmdm.rest.json.agent.DesiredDevice d = new com.hmdm.rest.json.agent.DesiredDevice();
            String t = lower(device.getTethering());
            d.setTethering("allow".equals(t) || "block".equals(t) ? t : null);
            d.setGoogleAccounts("block".equals(lower(device.getGoogleAccounts())) ? "block" : null);
            List<String> doms = new ArrayList<String>();
            List<String> asked = new ArrayList<String>();
            if (device.getAccountDomains() != null) asked.addAll(device.getAccountDomains());
            if (device.getAccountDomain() != null) asked.add(device.getAccountDomain());
            for (String a : asked) {
                String dom = a == null ? "" : a.trim().toLowerCase(Locale.ROOT).replaceFirst("^.*@", "");
                if (dom.matches("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$") && dom.length() <= 100
                        && !doms.contains(dom) && doms.size() < 20) doms.add(dom);
            }
            d.setAccountDomains(doms.isEmpty() ? null : doms);
            d.setAccountDomain(doms.size() == 1 ? doms.get(0) : null);
            d.setFactoryReset("block".equals(lower(device.getFactoryReset())) ? "block" : null);
            if (device.getFrpAccounts() != null) {
                List<String> ids = new ArrayList<String>();
                for (String a : device.getFrpAccounts()) {
                    if (a != null && a.trim().matches("^[0-9]{6,30}$") && !ids.contains(a.trim()) && ids.size() < 10) ids.add(a.trim());
                }
                d.setFrpAccounts(ids.isEmpty() ? null : ids);
            }
            c.device = d.getTethering() == null && d.getGoogleAccounts() == null && d.getAccountDomains() == null
                    && d.getFactoryReset() == null && d.getFrpAccounts() == null ? null : d;
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
