package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;

/**
 * <p>Certificates of enterprise Wi-Fi networks, uploaded from the console:</p>
 * <ul>
 *     <li>{@link #parseCa} reads a CA (PEM, several PEMs in one file, DER or PKCS#7); {@link #parseClient} opens a
 *     PKCS#12 with its password and takes the key and its chain;</li>
 *     <li>{@link Vault} keeps what was parsed encrypted at rest (AES-256-GCM) with a key that lives in a file next to
 *     the server's other keys, not in the database — so a database dump alone never yields a private key;</li>
 *     <li>{@link Material} is the decrypted form, only ever built to put a certificate in a phone's policy document.</li>
 * </ul>
 * <p>Nothing here logs certificate contents, keys or passwords.</p>
 */
public final class WifiCerts {
    public static final String CA = "CA", CLIENT = "CLIENT";
    /** Uploads above this are refused before parsing (a chain of 5 certificates and a key is well under it). */
    public static final int MAX_BYTES = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private WifiCerts() {}

    /** A parsed upload: its certificates (leaf / first CA first) and, for a client certificate, the private key. */
    public static final class Material {
        public final String kind;
        public final List<X509Certificate> certs;
        /** PKCS#8, null for a CA. */
        public final byte[] key;
        public final String keyAlgorithm;

        Material(String kind, List<X509Certificate> certs, byte[] key, String keyAlgorithm) {
            this.kind = kind;
            this.certs = certs;
            this.key = key;
            this.keyAlgorithm = keyAlgorithm;
        }

        public X509Certificate first() {
            return certs.get(0);
        }

        /** The earliest expiry of the whole chain: the day the network stops working. */
        public long notAfter() {
            long min = Long.MAX_VALUE;
            for (X509Certificate c : certs) min = Math.min(min, c.getNotAfter().getTime());
            return min;
        }

        public long notBefore() {
            long max = Long.MIN_VALUE;
            for (X509Certificate c : certs) max = Math.max(max, c.getNotBefore().getTime());
            return max;
        }

        public String fingerprint() {
            try {
                byte[] h = MessageDigest.getInstance("SHA-256").digest(first().getEncoded());
                StringBuilder sb = new StringBuilder(64);
                for (byte b : h) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        public List<String> certsBase64() {
            List<String> out = new ArrayList<String>();
            try {
                for (X509Certificate c : certs) out.add(Base64.getEncoder().encodeToString(c.getEncoded()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return out;
        }

        public String keyBase64() {
            return key == null ? null : Base64.getEncoder().encodeToString(key);
        }

        /** The JSON the vault seals. */
        String toJson() {
            ObjectNode n = JSON.createObjectNode();
            n.put("kind", kind);
            ArrayNode a = n.putArray("certs");
            for (String c : certsBase64()) a.add(c);
            if (key != null) {
                n.put("key", keyBase64());
                n.put("keyAlg", keyAlgorithm);
            }
            return n.toString();
        }

        static Material fromJson(String json) throws Exception {
            JsonNode n = JSON.readTree(json);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> certs = new ArrayList<X509Certificate>();
            for (JsonNode c : n.path("certs")) {
                certs.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(c.asText()))));
            }
            byte[] key = n.hasNonNull("key") ? Base64.getDecoder().decode(n.get("key").asText()) : null;
            return new Material(n.path("kind").asText(CA), certs, key, n.path("keyAlg").asText(null));
        }
    }

    /**
     * @throws IllegalArgumentException with an error key: {@code error.wifi.cert.size}, {@code error.wifi.cert.unreadable}
     */
    public static Material parseCa(byte[] data) {
        checkSize(data);
        List<X509Certificate> certs = new ArrayList<X509Certificate>();
        try {
            Collection<? extends Certificate> all = CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(data));
            for (Certificate c : all) if (c instanceof X509Certificate) certs.add((X509Certificate) c);
        } catch (Exception e) {
            throw new IllegalArgumentException("error.wifi.cert.unreadable");
        }
        if (certs.isEmpty() || certs.size() > 5) throw new IllegalArgumentException("error.wifi.cert.unreadable");
        return new Material(CA, certs, null, null);
    }

    /**
     * @throws IllegalArgumentException with an error key: {@code error.wifi.cert.size}, {@code error.wifi.cert.password}
     *     (wrong password or not a PKCS#12), {@code error.wifi.cert.nokey} (no private key inside)
     */
    public static Material parseClient(byte[] data, String password) {
        checkSize(data);
        char[] pw = password == null ? new char[0] : password.toCharArray();
        KeyStore ks;
        try {
            ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(data), pw);
        } catch (Exception e) {
            throw new IllegalArgumentException("error.wifi.cert.password");
        }
        try {
            Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (!ks.isKeyEntry(alias)) continue;
                Key key = ks.getKey(alias, pw);
                Certificate[] chain = ks.getCertificateChain(alias);
                if (!(key instanceof PrivateKey) || chain == null || chain.length == 0 || key.getEncoded() == null) continue;
                List<X509Certificate> certs = new ArrayList<X509Certificate>();
                for (Certificate c : chain) if (c instanceof X509Certificate && certs.size() < 5) certs.add((X509Certificate) c);
                if (certs.isEmpty()) continue;
                return new Material(CLIENT, certs, key.getEncoded(), key.getAlgorithm());
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("error.wifi.cert.password");
        }
        throw new IllegalArgumentException("error.wifi.cert.nokey");
    }

    private static void checkSize(byte[] data) {
        if (data == null || data.length == 0 || data.length > MAX_BYTES) throw new IllegalArgumentException("error.wifi.cert.size");
    }

    /** Encrypts certificate material at rest. Format: {@code v1:} + base64(12-byte IV + AES-GCM ciphertext and tag). */
    public static final class Vault {
        static final String KEY_FILE = "wifi-certs.key";
        private static final String PREFIX = "v1:";
        private final SecretKeySpec key;
        private final SecureRandom random = new SecureRandom();

        public Vault(byte[] key32) {
            if (key32 == null || key32.length != 32) throw new IllegalArgumentException("a 256-bit key is required");
            this.key = new SecretKeySpec(key32, "AES");
        }

        /** The vault whose key lives in {@code <directory>/wifi-certs.key}, created (owner-only) on first use. */
        public static synchronized Vault inDirectory(File directory) throws Exception {
            File f = new File(directory, KEY_FILE);
            if (!f.exists()) {
                byte[] k = new byte[32];
                new SecureRandom().nextBytes(k);
                if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("cannot create the key directory");
                Files.write(f.toPath(), Base64.getEncoder().encode(k));
                try {
                    Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // not a POSIX file system: the directory's own permissions apply
                }
            }
            return new Vault(Base64.getDecoder().decode(new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII).trim()));
        }

        public String seal(Material m) {
            try {
                byte[] iv = new byte[12];
                random.nextBytes(iv);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
                byte[] ct = c.doFinal(m.toJson().getBytes(StandardCharsets.UTF_8));
                byte[] out = new byte[iv.length + ct.length];
                System.arraycopy(iv, 0, out, 0, iv.length);
                System.arraycopy(ct, 0, out, iv.length, ct.length);
                return PREFIX + Base64.getEncoder().encodeToString(out);
            } catch (Exception e) {
                throw new IllegalStateException("cannot seal the certificate", e);
            }
        }

        /** @throws IllegalStateException when the value was not sealed with this key (or was tampered with) */
        public Material open(String sealed) {
            try {
                if (sealed == null || !sealed.startsWith(PREFIX)) throw new IllegalArgumentException("unknown format");
                byte[] in = Base64.getDecoder().decode(sealed.substring(PREFIX.length()));
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, in, 0, 12));
                return Material.fromJson(new String(c.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IllegalStateException("cannot open the certificate (wrong key?)");
            }
        }
    }
}
