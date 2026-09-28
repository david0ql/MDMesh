package com.hmdm.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;

/**
 * <p>Guard for URLs the server fetches on a user's behalf (SSRF): only http(s) to public addresses. Loopback, private,
 * link-local (incl. cloud metadata 169.254.169.254), CGNAT, multicast, unspecified and IPv6 unique-local targets are
 * refused, as is any host that resolves to one of them. Redirects are not followed.</p>
 */
public final class UrlGuard {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private UrlGuard() {
    }

    /** Whether the server may fetch this URL. */
    public static boolean isPublicHttpUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty() || uri.getUserInfo() != null) {
                return false;
            }
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                return false;
            }
            for (InetAddress a : addresses) {
                if (!isPublic(a)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            if (b0 == 0) return false;                          // 0.0.0.0/8
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return false; // 100.64.0.0/10 CGNAT
            if (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0) return false; // 192.0.0.0/24
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return false; // benchmarking
            if (b0 >= 240) return false;                        // reserved / broadcast
        } else if (a instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return false;             // fc00::/7 unique local
            boolean mapped = true;                               // ::ffff:a.b.c.d
            for (int i = 0; i < 10; i++) mapped &= b[i] == 0;
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (Exception e) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Open a guarded URL (no redirects, timeouts); throws IOException when the URL is not allowed. */
    public static InputStream open(String url) throws IOException {
        if (!isPublicHttpUrl(url)) {
            throw new IOException("URL not allowed (only http/https to public addresses): " + url);
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        int code = conn.getResponseCode();
        if (code / 100 != 2) {
            conn.disconnect();
            throw new IOException("HTTP " + code + " from " + url);
        }
        return conn.getInputStream();
    }
}
