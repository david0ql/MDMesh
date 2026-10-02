package com.hmdm.util;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Kiosk branding: a logo above the apps, a logo below them and the device serial at the bottom. A policy sets it
 * (dcPolicy.kioskBrand) and a folder may set its own logos (groups.brand); for a device, each field comes from the
 * nearest folder that has it, else from the policy.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class KioskBrand {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final int MAX_URL = 1000;

    private String logoUrl;
    private String footerLogoUrl;
    private Boolean showSerial;
    /** A wallpaper behind the apps. */
    private String backgroundUrl;
    /** A support line the kiosk can call with one tap (digits, +, * and #), and the name of its button. */
    private String supportPhone;
    private String supportLabel;

    /** Never throws: null for blank / invalid / empty input. */
    public static KioskBrand parse(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            KioskBrand b = JSON.readValue(json, KioskBrand.class);
            return b == null ? null : b.cleaned();
        } catch (Exception e) {
            return null;
        }
    }

    /** Only http(s) image URLs of a sane length; null when nothing is left. */
    public KioskBrand cleaned() {
        KioskBrand c = new KioskBrand();
        c.logoUrl = url(logoUrl);
        c.footerLogoUrl = url(footerLogoUrl);
        c.showSerial = Boolean.TRUE.equals(showSerial) ? Boolean.TRUE : null;
        c.backgroundUrl = url(backgroundUrl);
        String phone = supportPhone == null ? "" : supportPhone.replaceAll("[\\s().-]", "");
        c.supportPhone = phone.matches("^\\+?[0-9*#]{3,20}$") ? phone : null;
        String label = supportLabel == null ? "" : supportLabel.trim();
        c.supportLabel = c.supportPhone == null || label.isEmpty() ? null : label.substring(0, Math.min(30, label.length()));
        return c.isEmpty() ? null : c;
    }

    @JsonIgnore
    public boolean isEmpty() {
        return logoUrl == null && footerLogoUrl == null && showSerial == null && backgroundUrl == null && supportPhone == null;
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @param policy  the policy's branding (may be null)
     * @param folders the device's folder chain, nearest first (entries may be null)
     */
    public static KioskBrand effective(KioskBrand policy, List<KioskBrand> folders) {
        KioskBrand out = new KioskBrand();
        if (folders != null) {
            for (KioskBrand f : folders) {
                if (f == null) continue;
                if (out.logoUrl == null) out.logoUrl = f.logoUrl;
                if (out.footerLogoUrl == null) out.footerLogoUrl = f.footerLogoUrl;
                if (out.showSerial == null) out.showSerial = f.showSerial;
                if (out.backgroundUrl == null) out.backgroundUrl = f.backgroundUrl;
                if (out.supportPhone == null) { out.supportPhone = f.supportPhone; out.supportLabel = f.supportLabel; }
            }
        }
        if (policy != null) {
            if (out.logoUrl == null) out.logoUrl = policy.logoUrl;
            if (out.footerLogoUrl == null) out.footerLogoUrl = policy.footerLogoUrl;
            if (out.showSerial == null) out.showSerial = policy.showSerial;
            if (out.backgroundUrl == null) out.backgroundUrl = policy.backgroundUrl;
            if (out.supportPhone == null) { out.supportPhone = policy.supportPhone; out.supportLabel = policy.supportLabel; }
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
