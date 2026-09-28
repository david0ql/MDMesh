package com.hmdm.rest.filter;

import com.google.inject.Singleton;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * <p>Allowlist of the REST surface reachable without a console session.</p>
 *
 * <p>Jersey scans every resource under {@code com.hmdm}, so the legacy Headwind endpoints (launcher sync, notification
 * queue, plugin device APIs, stats, signup, QR, Swagger…) are mounted even though DallyControl never uses them — and
 * several authenticate a device by its number alone. This filter lets through {@code /rest/private/**} (guarded by the
 * JWT/Auth filters) and only the public endpoints the console, the agent and the edge actually call; everything else
 * answers 404.</p>
 *
 * <p>The decision is made on the decoded, canonical path: Jersey routes percent-decoded paths with matrix parameters
 * stripped, so matching the raw URI would let an encoded path slip past the allowlist.</p>
 */
@Singleton
public class RestSurfaceFilter implements Filter {

    /** Public prefixes (segment-bounded) and exact paths reachable without a session. */
    static final String[] PUBLIC_PREFIXES = {
            "/rest/private/",               // session-authenticated (JWTFilter + AuthFilter)
            "/rest/public/agent/v1/",       // device agent: enroll (single-use token), check-in (device secret), tunnel gate
            "/rest/public/passwordReset/",  // console "set password" page (reset token)
    };
    static final String[] PUBLIC_EXACT = {
            "/rest/public/auth/login",
            "/rest/public/auth/logout",
            "/rest/public/auth/options",
            "/rest/public/name",            // health probe (/healthz)
    };

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        String uri = req.getRequestURI();
        String ctx = req.getContextPath();
        String path = uri.startsWith(ctx) ? uri.substring(ctx.length()) : uri;
        if (!isAllowed(path)) {
            ((HttpServletResponse) response).sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Whether a raw request path (context stripped) may reach the REST layer. */
    public static boolean isAllowed(String rawPath) {
        String path = canonical(rawPath);
        if (path == null) {
            return false;
        }
        for (String exact : PUBLIC_EXACT) {
            if (path.equals(exact) || path.equals(exact + "/")) {
                return true;
            }
        }
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix) && path.length() > prefix.length()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Percent-decoded path with matrix parameters removed and slashes collapsed, or null when it cannot be trusted
     * (bad encoding, "." / ".." segments, backslashes, control characters).
     */
    static String canonical(String rawPath) {
        if (rawPath == null || rawPath.isEmpty() || rawPath.charAt(0) != '/') {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String segment : rawPath.split("/", -1)) {
            int semi = segment.indexOf(';');
            String s = semi >= 0 ? segment.substring(0, semi) : segment;
            String decoded = percentDecode(s);
            if (decoded == null) {
                return null;
            }
            if (decoded.isEmpty()) {
                continue;
            }
            if (decoded.equals(".") || decoded.equals("..") || decoded.contains("/") || decoded.contains("\\")) {
                return null;
            }
            for (int i = 0; i < decoded.length(); i++) {
                if (Character.isISOControl(decoded.charAt(i))) {
                    return null;
                }
            }
            out.append('/').append(decoded);
        }
        if (rawPath.endsWith("/")) {
            out.append('/');
        }
        return out.length() == 0 ? "/" : out.toString();
    }

    private static String percentDecode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    return null;
                }
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    return null;
                }
                bytes.write((hi << 4) + lo);
                i += 2;
            } else {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                bytes.write(b, 0, b.length);
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    @Override
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void destroy() {
    }
}
