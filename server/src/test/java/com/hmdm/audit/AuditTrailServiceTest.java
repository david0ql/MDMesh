package com.hmdm.audit;

import com.hmdm.persistence.AuditTrailDAO;
import com.hmdm.persistence.domain.AuditEntry;
import com.hmdm.persistence.mapper.AuditTrailMapper;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What ends up in the audit trail for the requests that matter: who, what (in Spanish), on what (name, id, devices,
 * folders), the result, and the summary without secrets. The database is a stub that answers by method name.
 */
public class AuditTrailServiceTest {

    private static final String OK = "{\"status\":\"OK\",\"message\":null,\"data\":{}}";
    private static final String DENIED = "{\"status\":\"ERROR\",\"message\":\"error.permission.denied\",\"data\":null}";

    private final List<AuditEntry> written = new ArrayList<>();
    /** method name (or "name:firstArg") → what the database answers. */
    private final Map<String, Object> answers = new HashMap<>();
    private AuditTrailService service;

    @Before
    public void setUp() {
        AuditTrailMapper db = (AuditTrailMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AuditTrailMapper.class}, (proxy, method, args) -> {
                    String key = method.getName() + (args != null && args.length > 0 ? ":" + args[0] : "");
                    Object v = answers.containsKey(key) ? answers.get(key) : answers.get(method.getName());
                    if (v instanceof List && !List.class.isAssignableFrom(method.getReturnType())) {
                        // A queue of answers: one per call (the row before and after an edit).
                        @SuppressWarnings("unchecked") List<Object> q = (List<Object>) v;
                        return q.isEmpty() ? null : q.remove(0);
                    }
                    if (v != null) return v;
                    if (List.class.isAssignableFrom(method.getReturnType())) return new ArrayList<>();
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
        AuditTrailDAO dao = new AuditTrailDAO(db) {
            @Override
            public void append(AuditEntry entry) {
                written.add(entry);
            }
        };
        service = new AuditTrailService(dao);
        answers.put("userById:7", user(7, "tecnico1", "Técnico Uno", 3, "User"));
        answers.put("deviceByNumber:dev-1", device(11, "dev-1", "Tablet bodega", "5"));
    }

    private static Map<String, Object> user(int id, String login, String name, int roleId, String roleName) {
        Map<String, Object> u = new HashMap<>();
        u.put("id", id);
        u.put("login", login);
        u.put("name", name);
        u.put("customerid", 1);
        u.put("roleid", roleId);
        u.put("rolename", roleName);
        return u;
    }

    private static Map<String, Object> device(int id, String number, String description, String groups) {
        Map<String, Object> d = new HashMap<>();
        d.put("id", id);
        d.put("number", number);
        d.put("description", description);
        d.put("customerid", 1);
        d.put("groups", groups);
        return d;
    }

    private AuditTrailService.Seen seen(String method, String path, Integer userId, String body) {
        AuditTrailService.Seen s = new AuditTrailService.Seen();
        s.method = method;
        s.path = path;
        s.userId = userId;
        s.rawBody = body;
        s.ip = "190.0.0.7";
        s.userAgent = "Mozilla/5.0 (prueba)";
        s.contentType = body == null ? null : "application/json";
        s.at = 1_800_000_000_000L;
        return s;
    }

    private AuditEntry run(AuditTrailService.Seen s, int status, String response) {
        written.clear();
        service.before(s);
        service.after(s, status, response, response != null, s.userId);
        service.flush();
        return written.isEmpty() ? null : written.get(written.size() - 1);
    }

    // --- commands ------------------------------------------------------------------------------------------------

    @Test
    public void aWipeWithoutPermissionIsRecordedAsDenied() {
        AuditEntry e = run(seen("POST", "/private/agent/v1/devices/dev-1/commands", 7, "{\"type\":\"device.wipe\"}"), 200, DENIED);
        assertNotNull(e);
        assertEquals("denied", e.result);
        assertEquals("comandos", e.category);
        assertEquals("device.command:device.wipe", e.action);
        assertEquals("Comando al dispositivo: borrado de fábrica (wipe)", e.label);
        assertEquals("tecnico1", e.login);
        assertEquals("Técnico Uno", e.userName);
        assertEquals("User", e.roleName);
        assertEquals(Integer.valueOf(1), e.customerId);
        assertEquals("190.0.0.7", e.ip);
        assertEquals("Mozilla/5.0 (prueba)", e.userAgent);
        assertEquals("device", e.targetType);
        assertEquals("dev-1", e.targetId);
        assertEquals("Tablet bodega", e.targetName);
        assertEquals("{\"dev-1\"}", e.deviceNumbersLiteral);
        assertEquals("{5}", e.groupIdsLiteral);
        assertTrue(e.detail, e.detail.contains("\"accionDisruptiva\":true"));
        assertEquals(1_800_000_000_000L, e.createdAt);
    }

    @Test
    public void anAllowedCommandIsRecordedAsOkAndThePasscodeIsNotKept() {
        AuditEntry e = run(seen("POST", "/private/agent/v1/devices/dev-1/commands", 7,
                "{\"type\":\"device.passcodeReset\",\"payload\":\"{\\\"passcode\\\":\\\"4321\\\"}\"}"), 200, OK);
        assertEquals("ok", e.result);
        assertEquals("Comando al dispositivo: cambio de clave de desbloqueo", e.label);
        assertFalse(e.detail, e.detail.contains("4321"));
        assertNull(e.message);
    }

    @Test
    public void aBulkCommandNamesEveryDeviceAndFolder() {
        answers.put("devicesByIds", Arrays.asList(device(11, "dev-1", "Tablet bodega", "5"), device(12, "dev-2", "", "6,7")));
        AuditEntry e = run(seen("POST", "/private/agent/v1/bulk/commands", 7,
                "{\"deviceIds\":[11,12],\"command\":{\"type\":\"device.reboot\"}}"), 200, OK);
        assertEquals("Comando masivo: reinicio", e.label);
        assertEquals("devices", e.targetType);
        assertEquals("2 dispositivos", e.targetName);
        assertEquals("{\"dev-1\",\"dev-2\"}", e.deviceNumbersLiteral);
        assertEquals("{5,6,7}", e.groupIdsLiteral);
        assertTrue(e.detail, e.detail.contains("Tablet bodega (dev-1)"));
    }

    @Test
    public void aFolderCommandReachesTheDevicesBelowIt() {
        answers.put("rowJson", "{\"id\":5,\"name\":\"Bodega\",\"customerid\":1}");
        answers.put("groupSubtree", Arrays.asList(5, 9));
        answers.put("deviceNumbersOfGroups", Arrays.asList("dev-1", "dev-9"));
        AuditEntry e = run(seen("POST", "/private/fleet/v1/groups/5/commands", 7, "{\"command\":{\"type\":\"device.lock\"}}"), 200, OK);
        assertEquals("Comando a la carpeta: bloqueo de pantalla", e.label);
        assertEquals("group", e.targetType);
        assertEquals("Bodega", e.targetName);
        assertEquals("{5,9}", e.groupIdsLiteral);
        assertEquals("{\"dev-1\",\"dev-9\"}", e.deviceNumbersLiteral);
    }

    // --- remote control ------------------------------------------------------------------------------------------

    @Test
    public void closingARemoteSessionRecordsItsDuration() {
        answers.put("lastRemoteStart", 1_800_000_000_000L - 200_000L);
        AuditEntry e = run(seen("POST", "/private/agent/v1/devices/dev-1/commands", 7, "{\"type\":\"remote.vnc.stop\"}"), 200, OK);
        assertEquals("remoto", e.category);
        assertEquals("remote.session.stop", e.action);
        assertEquals("Fin de sesión de control remoto", e.label);
        assertEquals(Integer.valueOf(200_000), e.durationMs);
        assertTrue(e.detail, e.detail.contains("3 min 20 s"));

        AuditEntry start = run(seen("POST", "/private/agent/v1/devices/dev-1/remote/start", 7, "{\"viewOnly\":true}"), 200,
                "{\"status\":\"OK\",\"message\":null,\"data\":{\"sessionId\":\"123\",\"password\":\"secreta\"}}");
        assertEquals("remote.session.start", start.action);
        assertEquals("Inicio de sesión de control remoto", start.label);
        // The session password is in the response, which is never stored.
        assertFalse(start.detail, start.detail.contains("secreta"));
    }

    // --- sign-in -------------------------------------------------------------------------------------------------

    @Test
    public void aFailedSignInIsRecordedWithTheNameTried() {
        AuditEntry e = run(seen("POST", "/public/auth/login", null, "{\"login\":\"intruso\",\"password\":\"AAAA1111\"}"), 200,
                "{\"status\":\"ERROR\",\"message\":null,\"data\":null}");
        assertNotNull(e);
        assertEquals("Inicio de sesión fallido", e.label);
        assertEquals("denied", e.result);
        assertEquals("intruso", e.login);
        assertNull(e.userId);
        assertNull(e.customerId);
        assertFalse(e.detail, e.detail.contains("AAAA1111"));
    }

    @Test
    public void aFailedSignInOfARealAccountBelongsToItsCustomer() {
        answers.put("userByLogin:tecnico1", user(7, "tecnico1", "Técnico Uno", 3, "User"));
        AuditEntry e = run(seen("POST", "/public/auth/login", null, "{\"login\":\"tecnico1\",\"password\":\"X\"}"), 200,
                "{\"status\":\"ERROR\",\"message\":null,\"data\":null}");
        assertEquals(Integer.valueOf(7), e.userId);
        assertEquals(Integer.valueOf(1), e.customerId);
        assertEquals("denied", e.result);
    }

    @Test
    public void signInAndSignOut() {
        AuditTrailService.Seen s = seen("POST", "/public/auth/login", null, "{\"login\":\"tecnico1\",\"password\":\"X\"}");
        written.clear();
        service.before(s);
        service.after(s, 200, "{\"status\":\"OK\",\"data\":{\"id\":7}}", true, 7); // the session now holds user 7
        service.flush();
        assertEquals("Inicio de sesión", written.get(0).label);
        assertEquals("ok", written.get(0).result);
        assertEquals("tecnico1", written.get(0).login);

        AuditEntry out = run(seen("POST", "/public/auth/logout", 7, null), 204, null);
        assertEquals("Cierre de sesión", out.label);
        assertEquals("sesion", out.category);
    }

    // --- edits: before / after -----------------------------------------------------------------------------------

    @Test
    public void anEditedUserShowsWhatChanged() {
        answers.put("rowJson", new ArrayList<>(Arrays.asList(
                "{\"id\":9,\"login\":\"ana\",\"name\":\"Ana\",\"password\":\"h1\",\"userroleid\":200,\"customerid\":1}",
                "{\"id\":9,\"login\":\"ana\",\"name\":\"Ana Ruiz\",\"password\":\"h2\",\"userroleid\":3,\"customerid\":1}")));
        answers.put("roleName:200", "Visor");
        answers.put("roleName:3", "User");
        answers.put("userFolders", "Bodega");
        answers.put("userFolderIds", Collections.singletonList(5));
        AuditEntry e = run(seen("PUT", "/private/dc/users", 7,
                "{\"id\":9,\"login\":\"ana\",\"name\":\"Ana Ruiz\",\"password\":\"0CC175B9C0F1B6A831C399E269772661\",\"role\":\"folders\",\"folders\":[5]}"), 200, OK);
        assertEquals("usuarios", e.category);
        assertEquals("user", e.targetType);
        assertEquals("9", e.targetId);
        assertEquals("ana", e.targetName);
        assertEquals("{5}", e.groupIdsLiteral);
        assertTrue(e.detail, e.detail.contains("\"nombre\":{\"antes\":\"Ana\",\"despues\":\"Ana Ruiz\"}"));
        assertTrue(e.detail, e.detail.contains("\"rol\":{\"antes\":\"Visor\",\"despues\":\"User\"}"));
        assertTrue(e.detail, e.detail.contains("\"contraseña\":\"modificado\""));
        assertFalse(e.detail, e.detail.contains("0CC175B9") || e.detail.contains("h1") || e.detail.contains("h2"));
    }

    @Test
    public void aDeletedFolderKeepsItsName() {
        answers.put("rowJson", new ArrayList<>(Collections.singletonList("{\"id\":5,\"name\":\"Bodega\",\"customerid\":1}")));
        AuditEntry e = run(seen("DELETE", "/private/fleet/v1/groups/5", 7, null), 200, OK);
        assertEquals("Eliminación de carpeta", e.label);
        assertEquals("Bodega", e.targetName);
        assertTrue(e.detail, e.detail.contains("\"eliminado\":{\"nombre\":\"Bodega\"}"));
        assertEquals("{5}", e.groupIdsLiteral);
    }

    @Test
    public void aRowOfAnotherCustomerIsNeverNamed() {
        answers.put("deviceByNumber:ajeno", device(99, "ajeno", "Equipo de otro cliente", "50"));
        ((Map<String, Object>) answers.get("deviceByNumber:ajeno")).put("customerid", 2);
        AuditEntry e = run(seen("POST", "/private/agent/v1/devices/ajeno/commands", 7, "{\"type\":\"device.lock\"}"), 200, DENIED);
        assertEquals("ajeno", e.targetId);
        assertNull(e.targetName);
        assertNull(e.groupIdsLiteral);
    }

    // --- what is, and is not, recorded ---------------------------------------------------------------------------

    @Test
    public void readsAreRecordedOnlyWhenRefusedAndOnlyOncePerMinute() {
        assertNull(run(seen("GET", "/private/agent/v1/devices/dev-1/state", 7, null), 200, OK));
        AuditEntry e = run(seen("GET", "/private/agent/v1/devices/otro/state", 7, null), 403, null);
        assertNotNull(e);
        assertEquals("denied", e.result);
        assertTrue(e.label, e.label.startsWith("Consulta denegada"));
        assertNull("the same refused read within a minute is one row", run(seen("GET", "/private/agent/v1/devices/otro/state", 7, null), 403, null));
        // A search that uses POST is a read too.
        assertNull(run(seen("POST", "/private/devices/search", 7, "{}"), 200, OK));
    }

    @Test
    public void anExportIsRecordedAlthoughItIsARead() {
        AuditTrailService.Seen s = seen("GET", "/private/fleet/v1/export.xlsx", 7, null);
        s.query = "group=5&tz=America%2FBogota";
        answers.put("rowJson", "{\"id\":5,\"name\":\"Bodega\",\"customerid\":1}");
        AuditEntry e = run(s, 200, null);
        assertEquals("Exportación de dispositivos (Excel)", e.label);
        assertEquals("reportes", e.category);
        assertEquals("Bodega", e.targetName);
        assertTrue(e.detail, e.detail.contains("\"tz\":\"America/Bogota\""));
    }

    @Test
    public void aMutatingRouteMissingFromTheCatalogIsStillRecorded() {
        AuditEntry e = run(seen("POST", "/private/algo/nuevo/12", 7, "{\"x\":1}"), 200, OK);
        assertNotNull(e);
        assertEquals("other:POST /private/algo/nuevo/{id}", e.action);
        assertEquals("Operación: POST /private/algo/nuevo/12", e.label);
    }

    @Test
    public void whatCannotBeAttributedOrIsNotAUsersActionIsSkipped() {
        // No session: nobody to attribute it to.
        assertNull(run(seen("POST", "/private/agent/v1/devices/dev-1/commands", null, "{\"type\":\"device.lock\"}"), 403, null));
        // The device's own calls.
        assertNull(run(seen("POST", "/public/agent/v1/checkin", null, "{}"), 200, OK));
        // An upload part, an interface preference.
        assertNull(run(seen("POST", "/private/web-ui-files/chunked/3/1", 7, null), 200, OK));
        assertNull(run(seen("POST", "/private/hints/history", 7, "{}"), 200, OK));
    }

    @Test
    public void helpers() {
        assertEquals("45 s", AuditTrailService.humanDuration(45_000));
        assertEquals("1 h 5 min", AuditTrailService.humanDuration(65 * 60_000L));
        assertEquals("{\"a\\\"b\",\"c\"}", AuditTrailService.textArray(Arrays.asList("a\"b", "c")));
        assertNull(AuditTrailService.intArray(Collections.<Integer>emptyList()));
        assertEquals("5", AuditTrailService.queryParam("a=1&group=5", "group"));
        assertTrue(AuditTrailService.queryNode("token=abc&x=1").toString().contains(AuditRedactor.MASK));
    }
}
