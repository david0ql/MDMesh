package com.hmdm.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

/**
 * Session cookie flags. HttpOnly always; Secure when the public URL is https (the {@code session.cookie.secure} context
 * parameter, set from BASE_URL by the container entrypoint). Tomcat itself cannot tell: it sits behind Caddy (and, in
 * Cloudflare mode, cloudflared) and only ever sees plain HTTP. SameSite is set by the context's CookieProcessor.
 *
 * The project compiles against the Servlet 2.5 API while Tomcat 9 runs Servlet 4, so SessionCookieConfig (3.0+) is
 * reached reflectively.
 */
public class SessionCookieHardening implements ServletContextListener {

    private static final Logger log = LoggerFactory.getLogger(SessionCookieHardening.class);

    @Override
    public void contextInitialized(ServletContextEvent event) {
        ServletContext ctx = event.getServletContext();
        boolean secure = "true".equalsIgnoreCase(ctx.getInitParameter("session.cookie.secure"));
        try {
            Object cookie = ctx.getClass().getMethod("getSessionCookieConfig").invoke(ctx);
            cookie.getClass().getMethod("setHttpOnly", boolean.class).invoke(cookie, true);
            cookie.getClass().getMethod("setSecure", boolean.class).invoke(cookie, secure);
            log.info("Session cookie: HttpOnly, Secure={}", secure);
        } catch (ReflectiveOperationException e) {
            // Must not start with weaker cookies than configured.
            throw new IllegalStateException("Cannot configure the session cookie", e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
    }
}
