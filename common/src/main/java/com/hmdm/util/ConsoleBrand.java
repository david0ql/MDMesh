package com.hmdm.util;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Console branding: the logo and client name shown in the admin console header (AppShell) once a folder administrator
 * signs in. It is stored per folder (groups.console_brand) and is deliberately separate from {@link KioskBrand} (the
 * branding pushed to the device kiosk): a client's operators may see their own logo in the console while their devices
 * show a different kiosk logo, or none.
 *
 * <p>For a signed-in user the effective console brand is resolved from the folders they can reach, nearest the top of
 * their tree winning ({@link #effective}). Full administrators have no folder scope and therefore no console brand
 * (the console falls back to the DallyControl wordmark).</p>
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConsoleBrand {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final int MAX_URL = 1000;
    public static final int MAX_NAME = 40;

    /** An http(s) logo URL (hosted on this server, like the kiosk logos). */
    private String logoUrl;
    /** A short client name shown next to the logo (e.g. "Alpina"). */
    private String name;

    /** Never throws: null for blank / invalid / empty input. */
    public static ConsoleBrand parse(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            ConsoleBrand b = JSON.readValue(json, ConsoleBrand.class);
            return b == null ? null : b.cleaned();
        } catch (Exception e) {
            return null;
        }
    }

    /** Only an http(s) image URL of a sane length and a trimmed short name; null when nothing is left. */
    public ConsoleBrand cleaned() {
        ConsoleBrand c = new ConsoleBrand();
        c.logoUrl = url(logoUrl);
        String n = name == null ? "" : name.trim();
        c.name = n.isEmpty() ? null : n.substring(0, Math.min(MAX_NAME, n.length()));
        return c.isEmpty() ? null : c;
    }

    @JsonIgnore
    public boolean isEmpty() {
        return logoUrl == null && name == null;
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The console brand to show a user, resolved over the folders they can reach, nearest the top (shallowest) first.
     *
     * @param folders the user's folder brandings, shallowest (nearest the root of their tree) first; entries may be null
     */
    public static ConsoleBrand effective(List<ConsoleBrand> folders) {
        ConsoleBrand out = new ConsoleBrand();
        if (folders != null) {
            for (ConsoleBrand f : folders) {
                if (f == null) continue;
                if (out.logoUrl == null) out.logoUrl = f.logoUrl;
                if (out.name == null) out.name = f.name;
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static String url(String u) {
        if (u == null) return null;
        String t = u.trim();
        if (t.isEmpty() || t.length() > MAX_URL || !(t.startsWith("https://") || t.startsWith("http://"))) return null;
        for (int i = 0; i < t.length(); i++) if (Character.isISOControl(t.charAt(i)) || Character.isWhitespace(t.charAt(i))) return null;
        return t;
    }
}
