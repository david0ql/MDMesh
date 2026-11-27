package com.hmdm.audit;

import org.junit.Test;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** The audit filter only observes: which paths it looks at, whose address it records, and that bodies pass intact. */
public class AuditTrailFilterTest {

    @Test
    public void whichPathsAreObserved() {
        assertEquals("/private/devices/3", AuditTrailFilter.auditedPath("/rest/private/devices/3", ""));
        assertEquals("/private/devices", AuditTrailFilter.auditedPath("/ctx/rest/private/devices/", "/ctx"));
        assertEquals("/public/auth/login", AuditTrailFilter.auditedPath("/rest/public/auth/login", null));
        assertEquals("/public/passwordReset/reset", AuditTrailFilter.auditedPath("/rest/public/passwordReset/reset", ""));
        assertEquals("/private/x", AuditTrailFilter.auditedPath("/rest/private/x;jsessionid=abc", ""));
        // The device agent's protocol and anything outside /rest are not the console's.
        assertNull(AuditTrailFilter.auditedPath("/rest/public/agent/v1/checkin", ""));
        assertNull(AuditTrailFilter.auditedPath("/files/app.apk", ""));
        assertNull(AuditTrailFilter.auditedPath("/rest/public/name", ""));
    }

    @Test
    public void theRealAddressBehindOurOwnProxyOnly() {
        // Behind Caddy (private peer): the first hop of X-Forwarded-For; behind Cloudflare: CF-Connecting-IP.
        assertEquals("190.24.1.9", AuditTrailFilter.clientIp("172.18.0.5", null, "190.24.1.9, 172.18.0.1"));
        assertEquals("181.50.2.2", AuditTrailFilter.clientIp("172.18.0.5", "181.50.2.2", "10.0.0.1"));
        assertEquals("2800:e2:180::1", AuditTrailFilter.clientIp("127.0.0.1", "2800:e2:180::1", null));
        // A client that reaches the server directly cannot choose the address that is recorded.
        assertEquals("200.1.1.1", AuditTrailFilter.clientIp("200.1.1.1", "9.9.9.9", "8.8.8.8"));
        assertEquals("172.18.0.5", AuditTrailFilter.clientIp("172.18.0.5", null, null));
        // A header is an address or nothing: it never carries text into the table.
        assertEquals("?", AuditTrailFilter.clientIp("127.0.0.1", "<script>alert(1)</script>", null));
    }

    private static HttpServletRequest request(byte[] body) {
        return (HttpServletRequest) Proxy.newProxyInstance(AuditTrailFilterTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> {
                    if ("getInputStream".equals(method.getName())) {
                        final InputStream in = new ByteArrayInputStream(body);
                        return new ServletInputStream() {
                            @Override
                            public int read() throws java.io.IOException {
                                return in.read();
                            }
                        };
                    }
                    if ("getCharacterEncoding".equals(method.getName())) return "UTF-8";
                    return null;
                });
    }

    private static byte[] drain(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[777];
        for (int n; (n = in.read(b)) >= 0; ) out.write(b, 0, n);
        return out.toByteArray();
    }

    @Test
    public void aSmallBodyIsKeptAndPassedOnUnchanged() throws Exception {
        byte[] body = "{\"type\":\"device.lock\",\"nota\":\"señal\"}".getBytes(StandardCharsets.UTF_8);
        AuditTrailFilter.BufferedRequest r = new AuditTrailFilter.BufferedRequest(request(body), 1024);
        assertEquals("{\"type\":\"device.lock\",\"nota\":\"señal\"}", r.text());
        assertArrayEquals(body, drain(r.getInputStream()));
        assertEquals("{\"type\":\"device.lock\",\"nota\":\"señal\"}", r.getReader().readLine());
    }

    @Test
    public void aBodyLargerThanTheLimitIsPassedOnWholeButNotKept() throws Exception {
        byte[] body = new byte[5000];
        for (int i = 0; i < body.length; i++) body[i] = (byte) ('a' + i % 26);
        AuditTrailFilter.BufferedRequest r = new AuditTrailFilter.BufferedRequest(request(body), 1024);
        assertNull(r.text());
        assertArrayEquals(body, drain(r.getInputStream()));
    }

    @Test
    public void aBodyOfExactlyTheLimitIsKept() throws Exception {
        byte[] body = new byte[1024];
        java.util.Arrays.fill(body, (byte) 'x');
        AuditTrailFilter.BufferedRequest r = new AuditTrailFilter.BufferedRequest(request(body), 1024);
        assertEquals(1024, r.text().length());
        assertArrayEquals(body, drain(r.getInputStream()));
    }
}
