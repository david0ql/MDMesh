package com.hmdm.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Downloads the public APK of an app by its package name from a public APK mirror (the bundle with all its splits
 * when the app ships as one, else the single APK), for apps the library only knows as a Play Store listing.
 * Only https, only the mirror's own hosts, with a size cap. The caller checks what came back: the package inside
 * must be the one asked for, and the signing certificate is shown to the administrator.
 */
public final class ApkFetcher {
    public static final Pattern PACKAGE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$");
    private static final String BASE = "https://d.apkpure.net/b/";
    private static final List<String> HOSTS = Arrays.asList("apkpure.net", "apkpure.com", "winudf.com");
    private static final long MAX_BYTES = 900L * 1024 * 1024;
    private static final String UA = "Mozilla/5.0 (Linux; Android 11; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36";

    public interface Progress {
        void at(long bytes, long total);
    }

    public static final class Fetched {
        public final File file;
        /** True when the file is one plain APK, false when it is a bundle (zip of APKs). */
        public final boolean plainApk;

        Fetched(File file, boolean plainApk) {
            this.file = file;
            this.plainApk = plainApk;
        }
    }

    private ApkFetcher() {}

    static boolean allowedHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        for (String ok : HOSTS) if (h.equals(ok) || h.endsWith("." + ok)) return true;
        return false;
    }

    /** @throws IllegalStateException with a message for the console when nothing usable could be downloaded */
    public static Fetched fetch(String packageName, File dest, Progress progress) throws Exception {
        if (packageName == null || !PACKAGE.matcher(packageName).matches()) throw new IllegalArgumentException("invalid package");
        String last = "no disponible";
        for (String kind : new String[]{"XAPK", "APK"}) {
            try {
                if (download(BASE + kind + "/" + packageName + "?version=latest", dest, progress)) {
                    return new Fetched(dest, isPlainApk(dest));
                }
            } catch (Exception e) {
                last = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }
        throw new IllegalStateException("No se encontró un APK público de " + packageName + " (" + last + ").");
    }

    /** @return false when the source has no such file (so the next kind is tried) */
    private static boolean download(String url, File dest, Progress progress) throws Exception {
        String next = url;
        for (int hop = 0; hop < 8; hop++) {
            URL u = new URL(next);
            if (!"https".equals(u.getProtocol()) || !allowedHost(u.getHost())) throw new IllegalStateException("redirección no permitida");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20_000);
            c.setReadTimeout(90_000);
            c.setRequestProperty("User-Agent", UA);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) return false;
                next = new URL(u, loc).toString();
                continue;
            }
            String type = String.valueOf(c.getContentType()).toLowerCase();
            if (code != 200 || type.startsWith("text/")) {
                c.disconnect();
                return false;
            }
            long total = c.getContentLengthLong();
            if (total > MAX_BYTES) {
                c.disconnect();
                throw new IllegalStateException("el archivo es demasiado grande");
            }
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[256 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (done > MAX_BYTES) throw new IllegalStateException("el archivo es demasiado grande");
                    if (progress != null) progress.at(done, total);
                }
            } finally {
                c.disconnect();
            }
            return dest.length() > 0;
        }
        throw new IllegalStateException("demasiadas redirecciones");
    }

    /** A plain APK has its manifest at the root of the zip; a bundle holds APKs instead. */
    static boolean isPlainApk(File f) {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(f)) {
            return z.getEntry("AndroidManifest.xml") != null;
        } catch (Exception e) {
            return false;
        }
    }
}
