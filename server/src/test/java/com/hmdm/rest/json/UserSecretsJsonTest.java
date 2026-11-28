package com.hmdm.rest.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.domain.UserRole;
import com.hmdm.rest.json.view.user.UserView;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A user's secrets never leave the server. Whatever resource answers with a user (sign-in, the current user, a
 * password change, the user lists, a customer's administrator…), the JSON of the response must not carry the password
 * hash, the session token, the reset token, the external-auth data or the two-factor secret. The check is on the JSON
 * itself, with the same Jackson the API uses, and on every way a User can end up in a response envelope.
 */
public class UserSecretsJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Distinct markers, so a leak is recognised whatever the property is called. */
    private static final String[] SECRETS = {
            "SECRET-password-hash", "SECRET-old-password", "SECRET-new-password", "SECRET-auth-token",
            "SECRET-reset-token", "SECRET-auth-data", "SECRET-two-factor"
    };

    private static User fullUser(boolean passwordReset) {
        User u = new User();
        u.setId(7);
        u.setLogin("ana");
        u.setName("Ana Ruiz");
        u.setEmail("ana@empresa.co");
        u.setCustomerId(1);
        u.setPassword(SECRETS[0]);
        u.setOldPassword(SECRETS[1]);
        u.setNewPassword(SECRETS[2]);
        u.setAuthToken(SECRETS[3]);
        u.setPasswordResetToken(SECRETS[4]);
        u.setAuthData(SECRETS[5]);
        u.setTwoFactorSecret(SECRETS[6]);
        u.setPasswordReset(passwordReset);
        UserRole role = new UserRole();
        role.setId(3);
        role.setName("User");
        role.setPermissions(new ArrayList<>());
        u.setUserRole(role);
        return u;
    }

    private static void assertNoSecret(String what, String json, String... allowed) {
        Set<String> ok = new HashSet<>(Arrays.asList(allowed));
        for (String secret : SECRETS) {
            if (ok.contains(secret)) continue;
            assertFalse(what + " leaks " + secret + ": " + json, json.contains(secret));
        }
        for (String name : new String[]{"\"password\"", "\"oldPassword\"", "\"newPassword\"", "\"authToken\"", "\"authData\"", "\"twoFactorSecret\""}) {
            assertFalse(what + " has the property " + name + ": " + json, json.contains(name));
        }
    }

    @Test
    public void aUserInAnEnvelopeCarriesNoSecret() throws Exception {
        // What PUT /private/users/current, GET /private/users/current, the user lists… answer with.
        String json = JSON.writeValueAsString(Response.OK("success.operation.completed", fullUser(false)));
        assertNoSecret("Response.OK(user)", json);
        assertTrue(json, json.contains("\"login\":\"ana\"") && json.contains("\"email\":\"ana@empresa.co\""));
        assertFalse(json, json.contains("passwordResetToken"));

        String list = JSON.writeValueAsString(Response.OK(Arrays.asList(fullUser(false), fullUser(true))));
        assertNoSecret("Response.OK(list of users)", list);
    }

    @Test
    public void theSignInAnswerCarriesOnlyTheResetTokenWhileAResetIsForced() throws Exception {
        String normal = JSON.writeValueAsString(Response.OK(new UserView(fullUser(false))));
        assertNoSecret("sign-in", normal);
        assertFalse(normal, normal.contains("passwordResetToken"));

        // First sign-in with an assigned password: the "set password" page needs the token, and nothing else.
        String forced = JSON.writeValueAsString(Response.OK(new UserView(fullUser(true))));
        assertNoSecret("sign-in with a forced reset", forced, SECRETS[4]);
        assertTrue(forced, forced.contains("\"passwordResetToken\":\"" + SECRETS[4] + "\""));
    }

    @Test
    public void requestsStillCarryThePasswordsIn() throws Exception {
        // Write-only, not ignored: a password change must still be read from the request body.
        User in = JSON.readValue("{\"id\":7,\"login\":\"ana\",\"oldPassword\":\"A\",\"newPassword\":\"B\",\"password\":\"C\"}", User.class);
        assertEquals("A", in.getOldPassword());
        assertEquals("B", in.getNewPassword());
        assertEquals("C", in.getPassword());
        assertNull(in.getAuthToken());
    }

    /**
     * Systematic guard: every String property that Jackson would WRITE for a User or a UserView is listed here. A new
     * property makes the test fail until someone decides it is not a secret — a field such as "apiKey" cannot slip
     * into the responses unnoticed.
     */
    @Test
    public void everySerialisedPropertyOfAUserIsKnown() throws Exception {
        Set<String> expectedUser = new TreeSet<>(Arrays.asList(
                "id", "login", "email", "name", "customerId", "userRole", "allDevicesAvailable", "allConfigAvailable",
                "passwordReset", "twoFactorAccepted", "twoFactor", "idleLogout", "lastLoginFail", "groups",
                "configurations", "masterCustomer", "editable", "singleCustomer", "superAdmin", "common"));
        User u = fullUser(true);
        u.setGroups(new ArrayList<>());
        u.setConfigurations(new ArrayList<>());
        u.setIdleLogout(10);
        Set<String> actual = new TreeSet<>();
        JSON.readTree(JSON.writeValueAsString(u)).fieldNames().forEachRemaining(actual::add);
        Set<String> unknown = new TreeSet<>(actual);
        unknown.removeAll(expectedUser);
        assertTrue("User now serialises properties this test does not know (a secret? mark it write-only; otherwise "
                + "add it to the list): " + unknown, unknown.isEmpty());

        Set<String> view = new TreeSet<>();
        JSON.readTree(JSON.writeValueAsString(new UserView(u))).fieldNames().forEachRemaining(view::add);
        Set<String> expectedView = new TreeSet<>(expectedUser);
        expectedView.add("passwordResetToken"); // only while a reset is forced (see above)
        view.removeAll(expectedView);
        assertTrue("UserView now serialises properties this test does not know: " + view, view.isEmpty());
    }

    /**
     * Every resource method that can answer with a domain object declares it through the generic Response envelope, so
     * the types cannot be read from the signatures; what CAN be checked is that no domain or view class reachable from
     * the API has a readable String property whose name says "secret" other than the ones reviewed here.
     */
    @Test
    public void noDomainClassExposesAnUnreviewedSecretProperty() throws Exception {
        // Reviewed: they are settings an administrator edits in the console, or values the caller must receive once.
        Set<String> reviewed = new HashSet<>(Arrays.asList(
                "Configuration.password",            // the kiosk exit password of a policy: a setting edited in the console
                "Configuration.wifiPassword",        // legacy provisioning Wi-Fi of a policy: idem
                "AgentEnrollmentToken.token",        // the enrollment code: shown to whoever may enroll (enroll_devices)
                "AgentEnrollmentToken.wifiPassword", // provisioning Wi-Fi of an enrollment code: idem
                "EnrollmentCodeView.wifiPassword",   // the same, in the list of codes (needs enroll_devices)
                "Configuration.passwordMode",        // which kind of kiosk password a policy asks for: a setting
                "AgentEnrollAttempt.tokenLabel",     // the human label of the code a device tried, not the code
                "ApplicationVersion.apkHash",        // the public checksum of an APK
                "PendingSignup.token"                // sign-up is not part of the reachable API (RestSurfaceFilter)
        ));
        Set<String> leaks = new TreeSet<>();
        for (Class<?> c : classesOf(com.hmdm.persistence.domain.User.class, "com.hmdm.persistence.domain")) {
            for (Method m : c.getMethods()) {
                if (m.getParameterCount() != 0 || m.getReturnType() != String.class || !m.getName().startsWith("get")) continue;
                String prop = Character.toLowerCase(m.getName().charAt(3)) + m.getName().substring(4);
                if (!com.hmdm.audit.AuditRedactor.isSecretName(prop)) continue;
                String key = c.getSimpleName() + "." + prop;
                if (reviewed.contains(key)) continue;
                if (!writes(c, prop)) continue;
                leaks.add(key);
            }
        }
        assertTrue("Domain classes whose JSON would carry a secret-looking property (make it write-only with "
                + "@JsonProperty(access = WRITE_ONLY) / @JsonIgnore, or review it and add it to the list): " + leaks, leaks.isEmpty());
    }

    /** Whether Jackson writes this property of the class (asked to Jackson itself, so annotations count). */
    private static boolean writes(Class<?> c, String prop) {
        try {
            com.fasterxml.jackson.databind.JavaType type = JSON.constructType(c);
            com.fasterxml.jackson.databind.BeanDescription d = JSON.getSerializationConfig().introspect(type);
            for (com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition p : d.findProperties()) {
                if (p.getName().equals(prop)) return p.couldSerialize();
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static List<Class<?>> classesOf(Class<?> anchor, String pkg) throws Exception {
        File root = new File(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Class<?>> out = new ArrayList<>();
        if (root.isDirectory()) {
            File[] files = new File(root, pkg.replace('.', '/')).listFiles();
            assertNotNull(files);
            for (File f : files) {
                String n = f.getName();
                if (n.endsWith(".class") && !n.contains("$")) out.add(Class.forName(pkg + "." + n.substring(0, n.length() - 6)));
            }
        } else {
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(root)) {
                String prefix = pkg.replace('.', '/') + "/";
                for (java.util.Enumeration<java.util.jar.JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                    String n = e.nextElement().getName();
                    if (n.startsWith(prefix) && n.endsWith(".class") && !n.contains("$") && n.indexOf('/', prefix.length()) < 0) {
                        out.add(Class.forName(n.substring(0, n.length() - 6).replace('/', '.')));
                    }
                }
            }
        }
        assertTrue("domain classes not found", out.size() > 20);
        return out;
    }
}
