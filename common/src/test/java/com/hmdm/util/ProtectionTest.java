package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.rest.json.agent.CommandHistoryView;
import com.hmdm.rest.json.agent.DesiredConfig;
import com.hmdm.rest.json.agent.DesiredDevice;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Factory-reset protection and remote wipe: the pure rules the server applies. */
public class ProtectionTest {

    private static final String ID_A = "111111111111111111111";
    private static final String ID_B = "222222222222222222222";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Configuration config(String dcPolicy) {
        Configuration c = new Configuration();
        c.setId(7);
        c.setDcPolicy(dcPolicy);
        return c;
    }

    private static DesiredConfig build(String dcPolicy, List<String> inherited) {
        return DesiredConfigBuilder.build(config(dcPolicy), Collections.<Application>emptyList(), Collections.<String>emptySet(),
                null, Collections.<String>emptySet(), null, null, inherited);
    }

    // --- accounts: validation -------------------------------------------------------------------------------------

    @Test
    public void accounts_are_validated_when_saved() {
        String json = FrpAccounts.normalize(Arrays.asList(
                new FrpAccounts.Account(" TI@Acme.com ", " " + ID_A + " "), new FrpAccounts.Account(null, ID_B),
                new FrpAccounts.Account("dup@acme.com", ID_A), new FrpAccounts.Account("", "")));
        List<FrpAccounts.Account> back = FrpAccounts.parse(json);
        assertEquals(2, back.size());
        assertEquals("ti@acme.com", back.get(0).getEmail());
        assertEquals(ID_A, back.get(0).getId());
        assertNull(back.get(1).getEmail());
        assertNull(FrpAccounts.normalize(Collections.<FrpAccounts.Account>emptyList()));
        assertNull(FrpAccounts.normalize(null));
    }

    @Test
    public void an_email_typed_where_the_id_goes_is_refused_by_name() {
        try {
            FrpAccounts.normalize(Collections.singletonList(new FrpAccounts.Account("ti@acme.com", "ti@acme.com")));
            fail("an e-mail is not an account id");
        } catch (IllegalArgumentException e) {
            assertEquals("id:ti@acme.com", e.getMessage());
        }
        try {
            FrpAccounts.normalize(Collections.singletonList(new FrpAccounts.Account("not an email", ID_A)));
            fail("malformed e-mail");
        } catch (IllegalArgumentException e) {
            assertEquals("email:not an email", e.getMessage());
        }
    }

    @Test
    public void a_stored_value_that_is_broken_never_throws() {
        assertTrue(FrpAccounts.parse("{not json").isEmpty());
        assertTrue(FrpAccounts.parse(null).isEmpty());
        assertEquals(1, FrpAccounts.parse("[{\"id\":\"abc\"},{\"id\":\"" + ID_A + "\",\"email\":\"x\"}]").size());
    }

    // --- accounts: inheritance ------------------------------------------------------------------------------------

    private static final List<FrpAccounts.Account> COMPANY = Collections.singletonList(new FrpAccounts.Account("ti@acme.com", ID_A));
    private static final List<FrpAccounts.FolderAccounts> FOLDERS = Arrays.asList(
            new FrpAccounts.FolderAccounts(31, "Bogotá", Collections.<FrpAccounts.Account>emptyList()),
            new FrpAccounts.FolderAccounts(30, "Colombia", Collections.singletonList(new FrpAccounts.Account("co@acme.com", ID_B))));

    @Test
    public void the_policys_own_accounts_win_then_the_nearest_folder_then_the_company() {
        DesiredDevice own = new DesiredDevice();
        own.setFrpAccounts(Collections.singletonList("333333333333333333333"));
        FrpAccounts.Effective e = FrpAccounts.resolve(own, Collections.singletonMap("333333333333333333333", "p@acme.com"), FOLDERS, COMPANY);
        assertEquals("policy", e.source);
        assertEquals("p@acme.com", e.accounts.get(0).getEmail());

        e = FrpAccounts.resolve(null, null, FOLDERS, COMPANY);
        assertEquals("folder", e.source);
        assertEquals(Integer.valueOf(30), e.groupId);
        assertEquals("Colombia", e.groupName);
        assertEquals(Collections.singletonList(ID_B), e.ids());

        e = FrpAccounts.resolve(new DesiredDevice(), null, null, COMPANY);
        assertEquals("company", e.source);
        assertEquals(Collections.singletonList(ID_A), e.ids());

        e = FrpAccounts.resolve(null, null, null, null);
        assertEquals("none", e.source);
        assertTrue(e.ids().isEmpty());
    }

    @Test
    public void a_policy_can_switch_the_protection_off() {
        DesiredDevice off = new DesiredDevice();
        off.setFrpMode("off");
        off.setFrpAccounts(Collections.singletonList(ID_A));
        FrpAccounts.Effective e = FrpAccounts.resolve(off, null, FOLDERS, COMPANY);
        assertEquals("off", e.source);
        assertTrue(e.accounts.isEmpty());
    }

    // --- the document the phone receives ----------------------------------------------------------------------------

    @Test
    public void inherited_accounts_reach_the_phone_when_the_policy_has_none() {
        DesiredConfig d = build(null, Collections.singletonList(ID_A));
        assertEquals(Collections.singletonList(ID_A), d.getDevice().getFrpAccounts());
        assertNull(d.getDevice().getFrpMode());
        // No accounts anywhere and no rules: no device block at all, exactly as before.
        assertNull(build(null, null).getDevice());
        assertNull(build(null, Collections.<String>emptyList()).getDevice());
    }

    @Test
    public void the_policys_own_accounts_are_sent_instead_of_the_inherited_ones() {
        DesiredConfig d = build("{\"device\":{\"factoryReset\":\"block\",\"frpAccounts\":[\"" + ID_B + "\"]}}", Collections.singletonList(ID_A));
        assertEquals(Collections.singletonList(ID_B), d.getDevice().getFrpAccounts());
        assertEquals("block", d.getDevice().getFactoryReset());
    }

    @Test
    public void off_sends_no_accounts_and_the_switch_itself_never_travels() {
        DesiredConfig d = build("{\"device\":{\"factoryReset\":\"block\",\"frpMode\":\"off\"}}", Collections.singletonList(ID_A));
        assertNull(d.getDevice().getFrpAccounts());
        assertNull(d.getDevice().getFrpMode());
        assertFalse(DesiredConfigBuilder.toPayloadJson(d).contains("frpMode"));
        // Only the switch, nothing else: no device block.
        assertNull(build("{\"device\":{\"frpMode\":\"off\"}}", Collections.singletonList(ID_A)).getDevice());
    }

    @Test
    public void changing_the_inherited_accounts_changes_the_revision_so_the_phone_reapplies() {
        String policy = "{\"device\":{\"factoryReset\":\"block\"}}";
        String none = build(policy, null).getRevision();
        String a = build(policy, Collections.singletonList(ID_A)).getRevision();
        String b = build(policy, Collections.singletonList(ID_B)).getRevision();
        assertNotEquals(none, a);
        assertNotEquals(a, b);
        assertEquals(a, build(policy, Collections.singletonList(ID_A)).getRevision());
    }

    @Test
    public void a_policy_without_the_new_rules_keeps_its_revision() {
        String policy = "{\"device\":{\"factoryReset\":\"block\",\"tethering\":\"block\",\"frpAccounts\":[\"" + ID_A + "\"]}}";
        DesiredConfig before = DesiredConfigBuilder.build(config(policy), Collections.<Application>emptyList());
        assertEquals(before.getRevision(), build(policy, null).getRevision());
        assertFalse(DesiredConfigBuilder.canonicalJson(before).contains("safeBoot"));
    }

    @Test
    public void the_new_anti_tamper_rules_are_cleaned_and_sent() {
        DcPolicy p = DcPolicy.parse("{\"device\":{\"safeBoot\":\"BLOCK\",\"debugging\":\"block\",\"oemUnlock\":\"nope\","
                + "\"maxFailedPasswordsForWipe\":10,\"frpMode\":\"OFF\"},\"frpLabels\":{\"" + ID_A + "\":\"x@acme.com\"}}");
        assertEquals("block", p.getDevice().getSafeBoot());
        assertEquals("block", p.getDevice().getDebugging());
        assertNull(p.getDevice().getOemUnlock());
        assertEquals(Integer.valueOf(10), p.getDevice().getMaxFailedPasswordsForWipe());
        assertEquals("off", p.getDevice().getFrpMode());
        assertNull("labels of accounts the policy does not have are dropped", p.getFrpLabels());
        // Out of range: fewer than 3 attempts would wipe a phone on a typo.
        assertNull(DcPolicy.parse("{\"device\":{\"maxFailedPasswordsForWipe\":1}}").getDevice());

        DcPolicy labelled = DcPolicy.parse("{\"device\":{\"frpAccounts\":[\"" + ID_A + "\"]},\"frpLabels\":{\"" + ID_A + "\":\"TI@Acme.com\",\"9\":\"z@z.co\"}}");
        assertEquals(Collections.singletonMap(ID_A, "ti@acme.com"), labelled.getFrpLabels());

        DesiredConfig d = build("{\"device\":{\"safeBoot\":\"block\",\"oemUnlock\":\"block\",\"maxFailedPasswordsForWipe\":8}}", null);
        assertEquals("block", d.getDevice().getSafeBoot());
        assertEquals("block", d.getDevice().getOemUnlock());
        assertEquals(Integer.valueOf(8), d.getDevice().getMaxFailedPasswordsForWipe());
    }

    @Test
    public void the_anti_theft_pin_keeps_every_other_device_rule() {
        Configuration c = config("{\"powerPin\":\"1234\",\"device\":{\"installApps\":\"block\",\"safeBoot\":\"block\"}}");
        c.setKioskMode(true);
        DesiredConfig d = DesiredConfigBuilder.build(c, Collections.<Application>emptyList());
        assertEquals("block", d.getDevice().getFactoryReset());
        assertEquals("block", d.getDevice().getInstallApps());
        assertEquals("block", d.getDevice().getSafeBoot());
    }

    // --- wipe and lost mode -----------------------------------------------------------------------------------------

    @Test
    public void a_wipe_keeps_the_protection_unless_it_is_a_reassignment() throws Exception {
        assertTrue(ProtectionCommands.keepsFrp(null));
        assertTrue(ProtectionCommands.keepsFrp("not json"));
        assertTrue(ProtectionCommands.keepsFrp("{\"intent\":\"lost\"}"));
        assertFalse(ProtectionCommands.keepsFrp("{\"intent\":\"reassign\"}"));
        assertTrue(ProtectionCommands.keepsFrp("{\"intent\":\"reassign\",\"keepFrp\":true}"));
        assertFalse(ProtectionCommands.keepsFrp("{\"intent\":\"lost\",\"keepFrp\":false}"));

        JsonNode n = JSON.readTree(ProtectionCommands.normalizeWipe(
                "{\"intent\":\"reassign\",\"externalStorage\":true,\"euicc\":false,\"reason\":\"  Cambio de\\nusuario  \",\"hack\":1}"));
        assertEquals("reassign", n.get("intent").asText());
        assertFalse(n.get("keepFrp").asBoolean());
        assertTrue(n.get("externalStorage").asBoolean());
        assertFalse(n.has("euicc"));
        assertFalse(n.has("hack"));
        assertEquals("Cambio de usuario", n.get("reason").asText());
        // An older console sends no payload: stored with the safe default made explicit.
        assertEquals("{\"keepFrp\":true}", ProtectionCommands.normalizeWipe(null));
    }

    @Test
    public void what_was_ordered_is_readable_in_the_history() {
        assertEquals("Equipo perdido o robado · protección de restablecimiento (FRP): se conserva · tarjeta SD: se borra · motivo: «Robo»",
                ProtectionCommands.describeWipe("{\"intent\":\"lost\",\"externalStorage\":true,\"reason\":\"Robo\"}"));
        assertEquals("Preparar para reasignar · protección de restablecimiento (FRP): se elimina · tarjeta SD: no se borra · eSIM: se borra · sin aviso en pantalla",
                ProtectionCommands.describeWipe("{\"intent\":\"reassign\",\"euicc\":true,\"silent\":true}"));
        assertEquals(ProtectionCommands.describeWipe("{\"intent\":\"lost\"}"),
                CommandHistoryView.subjectOf("device.wipe", "{\"intent\":\"lost\"}"));
        assertEquals("com.acme.pos", CommandHistoryView.subjectOf("app.install", "{\"packageName\":\"com.acme.pos\"}"));
        assertNull(CommandHistoryView.subjectOf("device.lock", null));
    }

    @Test
    public void lost_mode_options_are_bounded() throws Exception {
        JsonNode n = JSON.readTree(ProtectionCommands.normalizeLostMode(
                "{\"message\":\"Equipo de ACME\",\"phone\":\"+57 300 1234567\",\"ringSeconds\":9999,\"trackMinutes\":0,"
                        + "\"wipeAfterFailedUnlocks\":1,\"wipeIfSilentDays\":\"7\"}"));
        assertTrue(n.get("enabled").asBoolean());
        assertEquals(300, n.get("ringSeconds").asInt());
        assertFalse("0 = the default cadence", n.has("trackMinutes"));
        assertEquals("never fewer than 3 attempts", 3, n.get("wipeAfterFailedUnlocks").asInt());
        assertEquals(7, n.get("wipeIfSilentDays").asInt());
        assertEquals("{\"enabled\":false}", ProtectionCommands.normalizeLostMode("{\"enabled\":false,\"message\":\"x\"}"));
        assertEquals("Desactivar modo perdido", ProtectionCommands.describeLostMode("{\"enabled\":false}"));
        assertEquals("Activar modo perdido · mensaje: «Equipo de ACME» · teléfono de contacto: 3001234567 · sonar 60 s · ubicación cada 1 min"
                        + " · borrar tras 10 intentos fallidos de desbloqueo · borrar si no reporta en 5 días",
                ProtectionCommands.describeLostMode("{\"message\":\"Equipo de ACME\",\"phone\":\"3001234567\",\"ringSeconds\":60,"
                        + "\"wipeAfterFailedUnlocks\":10,\"wipeIfSilentDays\":5}"));
    }

    @Test
    public void the_agents_announcement_is_recognised() {
        assertTrue(ProtectionCommands.isWipeStarting("device.wipe", "accepted", "wipe:starting flags=0"));
        assertFalse(ProtectionCommands.isWipeStarting("device.wipe", "done", "wipe:starting flags=0"));
        assertFalse(ProtectionCommands.isWipeStarting("device.lock", "accepted", "wipe:starting"));
        assertFalse(ProtectionCommands.isWipeStarting("device.wipe", "accepted", null));
    }

    @Test
    public void lost_mode_waits_for_the_phone_like_a_lock_and_a_newer_one_replaces_a_lost_older_one() {
        CommandPolicy.Rule r = CommandPolicy.of("device.lostMode");
        assertEquals(CommandPolicy.NEVER, r.pendingTtlMs);
        assertTrue(r.redelivers());
        AgentCommand c = new AgentCommand();
        c.setType("device.lostMode");
        c.setStatus("pending");
        c.setCreatedAt(0L);
        assertEquals(CommandPolicy.Action.KEEP, CommandPolicy.decide(c, 400L * 24 * 3600_000L, false, false));
        c.setStatus("delivered");
        c.setDeliveredAt(0L);
        c.setDeliveries(1);
        assertEquals(CommandPolicy.Action.SUPERSEDE, CommandPolicy.decide(c, 10 * 60_000L, false, true));
        assertEquals("Modo perdido", CommandLabels.of("device.lostMode"));
    }

    // --- the line the console shows ---------------------------------------------------------------------------------

    private static JsonNode protection(String frp) throws Exception {
        return JSON.readTree("{\"frp\":" + frp + "}");
    }

    @Test
    public void the_summary_says_active_with_how_many_accounts() throws Exception {
        ProtectionSummary s = ProtectionSummary.frp(protection("{\"mechanism\":\"policy\",\"active\":true,\"accounts\":[\"1\",\"2\"]}"), 2);
        assertEquals("active", s.state);
        assertEquals("FRP activa con 2 cuentas", s.text);
        assertEquals("FRP activa con 1 cuenta",
                ProtectionSummary.frp(protection("{\"mechanism\":\"gms\",\"active\":true,\"accounts\":[\"1\"]}"), 1).text);
    }

    @Test
    public void the_summary_says_why_it_is_inactive() throws Exception {
        ProtectionSummary none = ProtectionSummary.frp(protection("{\"mechanism\":\"policy\",\"active\":false,\"reason\":\"noAccounts\"}"), 0);
        assertEquals("inactive", none.state);
        assertTrue(none.text.startsWith("FRP inactiva: no hay cuentas configuradas"));
        assertTrue(none.text.contains("quedaría libre"));

        ProtectionSummary pending = ProtectionSummary.frp(protection("{\"mechanism\":\"policy\",\"active\":false,\"reason\":\"noAccounts\"}"), 1);
        assertEquals("attention", pending.state);
        assertTrue(pending.text.startsWith("FRP pendiente"));

        assertTrue(ProtectionSummary.frp(protection("{\"active\":false,\"reason\":\"unsupported\"}"), 1).text.contains("no admite"));
        assertTrue(ProtectionSummary.frp(protection("{\"active\":false,\"reason\":\"gmsMissing\"}"), 1).text.contains("Servicios de Google Play"));
        ProtectionSummary error = ProtectionSummary.frp(protection("{\"active\":false,\"reason\":\"error\",\"error\":\"SecurityException: x\"}"), 1);
        assertEquals("inactive", error.state);
        assertTrue(error.text.contains("SecurityException: x"));

        ProtectionSummary other = ProtectionSummary.frp(protection("{\"active\":true,\"accounts\":[\"9\"],\"reason\":\"error\"}"), 1);
        assertEquals("attention", other.state);
    }

    @Test
    public void an_agent_that_does_not_report_is_unknown_not_active() {
        assertEquals("unknown", ProtectionSummary.frp(null, 2).state);
        assertEquals("inactive", ProtectionSummary.frp(null, 0).state);
    }
}
