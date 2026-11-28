package com.hmdm.util;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <p>What the console decides about ONE app's permissions, in a policy ({@code dcPolicy.permissions[package]}) or on
 * one device ({@code device_permission_override}):</p>
 * <ul>
 *     <li>{@code all} — preset for every runtime permission the app declares: {@code grant} / {@code deny} /
 *     {@code prompt};</li>
 *     <li>{@code permissions} — per group ({@link #GROUPS}), wins over the preset;</li>
 *     <li>{@code special} — special accesses the app should have ({@link #SPECIAL}).</li>
 * </ul>
 * <p>The keys are the agent's {@code PermissionCatalog}; anything else is dropped by {@link #cleaned}.</p>
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppPermissionRules {
    public static final String GRANT = "grant";
    public static final String DENY = "deny";
    public static final String PROMPT = "prompt";

    public static final List<String> LOCATION_GROUPS =
            Collections.unmodifiableList(Arrays.asList("locationFine", "locationCoarse", "locationBackground"));
    public static final List<String> GROUPS = Collections.unmodifiableList(Arrays.asList(
            "locationFine", "locationCoarse", "locationBackground", "camera", "microphone", "storage", "contacts", "phone",
            "sms", "notifications", "nearbyDevices", "activity", "calendar"));
    public static final List<String> SPECIAL = Collections.unmodifiableList(Arrays.asList(
            "allFiles", "overlay", "writeSettings", "unknownSources", "battery", "unrestrictedData", "notificationAccess",
            "accessibility"));
    public static final int MAX_APPS = 200;

    private static final ObjectMapper JSON = new ObjectMapper();

    private String all;
    private Map<String, String> permissions;
    private List<String> special;

    public static boolean isRule(String v) {
        return GRANT.equals(v) || DENY.equals(v) || PROMPT.equals(v);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return all == null && (permissions == null || permissions.isEmpty()) && (special == null || special.isEmpty());
    }

    /** Known keys and values only, in a canonical order; null when nothing is left. */
    public AppPermissionRules cleaned() {
        AppPermissionRules c = new AppPermissionRules();
        String a = all == null ? null : all.trim().toLowerCase(java.util.Locale.ROOT);
        c.all = isRule(a) ? a : null;
        if (permissions != null) {
            Map<String, String> out = new TreeMap<String, String>();
            for (String g : GROUPS) {
                String v = permissions.get(g);
                v = v == null ? null : v.trim().toLowerCase(java.util.Locale.ROOT);
                if (isRule(v)) out.put(g, v);
            }
            c.permissions = out.isEmpty() ? null : out;
        }
        if (special != null) {
            List<String> out = new ArrayList<String>();
            for (String s : SPECIAL) if (special.contains(s)) out.add(s);
            c.special = out.isEmpty() ? null : out;
        }
        return c.isEmpty() ? null : c;
    }

    /** A copy of {@code base} with {@code over} on top: its preset and each of its group rules win; specials add up. */
    public static AppPermissionRules merge(AppPermissionRules base, AppPermissionRules over) {
        if (over == null) return base;
        if (base == null) return over;
        AppPermissionRules m = new AppPermissionRules();
        m.all = over.all != null ? over.all : base.all;
        Map<String, String> p = new TreeMap<String, String>();
        if (base.permissions != null) p.putAll(base.permissions);
        if (over.permissions != null) p.putAll(over.permissions);
        m.permissions = p.isEmpty() ? null : p;
        List<String> s = new ArrayList<String>();
        for (String k : SPECIAL) {
            if ((base.special != null && base.special.contains(k)) || (over.special != null && over.special.contains(k))) s.add(k);
        }
        m.special = s.isEmpty() ? null : s;
        return m;
    }

    /** Valid packages with at least one rule, sorted by package; null when none. */
    public static Map<String, AppPermissionRules> cleaned(Map<String, AppPermissionRules> in) {
        if (in == null) return null;
        Map<String, AppPermissionRules> out = new TreeMap<String, AppPermissionRules>();
        for (Map.Entry<String, AppPermissionRules> e : in.entrySet()) {
            String pkg = e.getKey() == null ? "" : e.getKey().trim();
            if (!DcPolicy.isPackageName(pkg) || e.getValue() == null) continue;
            AppPermissionRules c = e.getValue().cleaned();
            if (c != null) out.put(pkg, c);
            if (out.size() >= MAX_APPS) break;
        }
        return out.isEmpty() ? null : out;
    }

    /** A stored per-device override ({@code { package: rules }}); never throws, an empty map for anything invalid. */
    public static Map<String, AppPermissionRules> parseMap(String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyMap();
        try {
            Map<String, AppPermissionRules> m = cleaned(JSON.<Map<String, AppPermissionRules>>readValue(json,
                    new TypeReference<Map<String, AppPermissionRules>>() { }));
            return m == null ? Collections.<String, AppPermissionRules>emptyMap() : m;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /** The JSON to store for a per-device override, or null when it holds no rule. */
    public static String toJson(Map<String, AppPermissionRules> rules) {
        Map<String, AppPermissionRules> c = cleaned(rules);
        if (c == null) return null;
        try {
            return JSON.writeValueAsString(c);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
