package com.hmdm.audit;

import com.hmdm.rest.filter.RestSurfaceFilter;
import org.junit.Test;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The audit trail must name every state-changing route a console user can reach. This test reads the JAX-RS
 * annotations of every resource class and fails when a reachable POST / PUT / DELETE is missing from AuditCatalog,
 * so a new mutating endpoint cannot be merged without deciding how the audit calls it (or declaring it a read, a
 * device call or a minor preference, with the reason).
 */
public class AuditCatalogCoverageTest {

    /** One JAX-RS route: verb + template under /rest. */
    private static final class Route {
        final String verb;
        final String template;
        final String where;

        Route(String verb, String template, String where) {
            this.verb = verb;
            this.template = template;
            this.where = where;
        }

        /** A concrete path of the template, to match it the way a request would. */
        String sample() {
            return template.replaceAll("\\{[^}]+}", "1");
        }
    }

    private static List<Route> routes() throws Exception {
        List<Route> out = new ArrayList<>();
        for (Class<?> c : resourceClasses()) {
            Path base = c.getAnnotation(Path.class);
            if (base == null) continue;
            for (Method m : c.getDeclaredMethods()) {
                String verb = m.isAnnotationPresent(POST.class) ? "POST" : m.isAnnotationPresent(PUT.class) ? "PUT"
                        : m.isAnnotationPresent(DELETE.class) ? "DELETE" : m.isAnnotationPresent(GET.class) ? "GET" : null;
                if (verb == null) continue;
                Path sub = m.getAnnotation(Path.class);
                String template = join(base.value(), sub == null ? "" : sub.value());
                out.add(new Route(verb, template, c.getSimpleName() + "." + m.getName()));
            }
        }
        return out;
    }

    private static String join(String base, String sub) {
        String p = ("/" + base + "/" + sub).replaceAll("/+", "/");
        // "{id: \\d+}" → "{id}"
        p = p.replaceAll("\\{\\s*([^}:\\s]+)\\s*:[^}]*}", "{$1}");
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    /** Every class of the server's resource package (the compiled classes directory is walked). */
    private static List<Class<?>> resourceClasses() throws Exception {
        String pkg = "com/hmdm/rest/resource";
        URL url = com.hmdm.rest.resource.DeviceResource.class.getProtectionDomain().getCodeSource().getLocation();
        File dir = new File(new File(url.toURI()), pkg);
        File[] files = dir.listFiles();
        assertNotNull("resource classes not found in " + dir, files);
        List<Class<?>> out = new ArrayList<>();
        for (File f : files) {
            String n = f.getName();
            if (!n.endsWith(".class") || n.contains("$")) continue;
            out.add(Class.forName("com.hmdm.rest.resource." + n.substring(0, n.length() - 6)));
        }
        assertTrue("too few resource classes found: " + out.size(), out.size() > 30);
        return out;
    }

    private static boolean reachable(Route r) {
        return RestSurfaceFilter.isAllowed("/rest" + r.sample());
    }

    @Test
    public void everyReachableMutatingRouteIsInTheCatalog() throws Exception {
        Set<String> missing = new TreeSet<>();
        Set<String> shadowed = new TreeSet<>();
        int checked = 0;
        for (Route r : routes()) {
            if ("GET".equals(r.verb) || !reachable(r)) continue;
            checked++;
            AuditCatalog.Match m = AuditCatalog.find(r.verb, r.sample());
            if (m == null) {
                missing.add(r.verb + " " + r.template + "   (" + r.where + ")");
            } else if (!m.rule.template.equals(r.template)) {
                // Matched by another route's rule: it would be recorded under the wrong name.
                shadowed.add(r.verb + " " + r.template + " is matched by the rule of " + m.rule.template);
            }
        }
        assertTrue("no mutating routes found: the scan is broken", checked > 80);
        assertTrue("State-changing routes without an entry in AuditCatalog (add audit(...) with a Spanish label, or "
                + "skip(Kind.READ/DEVICE/MINOR, ...) with the reason):\n  " + String.join("\n  ", missing), missing.isEmpty());
        assertTrue("Routes recorded under another route's rule (order the more specific rule first):\n  "
                + String.join("\n  ", shadowed), shadowed.isEmpty());
    }

    @Test
    public void catalogHasNoStaleRoutes() throws Exception {
        Set<String> existing = new HashSet<>();
        for (Route r : routes()) existing.add(r.verb + " " + r.template);
        Set<String> stale = new TreeSet<>();
        for (AuditCatalog.Rule rule : AuditCatalog.rules()) {
            if (!existing.contains(rule.key())) stale.add(rule.key());
        }
        assertTrue("AuditCatalog names routes that no resource declares (renamed or removed?):\n  "
                + String.join("\n  ", stale), stale.isEmpty());
    }

    @Test
    public void everyRecordedRuleIsReadable() {
        Set<String> seen = new HashSet<>();
        for (AuditCatalog.Rule rule : AuditCatalog.rules()) {
            assertTrue("duplicate rule " + rule.key(), seen.add(rule.key()));
            if (rule.kind != AuditCatalog.Kind.AUDIT) {
                assertNotNull("a skipped route needs its reason: " + rule.key(), rule.reason);
                continue;
            }
            assertTrue("unknown module in " + rule.key(), AuditCatalog.CATEGORIES.containsKey(rule.category));
            assertFalse("no label for " + rule.key(), rule.label == null || rule.label.trim().length() < 5);
            assertFalse("no action key for " + rule.key(), rule.action == null || rule.action.isEmpty());
            if (rule.targetType != null && !"devices".equals(rule.targetType)) {
                assertNotNull("unknown target type '" + rule.targetType + "' in " + rule.key(), AuditTargets.of(rule.targetType));
            }
            if (rule.targetSource != null) {
                assertTrue("bad target source in " + rule.key(), rule.targetSource.matches("(path|body|query):\\w+"));
                if (rule.targetSource.startsWith("path:")) {
                    assertTrue("target source names a placeholder the route lacks: " + rule.key(),
                            rule.template.contains("{" + rule.targetSource.substring(5) + "}"));
                }
            }
            if ("GET".equals(rule.method)) assertTrue("a recorded GET must be marked sensitive: " + rule.key(), rule.sensitiveRead);
        }
    }

    @Test
    public void theRoutesTheClientAskedForAreRecorded() {
        String[][] must = {
                {"POST", "/private/agent/v1/devices/abc/commands"},
                {"POST", "/private/agent/v1/bulk/commands"},
                {"POST", "/private/devices/deleteBulk"},
                {"POST", "/private/fleet/v1/groups"},
                {"PUT", "/private/fleet/v1/devices/configuration"},
                {"POST", "/private/fleet/v1/groups/3/commands"},
                {"POST", "/private/agent/v1/codes"},
                {"PUT", "/private/scripts"},
                {"POST", "/private/announcements"},
                {"POST", "/private/geofences/v1/zones"},
                {"POST", "/private/reports/v1/schedules"},
                {"POST", "/private/web-ui-files"},
                {"PUT", "/private/roles"},
                {"GET", "/private/fleet/v1/export.xlsx"},
                {"GET", "/private/reports/v1/export"},
                {"POST", "/public/auth/login"},
                {"POST", "/public/auth/logout"},
                {"PUT", "/private/users/current"},
                {"POST", "/private/agent/v1/devices/abc/remote/start"},
        };
        for (String[] r : must) {
            AuditCatalog.Match m = AuditCatalog.find(r[0], r[1]);
            assertNotNull(r[0] + " " + r[1] + " is not in the catalog", m);
            assertEquals(r[0] + " " + r[1], AuditCatalog.Kind.AUDIT, m.rule.kind);
        }
    }

    @Test
    public void matchingReadsThePlaceholders() {
        AuditCatalog.Match m = AuditCatalog.find("delete", "/private/agent/v1/devices/dev%2D1/kioskApps/com.x.y");
        assertNotNull(m);
        assertEquals("dev%2D1", m.params.get("deviceId"));
        assertEquals("com.x.y", m.params.get("pkg"));
        // The upload-part route must not swallow "complete".
        assertEquals("file.upload", AuditCatalog.find("POST", "/private/web-ui-files/chunked/9/complete").rule.action);
        assertEquals(AuditCatalog.Kind.MINOR, AuditCatalog.find("POST", "/private/web-ui-files/chunked/9/3").rule.kind);
        assertEquals("borrado de fábrica (wipe)", AuditCatalog.commandLabel("device.wipe"));
        assertEquals("cambio de clave de desbloqueo", AuditCatalog.commandLabel("device.passcodeReset"));
        assertEquals("tipo.nuevo", AuditCatalog.commandLabel("tipo.nuevo"));
        assertTrue(AuditCatalog.isDisruptive("device.WIPE"));
        assertFalse(AuditCatalog.isDisruptive("device.ring"));
    }
}
