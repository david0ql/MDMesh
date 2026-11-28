package com.hmdm.rest.resource.support;

import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.mapper.WifiCertificateMapper;
import com.hmdm.rest.json.agent.DesiredConfig;
import com.hmdm.rest.json.agent.DesiredWifi;
import com.hmdm.util.DesiredConfigBuilder;
import com.hmdm.util.WifiCerts;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** The certificates a policy's 802.1X networks refer to reach the phone's document; nothing else of the store does. */
public class WifiCertificateStoreTest {
    private static final String PASSWORD = "test-only-password";
    private static byte[] p12, der;

    @BeforeClass
    public static void makeCertificates() throws Exception {
        File dir = Files.createTempDirectory("wifi-store-test").toFile();
        File store = new File(dir, "client.p12"), cert = new File(dir, "ca.der");
        keytool("-genkeypair", "-alias", "client", "-keyalg", "RSA", "-keysize", "2048", "-validity", "365", "-dname", "CN=prueba",
                "-storetype", "PKCS12", "-keystore", store.getPath(), "-storepass", PASSWORD, "-keypass", PASSWORD);
        keytool("-exportcert", "-alias", "client", "-keystore", store.getPath(), "-storepass", PASSWORD, "-file", cert.getPath());
        p12 = Files.readAllBytes(store.toPath());
        der = Files.readAllBytes(cert.toPath());
    }

    private static void keytool(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = new File(System.getProperty("java.home"), "bin/keytool").getPath();
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
    }

    /** A table in memory: id -> (customerId, sealed). */
    private static final class FakeMapper implements WifiCertificateMapper {
        final Map<Integer, Object[]> rows = new HashMap<>();
        int reads;

        @Override public List<Map<String, Object>> list(int customerId) { return Collections.emptyList(); }

        @Override public Map<String, Object> sealed(int customerId, int id) {
            reads++;
            Object[] r = rows.get(id);
            if (r == null || (Integer) r[0] != customerId) return null;
            Map<String, Object> m = new HashMap<>();
            m.put("id", id);
            m.put("sealed", r[1]);
            return m;
        }

        @Override public void insert(Map<String, Object> c) { }

        @Override public int delete(int customerId, int id) { return rows.remove(id) == null ? 0 : 1; }

        @Override public List<Map<String, Object>> policiesWithCertificates(int customerId) { return Collections.emptyList(); }
    }

    private static DesiredConfig document(String eap) {
        Configuration c = new Configuration();
        c.setId(1);
        c.setDcPolicy("{\"wifi\":[{\"ssid\":\"corp\",\"security\":\"EAP\",\"eap\":" + eap + "}]}");
        return DesiredConfigBuilder.build(c, Collections.<Application>emptyList());
    }

    @Test
    public void certificates_are_inlined_and_change_the_revision() throws Exception {
        FakeMapper mapper = new FakeMapper();
        WifiCertificateStore store = new WifiCertificateStore(mapper, Files.createTempDirectory("wifi-store-key").toString());
        mapper.rows.put(4, new Object[]{1, store.seal(WifiCerts.parseCa(der))});
        mapper.rows.put(9, new Object[]{1, store.seal(WifiCerts.parseClient(p12, PASSWORD))});

        DesiredConfig doc = document("{\"method\":\"TLS\",\"identity\":\"u\",\"domain\":\"d.co\",\"caCertIds\":[4],\"clientCertId\":9}");
        String before = doc.getRevision();
        store.inline(doc, 1);
        DesiredWifi.Eap e = doc.getWifiAdvanced().get(0).getEap();
        assertEquals(1, e.getCaCerts().size());
        assertEquals("RSA", e.getClientKeyAlgorithm());
        assertNotNull(e.getClientKey());
        assertEquals(1, e.getClientChain().size());
        assertNotEquals("the certificates are part of what the phone must apply", before, doc.getRevision());
        String json = DesiredConfigBuilder.toPayloadJson(doc);
        assertTrue(json.contains("\"caCerts\"") && json.contains("\"clientKey\""));
        assertFalse(json.contains(PASSWORD));

        DesiredConfig again = store.inline(document("{\"method\":\"TLS\",\"identity\":\"u\",\"domain\":\"d.co\",\"caCertIds\":[4],\"clientCertId\":9}"), 1);
        assertEquals("deterministic: the same policy is the same revision on every check-in", doc.getRevision(), again.getRevision());
        assertEquals("opened once, then served from memory", 2, mapper.reads);
    }

    @Test
    public void another_account_or_a_missing_certificate_gets_nothing() throws Exception {
        FakeMapper mapper = new FakeMapper();
        WifiCertificateStore store = new WifiCertificateStore(mapper, Files.createTempDirectory("wifi-store-key").toString());
        mapper.rows.put(4, new Object[]{2, store.seal(WifiCerts.parseCa(der))}); // customer 2's
        DesiredConfig doc = store.inline(document("{\"method\":\"PEAP\",\"identity\":\"u\",\"password\":\"p\",\"domain\":\"d.co\",\"caCertIds\":[4,77]}"), 1);
        assertNull(doc.getWifiAdvanced().get(0).getEap().getCaCerts());
        assertNull(store.open(1, 4));
        assertNotNull(store.open(2, 4));
    }

    @Test
    public void a_client_certificate_is_never_used_as_a_ca_and_a_deleted_one_is_forgotten() throws Exception {
        FakeMapper mapper = new FakeMapper();
        WifiCertificateStore store = new WifiCertificateStore(mapper, Files.createTempDirectory("wifi-store-key").toString());
        mapper.rows.put(9, new Object[]{1, store.seal(WifiCerts.parseClient(p12, PASSWORD))});
        DesiredConfig doc = store.inline(document("{\"method\":\"PEAP\",\"identity\":\"u\",\"password\":\"p\",\"domain\":\"d.co\",\"caCertIds\":[9]}"), 1);
        assertNull(doc.getWifiAdvanced().get(0).getEap().getCaCerts());
        assertNotNull(store.open(1, 9));
        mapper.delete(1, 9);
        store.forget(1, 9);
        assertNull(store.open(1, 9));
    }

    @Test
    public void documents_without_enterprise_networks_are_untouched() throws Exception {
        WifiCertificateStore store = new WifiCertificateStore(new FakeMapper(), Files.createTempDirectory("wifi-store-key").toString());
        Configuration c = new Configuration();
        c.setId(1);
        c.setDcPolicy("{\"wifi\":[{\"ssid\":\"a\",\"password\":\"12345678\"},{\"ssid\":\"s\",\"security\":\"WPA3\",\"password\":\"12345678\"}]}");
        DesiredConfig doc = DesiredConfigBuilder.build(c, Collections.<Application>emptyList());
        String rev = doc.getRevision();
        assertEquals(rev, store.inline(doc, 1).getRevision());
        assertEquals(rev, store.inline(doc, null).getRevision());
    }
}
