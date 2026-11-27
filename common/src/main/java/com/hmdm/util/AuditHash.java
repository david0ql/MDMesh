/*
 *
 * DallyControl — hash chain of the audit trail. Each row stores the hash of the row written before it (prevHash) and
 * its own hash over prevHash plus its content, so editing a row, or removing one from the middle, breaks the chain
 * from that point on and {@link #verify} reports where.
 *
 */

package com.hmdm.util;

import com.hmdm.persistence.domain.AuditEntry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

public final class AuditHash {
    private AuditHash() {}

    /** SHA-256 (hex) of the row's content chained to {@code entry.prevHash}. */
    public static String of(AuditEntry e) {
        StringBuilder sb = new StringBuilder(512);
        field(sb, e.prevHash);
        field(sb, e.createdAt);
        field(sb, e.customerId);
        field(sb, e.userId);
        field(sb, e.login);
        field(sb, e.roleName);
        field(sb, e.ip);
        field(sb, e.userAgent);
        field(sb, e.category);
        field(sb, e.action);
        field(sb, e.label);
        field(sb, e.method);
        field(sb, e.path);
        field(sb, e.targetType);
        field(sb, e.targetId);
        field(sb, e.targetName);
        field(sb, e.result);
        field(sb, e.httpStatus);
        field(sb, e.message);
        field(sb, e.detail);
        field(sb, e.durationMs);
        return sha256(sb.toString());
    }

    /** Length-prefixed, so two different rows can never serialise to the same text. */
    private static void field(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("-1:;");
            return;
        }
        String s = String.valueOf(v);
        sb.append(s.length()).append(':').append(s).append(';');
    }

    public static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : d) hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Checks a run of consecutive rows (in id order). {@code previousHash} is the hash of the row just before the
     * run, or null when the run starts at the oldest row kept (the retention purge removes the rows before it, so its
     * prevHash cannot be checked). Returns the id of the first row that does not hold, or null when all do.
     */
    public static Long verify(List<AuditEntry> rows, String previousHash, boolean checkFirstLink) {
        String prev = previousHash;
        boolean first = true;
        for (AuditEntry e : rows) {
            boolean linkOk = (first && !checkFirstLink) || eq(e.prevHash, prev);
            if (!linkOk || !of(e).equals(e.hash)) return e.id;
            prev = e.hash;
            first = false;
        }
        return null;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
