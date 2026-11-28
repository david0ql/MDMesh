package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class KioskExitPolicyTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void generated_passwords_are_eight_digits_acceptable_and_not_repeated() {
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (int i = 0; i < 20; i++) {
            String a = KioskExitPolicy.generate();
            assertTrue(a.matches("^[0-9]{8}$"));
            assertTrue(KioskExitPolicy.isAcceptable(a));
            seen.add(a);
        }
        assertNotEquals("20 generated passwords must not all be the same", 1, seen.size());
    }

    @Test
    public void a_password_needs_four_characters_and_no_control_characters() {
        assertFalse(KioskExitPolicy.isAcceptable(null));
        assertFalse(KioskExitPolicy.isAcceptable(""));
        assertFalse(KioskExitPolicy.isAcceptable("123"));
        assertFalse(KioskExitPolicy.isAcceptable("    "));
        assertFalse(KioskExitPolicy.isAcceptable("12\n45"));
        assertTrue(KioskExitPolicy.isAcceptable("1234"));
        assertTrue(KioskExitPolicy.isAcceptable("Salida segura 2026"));
    }

    @Test
    public void without_a_password_the_exit_is_console_only() {
        assertEquals("remote", KioskExitPolicy.effectiveExitMode("gesture", null));
        assertEquals("remote", KioskExitPolicy.effectiveExitMode("visible", "  "));
        assertEquals("gesture", KioskExitPolicy.effectiveExitMode("gesture", "4821"));
        assertEquals("visible", KioskExitPolicy.effectiveExitMode("visible", "4821"));
    }

    @Test
    public void a_kiosk_enter_payload_without_password_is_sent_with_remote_exit() throws Exception {
        JsonNode n = JSON.readTree(KioskExitPolicy.hardenEnterPayload("{\"mode\":\"single\",\"exitMode\":\"gesture\",\"pinPackage\":\"com.a\"}"));
        assertEquals("remote", n.get("exitMode").asText());
        assertEquals("com.a", n.get("pinPackage").asText());
        assertEquals("remote", JSON.readTree(KioskExitPolicy.hardenEnterPayload("{\"exitMode\":\"visible\",\"password\":\" \"}")).get("exitMode").asText());
        assertEquals("remote", JSON.readTree(KioskExitPolicy.hardenEnterPayload(null)).get("exitMode").asText());
    }

    @Test
    public void a_kiosk_enter_payload_with_password_is_untouched() {
        String p = "{\"mode\":\"launcher\",\"exitMode\":\"gesture\",\"password\":\"4821\"}";
        assertEquals(p, KioskExitPolicy.hardenEnterPayload(p));
        assertEquals("not json", KioskExitPolicy.hardenEnterPayload("not json"));
    }
}
