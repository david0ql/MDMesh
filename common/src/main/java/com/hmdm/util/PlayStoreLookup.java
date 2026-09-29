package com.hmdm.util;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Name and icon of a Play Store app, read from its public page (og:title / og:image). Used to add a Play Store app to
 * the library by package name or link. Nothing is downloaded from Google but that page.
 */
public final class PlayStoreLookup {
    private static final Pattern OG = Pattern.compile("<meta\\s+property=\"og:(title|image)\"\\s+content=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_IN_URL = Pattern.compile("[?&]id=([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)");
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    public static final class Result {
        public final String packageName;
        public final String name;
        public final String icon;

        Result(String packageName, String name, String icon) {
            this.packageName = packageName;
            this.name = name;
            this.icon = icon;
        }
    }

    private PlayStoreLookup() {}

    /** The package from what was typed: a package name or a Play Store link; null when neither. */
    public static String packageOf(String typed) {
        if (typed == null) return null;
        String t = typed.trim();
        if (DcPolicy.isPackageName(t)) return t;
        Matcher m = ID_IN_URL.matcher(t);
        return m.find() ? m.group(1) : null;
    }

    /** Parse a Play Store details page; null name when the app does not exist (Google answers 404). */
    public static Result parse(String packageName, String html) {
        String name = null, icon = null;
        Matcher m = OG.matcher(html == null ? "" : html);
        while (m.find()) {
            String v = unescape(m.group(2));
            if ("title".equalsIgnoreCase(m.group(1)) && name == null) name = v;
            if ("image".equalsIgnoreCase(m.group(1)) && icon == null) icon = v;
        }
        if (name != null) {
            name = name.replaceAll("\\s+[-–]\\s+(Apps|Aplicaciones)\\s+(on|en)\\s+Google\\s+Play$", "").trim();
        }
        if (icon != null && !icon.startsWith("https://")) icon = null;
        return new Result(packageName, name == null || name.isEmpty() ? null : name, icon);
    }

    public static Result fetch(String packageName) throws java.io.IOException {
        String url = "https://play.google.com/store/apps/details?id=" + packageName + "&hl=es";
        try (InputStream in = UrlGuard.open(url)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < MAX_BYTES) out.write(buf, 0, n);
            return parse(packageName, new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
    }
}
