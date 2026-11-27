package com.hmdm.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The audit's pure pieces: what is kept of a body (no secrets), how a result is read, and the before/after. */
public class AuditPiecesTest {

    // --- secrets never reach the trail ---------------------------------------------------------------------------

    @Test
    public void secretsAreMaskedWhateverTheRoute() {
        String body = "{\"login\":\"ana\",\"password\":\"5F4DCC3B5AA765D61D8327DEB882CF99\",\"newPassword\":\"x\","
                + "\"wifiPassword\":\"clave-wifi\",\"wifiSsid\":\"Bodega\",\"passwordResetToken\":\"tok\","
                + "\"authToken\":\"t\",\"nested\":{\"apiKey\":\"k\",\"secret\":\"s\",\"name\":\"ok\"},\"pin\":\"1234\"}";
        String out = AuditRedactor.write(AuditRedactor.sanitize(body));
        for (String leaked : new String[]{"5F4DCC3B", "clave-wifi", "\"tok\"", "\"1234\"", "\"k\"", "\"s\"", "\"t\"", "\"x\""}) {
            assertFalse("leaked " + leaked + " in " + out, out.contains(leaked));
        }
        assertTrue(out.contains("\"login\":\"ana\""));
        assertTrue(out.contains("\"wifiSsid\":\"Bodega\""));
        assertTrue(out.contains("\"name\":\"ok\""));
        assertTrue(out.contains(AuditRedactor.MASK));
    }

    @Test
    public void aCommandPayloadSentAsTextIsCleanedToo() {
        // The agent command body carries its payload as a JSON string: the new unlock code must not be kept.
        String body = "{\"type\":\"device.passcodeReset\",\"payload\":\"{\\\"passcode\\\":\\\"987654\\\",\\\"lockNow\\\":true}\"}";
        String out = AuditRedactor.write(AuditRedactor.sanitize(body));
        assertFalse(out, out.contains("987654"));
        assertTrue(out, out.contains("device.passcodeReset"));
        assertTrue(out, out.contains("lockNow"));
    }

    @Test
    public void namesThatOnlyLookSecretAreKept() {
        assertFalse(AuditRedactor.isSecretName("passwordReset"));
        assertFalse(AuditRedactor.isSecretName("name"));
        assertFalse(AuditRedactor.isSecretName("mapping"));
        assertFalse(AuditRedactor.isSecretName("configurationPinned"));
        assertFalse(AuditRedactor.isSecretName("description"));
        assertTrue(AuditRedactor.isSecretName("password"));
        assertTrue(AuditRedactor.isSecretName("passwordresettoken"));
        assertTrue(AuditRedactor.isSecretName("twoFactorSecret"));
        assertTrue(AuditRedactor.isSecretName("agentsecrethash"));
        assertTrue(AuditRedactor.isSecretName("token"));
        assertTrue(AuditRedactor.isSecretName("Authorization"));
    }

    @Test
    public void longValuesAndListsAreCut() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2000; i++) big.append('a');
        StringBuilder ids = new StringBuilder("[");
        for (int i = 0; i < 500; i++) ids.append(i == 0 ? "" : ",").append(i);
        ids.append("]");
        String out = AuditRedactor.write(AuditRedactor.sanitize("{\"body\":\"" + big + "\",\"deviceIds\":" + ids + "}"));
        assertTrue(out.length() < AuditRedactor.MAX_DETAIL);
        assertTrue(out.contains("2000 caracteres"));
        assertTrue(out.contains("500 en total"));
        assertNull(AuditRedactor.sanitize("   "));
        assertNull(AuditRedactor.write(AuditRedactor.sanitize("{}")));
        assertEquals("\"Bodega norte\"", AuditRedactor.write(AuditRedactor.sanitize("Bodega norte")));
    }

    @Test
    public void aDetailTooLongStaysValidJson() throws Exception {
        ObjectNode o = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        for (int i = 0; i < 400; i++) o.put("campo" + i, "valor-" + i + "-xxxxxxxxxxxxxxxxxxxxxxxx");
        String out = AuditRedactor.write(o);
        assertTrue(out.length() <= AuditRedactor.MAX_DETAIL);
        JsonNode back = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out);
        assertTrue(back.get("recortado").asBoolean());
    }

    // --- how it ended --------------------------------------------------------------------------------------------

    @Test
    public void theEnvelopeDecidesTheResult() {
        assertEquals("ok", AuditOutcome.of(200, "{\"status\":\"OK\",\"message\":null,\"data\":{}}", true, false).result);
        AuditOutcome denied = AuditOutcome.of(200, "{\"status\":\"ERROR\",\"message\":\"error.permission.denied\",\"data\":null}", true, false);
        assertEquals("denied", denied.result);
        assertEquals("Permiso denegado", denied.message);
        AuditOutcome err = AuditOutcome.of(200, "{\"status\":\"ERROR\",\"message\":\"Elige al menos una carpeta.\"}", true, false);
        assertEquals("error", err.result);
        assertEquals("Elige al menos una carpeta.", err.message);
        assertEquals("El dispositivo no existe", AuditOutcome.of(200, "{\"status\":\"ERROR\",\"message\":\"error.agent.device.unknown\"}", true, false).message);
    }

    @Test
    public void httpRefusalsAndSignIn() {
        assertEquals("denied", AuditOutcome.of(403, null, false, false).result);
        assertEquals("denied", AuditOutcome.of(401, null, false, false).result);
        assertEquals("error", AuditOutcome.of(500, null, false, false).result);
        assertEquals("error", AuditOutcome.of(404, null, false, false).result);
        assertEquals("ok", AuditOutcome.of(204, null, false, false).result);
        // A wrong password answers 200 + ERROR without a message: for a sign-in that is a refusal.
        assertEquals("denied", AuditOutcome.of(200, "{\"status\":\"ERROR\",\"message\":null,\"data\":null}", true, true).result);
        assertEquals("ok", AuditOutcome.of(200, "{\"status\":\"OK\",\"data\":{\"id\":1}}", true, true).result);
    }

    @Test
    public void dataThatMentionsAnErrorIsNotARefusal() {
        // Only the envelope's own status counts, not a "status":"ERROR" somewhere inside the data.
        String body = "{\"status\":\"OK\",\"message\":null,\"data\":[{\"status\":\"ERROR\",\"message\":\"error.permission.denied\"}]}";
        assertEquals("ok", AuditOutcome.of(200, body, true, false).result);
        // An export is never parsed.
        assertEquals("ok", AuditOutcome.of(200, "{\"status\":\"ERROR\"", false, false).result);
    }

    // --- before / after ------------------------------------------------------------------------------------------

    private static Map<String, String> row(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    public void onlyWhatChangedIsReported() {
        Map<String, String> before = row("id", "4", "name", "Bodega", "configurationid", "Kiosko", "lastupdate", "1", "password", "aaa");
        Map<String, String> after = row("id", "4", "name", "Bodega norte", "configurationid", "Kiosko", "lastupdate", "2", "password", "bbb");
        ObjectNode c = AuditDiff.changes(before, after);
        assertEquals(2, c.size());
        assertEquals("Bodega", c.get("nombre").get("antes").asText());
        assertEquals("Bodega norte", c.get("nombre").get("despues").asText());
        // A changed secret is reported without either value; a timestamp is not a change.
        assertEquals("modificado", c.get("contraseña").asText());
        assertFalse(c.toString().contains("aaa") || c.toString().contains("bbb"));
        assertEquals(0, AuditDiff.changes(before, before).size());
    }

    @Test
    public void addedAndRemovedValues() {
        ObjectNode c = AuditDiff.changes(row("permisos", "edit_devices"), row("permisos", "edit_devices, disruptive_actions"));
        assertEquals("edit_devices, disruptive_actions", c.get("permisos").get("despues").asText());
        c = AuditDiff.changes(row("email", null), row("email", "a@b.co"));
        assertTrue(c.get("correo").get("antes").isNull());
    }

    @Test
    public void aDeletedRowKeepsItsShortPublicFields() {
        ObjectNode s = AuditDiff.summary(row("id", "9", "login", "ana", "password", "hash", "authtoken", "t", "email", "a@b.co", "customerid", "1"));
        assertEquals("ana", s.get("usuario").asText());
        assertEquals("a@b.co", s.get("correo").asText());
        assertFalse(s.toString().contains("hash"));
        assertFalse(s.has("authtoken"));
    }
}
