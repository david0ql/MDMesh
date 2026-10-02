package com.hmdm.util;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Who signed an APK: the SHA-256 of its first signing certificate and that certificate's subject. Reads the APK
 * Signature Scheme v2/v3 block (what Android itself trusts on modern apps) and falls back to the v1 (JAR) signature.
 * Android only lets an app be UPDATED by an APK signed with the same certificate, so the certificate is the app's
 * real identity; {@link #publisher} names the ones known here.
 */
public final class ApkSigner {
    private static final long SIG_BLOCK_MAGIC_LO = 0x20676953204b5041L; // "APK Sig "
    private static final long SIG_BLOCK_MAGIC_HI = 0x3234206b636f6c42L; // "Block 42"
    private static final int V2_ID = 0x7109871a;
    private static final int V3_ID = 0xf05368c0;

    /** Certificates of publishers that are well known (lower-case SHA-256). */
    private static final Map<String, String> KNOWN = new LinkedHashMap<>();
    static {
        KNOWN.put("f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83", "Google LLC");
        KNOWN.put("3987d043d10aefaf5a8710b3671418fe57e0e19b653c9df82558feb5ffce5d44", "WhatsApp LLC");
    }

    public static final class Signer {
        public final String sha256;
        public final String subject;

        Signer(String sha256, String subject) {
            this.sha256 = sha256;
            this.subject = subject;
        }
    }

    private ApkSigner() {}

    /** The publisher's name when the certificate is a known one, else null. */
    public static String publisher(String sha256) {
        return sha256 == null ? null : KNOWN.get(sha256.toLowerCase());
    }

    /** @return the first signer, or null when the file carries no readable signature */
    public static Signer of(File apk) {
        try {
            byte[] der = v2v3Certificate(apk);
            if (der == null) der = v1Certificate(apk);
            if (der == null) return null;
            X509Certificate x = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
            byte[] d = MessageDigest.getInstance("SHA-256").digest(der);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return new Signer(sb.toString(), x.getSubjectX500Principal().getName());
        } catch (Exception e) {
            return null;
        }
    }

    /** DER of the first certificate in the APK Signing Block (v3 preferred, then v2); null when there is none. */
    static byte[] v2v3Certificate(File apk) throws Exception {
        try (RandomAccessFile f = new RandomAccessFile(apk, "r")) {
            long len = f.length();
            // End of central directory: scan back for its signature (the comment is at most 65535 bytes).
            int tail = (int) Math.min(len, 65557);
            byte[] buf = new byte[tail];
            f.seek(len - tail);
            f.readFully(buf);
            int eocd = -1;
            for (int i = tail - 22; i >= 0; i--) {
                if (buf[i] == 0x50 && buf[i + 1] == 0x4b && buf[i + 2] == 0x05 && buf[i + 3] == 0x06) { eocd = i; break; }
            }
            if (eocd < 0) return null;
            long cdOffset = ByteBuffer.wrap(buf, eocd + 16, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xffffffffL;
            if (cdOffset < 32) return null;
            byte[] footer = new byte[24];
            f.seek(cdOffset - 24);
            f.readFully(footer);
            ByteBuffer fb = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN);
            long blockSize = fb.getLong();
            if (fb.getLong() != SIG_BLOCK_MAGIC_LO || fb.getLong() != SIG_BLOCK_MAGIC_HI) return null;
            if (blockSize < 24 || blockSize > 64L * 1024 * 1024 || blockSize + 8 > cdOffset) return null;
            byte[] pairs = new byte[(int) (blockSize - 24)];
            f.seek(cdOffset - blockSize);
            f.readFully(pairs);
            ByteBuffer pb = ByteBuffer.wrap(pairs).order(ByteOrder.LITTLE_ENDIAN);
            byte[] v2 = null, v3 = null;
            while (pb.remaining() >= 12) {
                long pairLen = pb.getLong();
                if (pairLen < 4 || pairLen > pb.remaining()) break;
                int id = pb.getInt();
                byte[] value = new byte[(int) pairLen - 4];
                pb.get(value);
                if (id == V3_ID) v3 = value; else if (id == V2_ID) v2 = value;
            }
            byte[] scheme = v3 != null ? v3 : v2;
            return scheme == null ? null : firstCertificate(scheme);
        }
    }

    /** signers(seq) -> signer -> signed data -> digests(seq), certificates(seq) -> certificate. */
    private static byte[] firstCertificate(byte[] schemeBlock) {
        ByteBuffer signers = prefixed(ByteBuffer.wrap(schemeBlock).order(ByteOrder.LITTLE_ENDIAN));
        ByteBuffer signer = prefixed(signers);
        ByteBuffer signedData = prefixed(signer);
        prefixed(signedData); // digests
        ByteBuffer certs = prefixed(signedData);
        ByteBuffer cert = prefixed(certs);
        byte[] der = new byte[cert.remaining()];
        cert.get(der);
        return der;
    }

    private static ByteBuffer prefixed(ByteBuffer b) {
        int len = b.getInt();
        if (len < 0 || len > b.remaining()) throw new IllegalArgumentException("bad length");
        ByteBuffer s = b.slice().order(ByteOrder.LITTLE_ENDIAN);
        s.limit(len);
        b.position(b.position() + len);
        return s;
    }

    private static byte[] v1Certificate(File apk) throws Exception {
        try (JarFile jar = new JarFile(apk, true)) {
            Enumeration<JarEntry> en = jar.entries();
            byte[] sink = new byte[8192];
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (e.isDirectory() || e.getName().startsWith("META-INF/")) continue;
                try (InputStream in = jar.getInputStream(e)) {
                    while (in.read(sink) >= 0) { /* certificates are known only after the entry is read */ }
                }
                Certificate[] certs = e.getCertificates();
                return certs == null || certs.length == 0 ? null : certs[0].getEncoded();
            }
        }
        return null;
    }
}
