package com.hmdm.security;

import javax.servlet.http.HttpServletRequest;

/**
 * <p>The network address of the console request the current thread is serving, kept next to
 * {@link SecurityContext} so code far from the servlet layer (the command queue) can record who asked for
 * something and from where. Set and cleared by {@code AuthFilter}; null on any other thread.</p>
 */
public final class RequestOrigin {

    private static final ThreadLocal<String> IP = new ThreadLocal<>();

    private RequestOrigin() {
    }

    public static void set(HttpServletRequest request) {
        IP.set(clientIp(request));
    }

    public static void set(String ip) {
        IP.set(ip);
    }

    /** The address of the current request, or null when the thread serves none. */
    public static String ip() {
        return IP.get();
    }

    public static void clear() {
        IP.remove();
    }

    /**
     * The caller's address as the server sees it. Forwarding headers can be written by anyone, so they count only
     * when the direct peer is a local or private address, that is, our own proxy in front of the server.
     */
    public static String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        String remote = request.getRemoteAddr();
        return resolve(remote, request.getHeader("CF-Connecting-IP"), request.getHeader("X-Forwarded-For"));
    }

    static String resolve(String remote, String cfConnectingIp, String forwardedFor) {
        if (isLocalOrPrivate(remote)) {
            if (cfConnectingIp != null && !cfConnectingIp.trim().isEmpty()) return clip(cfConnectingIp.trim());
            if (forwardedFor != null && !forwardedFor.trim().isEmpty()) return clip(forwardedFor.split(",")[0].trim());
        }
        return clip(remote);
    }

    private static String clip(String ip) {
        return ip == null ? null : (ip.length() > 64 ? ip.substring(0, 64) : ip);
    }

    private static boolean isLocalOrPrivate(String addr) {
        if (addr == null) return false;
        try {
            java.net.InetAddress a = java.net.InetAddress.getByName(addr);
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                    || (a.getAddress().length == 16 && (a.getAddress()[0] & 0xfe) == 0xfc);
        } catch (Exception e) {
            return false;
        }
    }
}
