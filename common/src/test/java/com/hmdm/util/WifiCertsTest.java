package com.hmdm.util;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

import static org.junit.Assert.*;

/** The certificates are made by the JDK's own keytool when the test starts: no key material lives in the repository. */
public class WifiCertsTest {
    private static final String PASSWORD = "test-only-password";
    private static byte[] p12, der, pem;

    @BeforeClass
    public static void makeCertificates() throws Exception {
        File dir = Files.createTempDirectory("wifi-certs-test").toFile();
        File store = new File(dir, "client.p12"), cert = new File(dir, "ca.der"), certPem = new File(dir, "ca.pem");
        keytool("-genkeypair", "-alias", "client", "-keyalg", "RSA", "-keysize", "2048", "-validity", "365", "-dname", "CN=Dispositivo de prueba, O=Amovil",
                "-storetype", "PKCS12", "-keystore", store.getPath(), "-storepass", PASSWORD, "-keypass", PASSWORD);
        keytool("-exportcert", "-alias", "client", "-keystore", store.getPath(), "-storepass", PASSWORD, "-file", cert.getPath());
        keytool("-exportcert", "-rfc", "-alias", "client", "-keystore", store.getPath(), "-storepass", PASSWORD, "-file", certPem.getPath());
        p12 = Files.readAllBytes(store.toPath());
        der = Files.readAllBytes(cert.toPath());
        pem = Files.readAllBytes(certPem.toPath());
    }

    private static void keytool(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = new File(System.getProperty("java.home"), "bin/keytool").getPath();
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals("keytool " + args[0], 0, p.waitFor());
    }

    @Test
    public void a_ca_is_read_from_der_and_from_pem() {
        WifiCerts.Material fromDer = WifiCerts.parseCa(der), fromPem = WifiCerts.parseCa(pem);
        assertEquals(WifiCerts.CA, fromDer.kind);
        assertEquals(1, fromDer.certs.size());
        assertEquals(fromDer.fingerprint(), fromPem.fingerprint());
        assertEquals(64, fromDer.fingerprint().length());
        assertTrue(fromDer.first().getSubjectX500Principal().getName().contains("Dispositivo de prueba"));
        assertTrue(fromDer.notAfter() > System.currentTimeMillis());
        assertNull(fromDer.key);
    }

    @Test
    public void several_pems_in_one_file_are_a_chain() {
        byte[] two = (new String(pem, StandardCharsets.US_ASCII) + "\n" + new String(pem, StandardCharsets.US_ASCII)).getBytes(StandardCharsets.US_ASCII);
        assertEquals(2, WifiCerts.parseCa(two).certs.size());
    }

    @Test
    public void garbage_empty_and_huge_uploads_are_refused() {
        assertEquals("error.wifi.cert.unreadable", error(() -> WifiCerts.parseCa("not a certificate".getBytes(StandardCharsets.UTF_8))));
        assertEquals("error.wifi.cert.size", error(() -> WifiCerts.parseCa(new byte[0])));
        assertEquals("error.wifi.cert.size", error(() -> WifiCerts.parseCa(new byte[WifiCerts.MAX_BYTES + 1])));
        assertEquals("error.wifi.cert.password", error(() -> WifiCerts.parseClient("nope".getBytes(StandardCharsets.UTF_8), PASSWORD)));
    }

    @Test
    public void a_client_certificate_needs_its_password() {
        WifiCerts.Material m = WifiCerts.parseClient(p12, PASSWORD);
        assertEquals(WifiCerts.CLIENT, m.kind);
        assertEquals("RSA", m.keyAlgorithm);
        assertNotNull(m.key);
        assertEquals(1, m.certs.size());
        assertEquals("error.wifi.cert.password", error(() -> WifiCerts.parseClient(p12, "wrong")));
        assertEquals("error.wifi.cert.password", error(() -> WifiCerts.parseClient(p12, null)));
    }

    @Test
    public void the_vault_round_trips_and_shows_nothing() throws Exception {
        WifiCerts.Material m = WifiCerts.parseClient(p12, PASSWORD);
        File dir = Files.createTempDirectory("wifi-vault-test").toFile();
        WifiCerts.Vault vault = WifiCerts.Vault.inDirectory(dir);
        String sealed = vault.seal(m);
        assertTrue(sealed.startsWith("v1:"));
        assertFalse("the key is not readable in what is stored", sealed.contains(m.keyBase64().substring(0, 40)));
        assertFalse(sealed.contains(m.certsBase64().get(0).substring(0, 40)));
        assertNotEquals("a fresh nonce every time", sealed, vault.seal(m));
        WifiCerts.Material back = WifiCerts.Vault.inDirectory(dir).open(sealed);
        assertEquals("the same key file opens it", m.fingerprint(), back.fingerprint());
        assertArrayEquals(m.key, back.key);
        assertEquals(WifiCerts.CLIENT, back.kind);
        assertEquals("RSA", back.keyAlgorithm);
        assertEquals("one key file, base64 of 32 bytes", 32, Base64.getDecoder().decode(new String(Files.readAllBytes(new File(dir, "wifi-certs.key").toPath())).trim()).length);
    }

    @Test
    public void another_key_or_a_tampered_value_does_not_open() throws Exception {
        WifiCerts.Material m = WifiCerts.parseCa(der);
        WifiCerts.Vault a = new WifiCerts.Vault(new byte[32]);
        byte[] other = new byte[32];
        other[0] = 1;
        String sealed = a.seal(m);
        assertNotNull(a.open(sealed));
        assertThrows(IllegalStateException.class, () -> new WifiCerts.Vault(other).open(sealed));
        char flip = sealed.charAt(sealed.length() - 6) == 'A' ? 'B' : 'A';
        String tampered = sealed.substring(0, sealed.length() - 6) + flip + sealed.substring(sealed.length() - 5);
        assertThrows(IllegalStateException.class, () -> a.open(tampered));
        assertThrows(IllegalStateException.class, () -> a.open("plain text"));
        assertThrows(IllegalArgumentException.class, () -> new WifiCerts.Vault(new byte[16]));
    }

    private static String error(Runnable r) {
        try {
            r.run();
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }
}
