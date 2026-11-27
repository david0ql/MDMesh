/*
 *
 * DallyControl — the audit trail's servlet filter. It wraps the whole REST surface a console user can reach
 * (/rest/private/* and the public sign-in / password routes) OUTSIDE the authentication and folder-scope filters, so
 * a request they refuse (403) is seen too. It only observes: the request body is passed on untouched, the response
 * is streamed as usual (the first bytes are copied to tell a refusal from a success), and any failure of the audit
 * itself is swallowed. What is recorded, and how it is named, is AuditTrailService + AuditCatalog.
 *
 */

package com.hmdm.audit;

import com.hmdm.persistence.domain.User;
import com.hmdm.rest.filter.AuthFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import javax.servlet.http.HttpSession;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.SequenceInputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Singleton
public class AuditTrailFilter implements Filter {
    private static final Logger log = LoggerFactory.getLogger(AuditTrailFilter.class);

    /** Bodies up to this size are kept for the summary; larger ones (uploads) are only passed through. */
    static final int MAX_BODY = 256 * 1024;
    /** How much of the response is copied to read the envelope's status. */
    static final int RESPONSE_HEAD = 600;

    private final AuditTrailService service;

    @Inject
    public AuditTrailFilter(AuditTrailService service) {
        this.service = service;
    }

    @Override
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void destroy() {
    }

    /** The path under /rest when the audit looks at it, else null. */
    static String auditedPath(String uri, String contextPath) {
        if (uri == null) return null;
        String p = contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : uri;
        if (!p.startsWith("/rest/")) return null;
        p = p.substring(5);
        int semi = p.indexOf(';');
        if (semi >= 0) p = p.substring(0, semi);
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.startsWith("/private/") || p.startsWith("/public/auth/") || p.startsWith("/public/passwordReset/")) return p;
        return null;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest req = (HttpServletRequest) request;
        String path = null;
        try {
            path = auditedPath(req.getRequestURI(), req.getContextPath());
        } catch (Exception ignored) {
            // falls through unaudited
        }
        String method = req.getMethod();
        if (path == null || "OPTIONS".equals(method) || "HEAD".equals(method)) {
            chain.doFilter(request, response);
            return;
        }

        AuditTrailService.Seen seen = new AuditTrailService.Seen();
        HttpServletRequest passRequest = req;
        HeadResponse passResponse = new HeadResponse((HttpServletResponse) response);
        try {
            seen.method = method;
            seen.path = path;
            seen.query = req.getQueryString();
            seen.ip = clientIp(req.getRemoteAddr(), req.getHeader("CF-Connecting-IP"), req.getHeader("X-Forwarded-For"));
            seen.userAgent = req.getHeader("User-Agent");
            seen.contentType = req.getContentType();
            seen.contentLength = req.getContentLength();
            seen.at = System.currentTimeMillis();
            seen.userId = sessionUserId(req);
            if (AuditCatalog.isMutating(method) && keepsBody(seen.contentType) && seen.contentLength <= MAX_BODY) {
                BufferedRequest buffered = new BufferedRequest(req, MAX_BODY);
                seen.rawBody = buffered.text();
                passRequest = buffered;
            }
            service.before(seen);
        } catch (Exception e) {
            log.warn("Audit: could not observe {} {}: {}", method, path, e.toString());
        }

        try {
            chain.doFilter(passRequest, passResponse);
        } finally {
            try {
                passResponse.flushCopy();
                Integer after = seen.userId != null ? seen.userId : sessionUserId(req);
                service.after(seen, passResponse.status(), passResponse.head(), passResponse.isJson(), after);
            } catch (Exception e) {
                log.warn("Audit: could not record {} {}: {}", method, path, e.toString());
            }
        }
    }

    private static boolean keepsBody(String contentType) {
        if (contentType == null) return false;
        String c = contentType.toLowerCase(Locale.ROOT);
        return c.startsWith("application/json") || c.startsWith("text/plain");
    }

    private static Integer sessionUserId(HttpServletRequest req) {
        try {
            HttpSession session = req.getSession(false);
            if (session == null) return null;
            Object u = session.getAttribute(AuthFilter.sessionCredentials);
            return u instanceof User ? ((User) u).getId() : null;
        } catch (IllegalStateException invalidated) {
            return null;
        }
    }

    /**
     * The technician's address. Forwarding headers are set by whoever connects, so they are believed only when the
     * direct peer is our own proxy (Caddy / cloudflared on this host or its private network) — the same rule the
     * device check-in uses. Behind Cloudflare the real client is CF-Connecting-IP; behind Caddy alone, the first hop
     * of X-Forwarded-For.
     */
    static String clientIp(String remoteAddr, String cfConnectingIp, String forwardedFor) {
        if (isLocalOrPrivate(remoteAddr)) {
            if (cfConnectingIp != null && !cfConnectingIp.trim().isEmpty()) return clean(cfConnectingIp);
            if (forwardedFor != null && !forwardedFor.trim().isEmpty()) return clean(forwardedFor.split(",")[0]);
        }
        return remoteAddr;
    }

    private static String clean(String ip) {
        String s = ip.trim();
        // An address only: nothing else may reach the audit table from a header.
        return s.matches("[0-9A-Fa-f:.]{2,45}") ? s : "?";
    }

    static boolean isLocalOrPrivate(String addr) {
        if (addr == null) return false;
        try {
            InetAddress a = InetAddress.getByName(addr);
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                    || (a.getAddress().length == 16 && (a.getAddress()[0] & 0xfe) == 0xfc);
        } catch (Exception e) {
            return false;
        }
    }

    // --- request: keep a copy of a small body, pass everything on -------------------------------------------------

    static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] head;
        private final boolean complete;
        private final InputStream rest;

        BufferedRequest(HttpServletRequest request, int max) throws IOException {
            super(request);
            InputStream in = request.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream(1024);
            byte[] chunk = new byte[8192];
            boolean eof = false;
            while (buf.size() <= max) {
                int n = in.read(chunk, 0, Math.min(chunk.length, max + 1 - buf.size()));
                if (n < 0) {
                    eof = true;
                    break;
                }
                buf.write(chunk, 0, n);
            }
            this.head = buf.toByteArray();
            this.complete = eof;
            this.rest = in;
        }

        /** The body as text when all of it fitted, else null. */
        String text() {
            return complete ? new String(head, StandardCharsets.UTF_8) : null;
        }

        @Override
        public ServletInputStream getInputStream() {
            final InputStream all = complete ? new ByteArrayInputStream(head)
                    : new SequenceInputStream(new ByteArrayInputStream(head), rest);
            return new ServletInputStream() {
                @Override
                public int read() throws IOException {
                    return all.read();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    return all.read(b, off, len);
                }

                @Override
                public int available() throws IOException {
                    return all.available();
                }

                // Servlet 3.1 (the container's API; this module compiles against 2.5, hence no @Override).
                public boolean isFinished() {
                    try {
                        return all.available() == 0;
                    } catch (IOException e) {
                        return true;
                    }
                }

                public boolean isReady() {
                    return true;
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String enc = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(), enc == null ? "UTF-8" : enc));
        }
    }

    // --- response: remember the status and the first bytes --------------------------------------------------------

    static final class HeadResponse extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream copy = new ByteArrayOutputStream(RESPONSE_HEAD);
        private int status = 200;
        private ServletOutputStream stream;
        private PrintWriter writer;
        private StringBuilder written;

        HeadResponse(HttpServletResponse response) {
            super(response);
        }

        int status() {
            return status;
        }

        boolean isJson() {
            String c = getContentType();
            return c != null && c.toLowerCase(Locale.ROOT).contains("json");
        }

        String head() {
            if (written != null) return written.toString();
            return copy.size() == 0 ? null : new String(copy.toByteArray(), StandardCharsets.UTF_8);
        }

        void flushCopy() {
            if (writer != null) writer.flush();
        }

        @Override
        public void setStatus(int sc) {
            status = sc;
            super.setStatus(sc);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void setStatus(int sc, String sm) {
            status = sc;
            super.setStatus(sc, sm);
        }

        @Override
        public void sendError(int sc) throws IOException {
            status = sc;
            super.sendError(sc);
        }

        @Override
        public void sendError(int sc, String msg) throws IOException {
            status = sc;
            super.sendError(sc, msg);
        }

        @Override
        public void sendRedirect(String location) throws IOException {
            status = 302;
            super.sendRedirect(location);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (stream == null) {
                final ServletOutputStream out = super.getOutputStream();
                stream = new ServletOutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        if (copy.size() < RESPONSE_HEAD) copy.write(b);
                        out.write(b);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        int room = RESPONSE_HEAD - copy.size();
                        if (room > 0) copy.write(b, off, Math.min(room, len));
                        out.write(b, off, len);
                    }

                    @Override
                    public void flush() throws IOException {
                        out.flush();
                    }

                    @Override
                    public void close() throws IOException {
                        out.close();
                    }

                    // Servlet 3.1 (see BufferedRequest).
                    public boolean isReady() {
                        return true;
                    }
                };
            }
            return stream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                final PrintWriter out = super.getWriter();
                written = new StringBuilder();
                writer = new PrintWriter(new java.io.Writer() {
                    @Override
                    public void write(char[] cbuf, int off, int len) {
                        int room = RESPONSE_HEAD - written.length();
                        if (room > 0) written.append(cbuf, off, Math.min(room, len));
                        out.write(cbuf, off, len);
                    }

                    @Override
                    public void flush() {
                        out.flush();
                    }

                    @Override
                    public void close() {
                        out.close();
                    }
                });
            }
            return writer;
        }
    }
}
