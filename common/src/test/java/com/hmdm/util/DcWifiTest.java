package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.rest.json.agent.DesiredConfig;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class DcWifiTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static DcPolicy policy(String json) {
        return DcPolicy.parse(json);
    }

    // ---------------------------------------------------------------------------------------------- cleaning

    @Test
    public void every_security_is_kept_and_unknown_ones_become_wpa() {
        DcPolicy p = policy("{\"wifi\":[{\"ssid\":\"a\",\"security\":\"wpa3\",\"password\":\"12345678\"},"
                + "{\"ssid\":\"b\",\"security\":\"OWE\",\"password\":\"x\"},{\"ssid\":\"c\",\"security\":\"WPA3_TRANSITION\",\"password\":\"12345678\"},"
                + "{\"ssid\":\"d\",\"security\":\"EAP\"},{\"ssid\":\"e\",\"security\":\"EAP_WPA3\"},{\"ssid\":\"f\",\"security\":\"bogus\"}]}");
        assertEquals("WPA3", p.getWifi().get(0).getSecurity());
        assertEquals("OWE", p.getWifi().get(1).getSecurity());
        assertNull("OWE has no key", p.getWifi().get(1).getPassword());
        assertEquals("WPA3_TRANSITION", p.getWifi().get(2).getSecurity());
        assertEquals("EAP", p.getWifi().get(3).getSecurity());
        assertEquals("PEAP", p.getWifi().get(3).getEap().getMethod());
        assertEquals("EAP_WPA3", p.getWifi().get(4).getSecurity());
        assertEquals("WPA", p.getWifi().get(5).getSecurity());
    }

    @Test
    public void network_options_are_validated() {
        DcPolicy p = policy("{\"wifi\":[{\"ssid\":\"a\",\"password\":\"12345678\",\"hidden\":true,\"autoConnect\":false,\"priority\":7,"
                + "\"mac\":\"DEVICE\",\"metered\":\"Unmetered\","
                + "\"proxy\":{\"mode\":\"manual\",\"host\":\"proxy.corp.co\",\"port\":3128,\"exclude\":[\"*.corp.co\",\"bad host\"]},"
                + "\"ip\":{\"address\":\"10.0.0.50\",\"prefix\":24,\"gateway\":\"10.0.0.1\",\"dns\":[\"8.8.8.8\",\"nope\"]}},"
                + "{\"ssid\":\"b\",\"security\":\"NONE\",\"priority\":5000,\"mac\":\"x\",\"metered\":\"y\",\"autoConnect\":true,"
                + "\"proxy\":{\"mode\":\"pac\",\"pacUrl\":\"ftp://x\"},\"ip\":{\"address\":\"999.1.1.1\"}},"
                + "{\"ssid\":\"c\",\"security\":\"NONE\",\"proxy\":{\"mode\":\"pac\",\"pacUrl\":\"https://corp.co/proxy.pac\"}}]}");
        DcPolicy.Wifi a = p.getWifi().get(0), b = p.getWifi().get(1), c = p.getWifi().get(2);
        assertEquals(Boolean.TRUE, a.getHidden());
        assertEquals(Boolean.FALSE, a.getAutoConnect());
        assertEquals(Integer.valueOf(7), a.getPriority());
        assertEquals("device", a.getMac());
        assertEquals("unmetered", a.getMetered());
        assertEquals("proxy.corp.co", a.getProxy().getHost());
        assertEquals(Integer.valueOf(3128), a.getProxy().getPort());
        assertEquals(Collections.singletonList("*.corp.co"), a.getProxy().getExclude());
        assertEquals("10.0.0.50", a.getIp().getAddress());
        assertEquals("10.0.0.1", a.getIp().getGateway());
        assertEquals(Collections.singletonList("8.8.8.8"), a.getIp().getDns());
        assertNull(b.getPriority()); assertNull(b.getMac()); assertNull(b.getMetered()); assertNull(b.getProxy()); assertNull(b.getIp());
        assertNull("auto-connect is the default: not stored", b.getAutoConnect());
        assertEquals("https://corp.co/proxy.pac", c.getProxy().getPacUrl());
    }

    @Test
    public void enterprise_parameters_are_cleaned() {
        DcPolicy p = policy("{\"wifi\":[{\"ssid\":\"corp\",\"security\":\"EAP\",\"eap\":{\"method\":\"ttls\",\"phase2\":\"pap\",\"identity\":\" u@corp.co \","
                + "\"anonymousIdentity\":\"anon@corp.co\",\"password\":\"s3cret\",\"domain\":\"radius.corp.co\",\"caCertIds\":[3,3,-1,4],\"clientCertId\":9}},"
                + "{\"ssid\":\"tls\",\"security\":\"EAP\",\"eap\":{\"method\":\"TLS\",\"phase2\":\"PAP\",\"password\":\"x\",\"caCertIds\":[1],\"clientCertId\":9}},"
                + "{\"ssid\":\"home\",\"password\":\"12345678\",\"eap\":{\"method\":\"PEAP\"}}]}");
        DcPolicy.WifiEap e = p.getWifi().get(0).getEap();
        assertEquals("TTLS", e.getMethod()); assertEquals("PAP", e.getPhase2()); assertEquals("u@corp.co", e.getIdentity());
        assertEquals(Arrays.asList(3, 4), e.getCaCertIds());
        assertNull("a client certificate is for TLS only", e.getClientCertId());
        DcPolicy.WifiEap tls = p.getWifi().get(1).getEap();
        assertNull(tls.getPhase2()); assertNull(tls.getPassword()); assertEquals(Integer.valueOf(9), tls.getClientCertId());
        assertNull("no EAP on a personal network", p.getWifi().get(2).getEap());
    }

    @Test
    public void networks_get_a_stable_id() {
        String once = DcPolicy.normalize("{\"wifi\":[{\"ssid\":\"Planta\",\"password\":\"12345678\"},{\"ssid\":\"Corp\",\"id\":\"abc\",\"password\":\"12345678\"}]}");
        DcPolicy p = policy(once);
        assertNotNull(p.getWifi().get(0).getId());
        assertEquals("abc", p.getWifi().get(1).getId());
        assertEquals("the same on every read", once, DcPolicy.normalize(once));
        assertEquals(p.getWifi().get(0).getId(), policy("{\"wifi\":[{\"ssid\":\"Planta\"}]}").getWifi().get(0).getId());
    }

    @Test
    public void rules_are_cleaned_and_empty_rules_vanish() {
        assertNull(policy("{\"wifiRules\":{\"lockManaged\":false,\"ssidMode\":\"x\",\"minSecurity\":\"open\",\"safeguardMinutes\":5}}").getWifiRules());
        DcPolicy.WifiRules r = policy("{\"wifiRules\":{\"lockManaged\":true,\"blockAdd\":true,\"ssidMode\":\"ALLOW\",\"ssids\":[\" Corp \",\"Corp\",\"\"],"
                + "\"minSecurity\":\"Enterprise\",\"safeguardMinutes\":999}}").getWifiRules();
        assertEquals(Boolean.TRUE, r.getLockManaged()); assertEquals(Boolean.TRUE, r.getBlockAdd()); assertNull(r.getBlockConfig());
        assertEquals("allow", r.getSsidMode()); assertEquals(Collections.singletonList("Corp"), r.getSsids());
        assertEquals("enterprise", r.getMinSecurity());
        assertNull("out of range -> the default", r.getSafeguardMinutes());
        assertEquals(Integer.valueOf(3), policy("{\"wifiRules\":{\"blockConfig\":true,\"safeguardMinutes\":3}}").getWifiRules().getSafeguardMinutes());
        assertFalse(policy("{\"wifiRules\":{\"lockManaged\":true}}").isEmpty());
    }

    // -------------------------------------------------------------------------------------------- validation

    private static String check(String json) {
        return DcWifi.validate(policy(json), null);
    }

    @Test
    public void a_plain_policy_is_valid() {
        assertNull(check("{}"));
        assertNull(check("{\"wifi\":[{\"ssid\":\"a\",\"password\":\"12345678\"},{\"ssid\":\"b\",\"security\":\"NONE\"},{\"ssid\":\"c\",\"security\":\"OWE\"}]}"));
        assertNull(check("{\"wifi\":[{\"ssid\":\"a\",\"password\":\"" + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" + "\"}]}"));
    }

    @Test
    public void keyed_networks_need_a_usable_password() {
        assertEquals("error.configuration.wifi.password.missing", check("{\"wifi\":[{\"ssid\":\"a\"}]}"));
        assertEquals("error.configuration.wifi.password.length", check("{\"wifi\":[{\"ssid\":\"a\",\"password\":\"short\"}]}"));
        assertEquals("error.configuration.wifi.password.length", check("{\"wifi\":[{\"ssid\":\"a\",\"security\":\"WPA3\",\"password\":\""
                + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" + "\"}]}"));
    }

    @Test
    public void enterprise_networks_must_verify_the_server() {
        String base = "{\"wifi\":[{\"ssid\":\"corp\",\"security\":\"EAP\",\"eap\":{\"method\":\"PEAP\",\"identity\":\"u\",\"password\":\"p\"%s}}]}";
        assertEquals("error.configuration.wifi.eap.ca", check(String.format(base, "")));
        assertEquals("error.configuration.wifi.eap.domain", check(String.format(base, ",\"caCertIds\":[1]")));
        assertNull(check(String.format(base, ",\"caCertIds\":[1],\"domain\":\"radius.corp.co\"")));
        assertNull(check(String.format(base, ",\"caCertIds\":[1],\"altSubjectMatch\":\"DNS:radius.corp.co\"")));
        assertEquals("error.configuration.wifi.eap.identity",
                check("{\"wifi\":[{\"ssid\":\"corp\",\"security\":\"EAP\",\"eap\":{\"method\":\"PEAP\",\"password\":\"p\",\"caCertIds\":[1],\"domain\":\"d.co\"}}]}"));
        assertEquals("error.configuration.wifi.eap.clientcert",
                check("{\"wifi\":[{\"ssid\":\"corp\",\"security\":\"EAP\",\"eap\":{\"method\":\"TLS\",\"caCertIds\":[1],\"domain\":\"d.co\"}}]}"));
        assertNull(check("{\"wifi\":[{\"ssid\":\"corp\",\"security\":\"EAP\",\"eap\":{\"method\":\"TLS\",\"caCertIds\":[1],\"domain\":\"d.co\",\"clientCertId\":2}}]}"));
    }

    @Test
    public void rules_that_would_strand_the_phones_are_refused() {
        assertEquals("error.configuration.wifi.allow.empty", check("{\"wifiRules\":{\"ssidMode\":\"allow\"}}"));
        assertNull(check("{\"wifiRules\":{\"ssidMode\":\"allow\",\"ssids\":[\"Corp\"]}}"));
        assertNull("the managed network is always allowed", check("{\"wifi\":[{\"ssid\":\"a\",\"password\":\"12345678\"}],\"wifiRules\":{\"ssidMode\":\"allow\"}}"));
        assertEquals("error.configuration.wifi.deny.empty", check("{\"wifiRules\":{\"ssidMode\":\"deny\"}}"));
        assertEquals("error.configuration.wifi.deny.managed",
                check("{\"wifi\":[{\"ssid\":\"a\",\"password\":\"12345678\"}],\"wifiRules\":{\"ssidMode\":\"deny\",\"ssids\":[\"a\"]}}"));
        assertEquals("error.configuration.wifi.minsecurity",
                check("{\"wifi\":[{\"ssid\":\"a\",\"password\":\"12345678\"}],\"wifiRules\":{\"minSecurity\":\"enterprise\"}}"));
        assertNull(check("{\"wifi\":[{\"ssid\":\"a\",\"password\":\"12345678\"}],\"wifiRules\":{\"minSecurity\":\"personal\"}}"));
        assertEquals("error.configuration.wifi.block.nonetworks", check("{\"wifiRules\":{\"blockAdd\":true}}"));
        assertEquals("error.configuration.wifi.block.nonetworks", check("{\"wifiRules\":{\"blockConfig\":true}}"));
        assertNull(check("{\"wifi\":[{\"ssid\":\"a\",\"password\":\"12345678\"}],\"wifiRules\":{\"blockAdd\":true,\"blockConfig\":true,\"lockManaged\":true}}"));
        assertNull("locking the managed networks strands nobody", check("{\"wifiRules\":{\"lockManaged\":true}}"));
    }

    @Test
    public void wifi_forced_off_with_managed_networks_is_refused() {
        DcPolicy p = policy("{\"wifi\":[{\"ssid\":\"a\",\"password\":\"12345678\"}]}");
        assertEquals("error.configuration.wifi.radio.off", DcWifi.validate(p, Boolean.FALSE));
        assertNull(DcWifi.validate(p, Boolean.TRUE));
        assertNull(DcWifi.validate(policy("{}"), Boolean.FALSE));
    }

    @Test
    public void a_static_ip_needs_its_gateway() {
        assertEquals("error.configuration.wifi.ip.gateway", check("{\"wifi\":[{\"ssid\":\"a\",\"security\":\"NONE\",\"ip\":{\"address\":\"10.0.0.5\"}}]}"));
    }

    @Test
    public void every_error_has_a_spanish_message() {
        for (String key : Arrays.asList("password.missing", "password.length", "eap.missing", "eap.ca", "eap.domain", "eap.clientcert", "eap.identity",
                "ip.gateway", "radio.off", "allow.empty", "deny.empty", "deny.managed", "minsecurity", "block.nonetworks")) {
            assertNotEquals(key, DcWifi.message("error.configuration.wifi.other"), DcWifi.message("error.configuration.wifi." + key));
        }
    }

    // ----------------------------------------------------------------------------------------------- secrets

    @Test
    public void passwords_never_leave_in_the_policy_json() throws Exception {
        String stored = "{\"wifi\":[{\"ssid\":\"a\",\"id\":\"n1\",\"password\":\"hunter2hunter2\"},{\"ssid\":\"open\",\"security\":\"NONE\"},"
                + "{\"ssid\":\"corp\",\"security\":\"EAP\",\"eap\":{\"method\":\"PEAP\",\"identity\":\"u\",\"password\":\"eap-secret\"}}],\"trackingMinutes\":5}";
        String safe = DcWifi.redact(stored);
        assertFalse(safe.contains("hunter2")); assertFalse(safe.contains("eap-secret"));
        JsonNode n = JSON.readTree(safe);
        assertTrue(n.at("/wifi/0/passwordSet").asBoolean());
        assertTrue(n.at("/wifi/1/passwordSet").isMissingNode());
        assertTrue(n.at("/wifi/2/eap/passwordSet").asBoolean());
        assertEquals("the rest is untouched", 5, n.get("trackingMinutes").asInt());
        assertEquals("u", n.at("/wifi/2/eap/identity").asText());
        assertNull(DcWifi.redact(null));
        assertEquals("{\"trackingMinutes\":5}", DcWifi.redact("{\"trackingMinutes\":5}"));
        assertNull("unreadable JSON is not shown", DcWifi.redact("{\"wifi\":[oops"));
    }

    @Test
    public void the_configuration_serialises_without_wifi_passwords() throws Exception {
        Configuration c = new Configuration();
        c.setDcPolicy("{\"wifi\":[{\"ssid\":\"a\",\"password\":\"hunter2hunter2\"}]}");
        String json = JSON.writeValueAsString(c);
        assertFalse(json.contains("hunter2"));
        assertTrue(json.contains("passwordSet"));
        assertTrue("Java code still reads the real value", c.getDcPolicy().contains("hunter2"));
        assertFalse("and so does the audit diff", ConfigChangeSummary.build(null, c).contains("hunter2"));
    }

    @Test
    public void a_save_keeps_the_stored_password_unless_a_new_one_is_typed() throws Exception {
        String stored = "{\"wifi\":[{\"ssid\":\"a\",\"id\":\"n1\",\"password\":\"old-password\"},{\"ssid\":\"b\",\"id\":\"n2\",\"password\":\"b-password\"},"
                + "{\"ssid\":\"corp\",\"id\":\"n3\",\"security\":\"EAP\",\"eap\":{\"password\":\"eap-old\"}}]}";
        String incoming = "{\"wifi\":[{\"ssid\":\"renamed\",\"id\":\"n1\",\"passwordSet\":true},{\"ssid\":\"b\",\"id\":\"n2\",\"passwordSet\":true,\"password\":\"new-password\"},"
                + "{\"ssid\":\"corp\",\"id\":\"n3\",\"security\":\"EAP\",\"eap\":{\"passwordSet\":true}},{\"ssid\":\"fresh\",\"id\":\"n4\",\"passwordSet\":true}]}";
        JsonNode n = JSON.readTree(DcWifi.mergeSecrets(incoming, Collections.singletonList(stored)));
        assertEquals("kept by id even when the SSID changes", "old-password", n.at("/wifi/0/password").asText());
        assertEquals("new-password", n.at("/wifi/1/password").asText());
        assertEquals("eap-old", n.at("/wifi/2/eap/password").asText());
        assertTrue("nothing stored for a new network", n.at("/wifi/3/password").isMissingNode());
        assertFalse("passwordSet is never stored", n.toString().contains("passwordSet"));
    }

    @Test
    public void a_network_without_passwordSet_gets_nothing_and_old_policies_match_by_ssid() throws Exception {
        String stored = "{\"wifi\":[{\"ssid\":\"a\",\"password\":\"old-password\"}]}";
        assertTrue(JSON.readTree(DcWifi.mergeSecrets("{\"wifi\":[{\"ssid\":\"a\"}]}", Collections.singletonList(stored))).at("/wifi/0/password").isMissingNode());
        assertEquals("old-password", JSON.readTree(DcWifi.mergeSecrets("{\"wifi\":[{\"ssid\":\"a\",\"passwordSet\":true}]}",
                Collections.singletonList(stored))).at("/wifi/0/password").asText());
        assertEquals("the first stored policy wins", "old-password", JSON.readTree(DcWifi.mergeSecrets("{\"wifi\":[{\"ssid\":\"a\",\"passwordSet\":true}]}",
                Arrays.asList(stored, "{\"wifi\":[{\"ssid\":\"a\",\"password\":\"other\"}]}"))).at("/wifi/0/password").asText());
    }

    // -------------------------------------------------------------------------------------- desired document

    private static Configuration cfg(String dc) {
        Configuration c = new Configuration();
        c.setId(1);
        c.setDcPolicy(dc);
        return c;
    }

    @Test
    public void an_old_style_network_produces_the_same_document_as_before() {
        DesiredConfig d = DesiredConfigBuilder.build(cfg("{\"wifi\":[{\"ssid\":\"Amovil\",\"password\":\"secret123\",\"hidden\":true}]}"),
                Collections.<com.hmdm.persistence.domain.Application>emptyList());
        String json = DesiredConfigBuilder.canonicalJson(d);
        assertTrue(json, json.contains("\"wifi\":[{\"hidden\":true,\"password\":\"secret123\",\"security\":\"WPA\",\"ssid\":\"Amovil\"}]"));
        assertFalse("no new keys: phones already on this policy are not asked to re-apply", json.contains("wifiAdvanced") || json.contains("wifiRules"));
    }

    @Test
    public void newer_securities_travel_apart_so_older_agents_ignore_them() {
        DesiredConfig d = DesiredConfigBuilder.build(cfg("{\"wifi\":[{\"ssid\":\"psk\",\"password\":\"12345678\"},{\"ssid\":\"mix\",\"security\":\"WPA3_TRANSITION\",\"password\":\"12345678\"},"
                + "{\"ssid\":\"sae\",\"security\":\"WPA3\",\"password\":\"12345678\"},{\"ssid\":\"owe\",\"security\":\"OWE\"},"
                + "{\"ssid\":\"corp\",\"security\":\"EAP\",\"eap\":{\"method\":\"PEAP\",\"identity\":\"u\",\"password\":\"p\",\"domain\":\"d.co\",\"caCertIds\":[4]}}],"
                + "\"wifiRules\":{\"lockManaged\":true,\"ssidMode\":\"allow\"}}"),
                Collections.<com.hmdm.persistence.domain.Application>emptyList());
        assertEquals(2, d.getWifi().size());
        assertEquals("mix", d.getWifi().get(1).getSsid());
        assertEquals(3, d.getWifiAdvanced().size());
        assertEquals("corp", d.getWifiAdvanced().get(2).getSsid());
        assertEquals(Collections.singletonList(4), d.getWifiAdvanced().get(2).getEap().getCaCertIds());
        String json = DesiredConfigBuilder.toPayloadJson(d);
        assertFalse("certificate ids are resolved, never sent", json.contains("caCertIds"));
        assertFalse("the console's network id stays on the server", json.contains("\"id\""));
        assertTrue(json.contains("\"wifiRules\":{\"lockManaged\":true,\"ssidMode\":\"allow\"}"));
    }

    @Test
    public void only_advanced_networks_leave_the_legacy_list_absent() {
        DesiredConfig d = DesiredConfigBuilder.build(cfg("{\"wifi\":[{\"ssid\":\"sae\",\"security\":\"WPA3\",\"password\":\"12345678\"}]}"),
                Collections.<com.hmdm.persistence.domain.Application>emptyList());
        assertNull(d.getWifi());
        assertEquals(1, d.getWifiAdvanced().size());
    }

    @Test
    public void certificate_ids_in_use_are_found() {
        assertEquals(new java.util.LinkedHashSet<Integer>(Arrays.asList(3, 4, 9)), DcWifi.certificateIds(
                "{\"wifi\":[{\"ssid\":\"corp\",\"security\":\"EAP\",\"eap\":{\"method\":\"TLS\",\"caCertIds\":[3,4],\"clientCertId\":9}}]}"));
        assertTrue(DcWifi.certificateIds(null).isEmpty());
    }
}
