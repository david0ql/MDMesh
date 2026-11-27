package com.hmdm.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Properties;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Resumable upload of a big file in parts. The console's traffic goes through a tunnel that refuses request bodies
 * over 100 MB, so an APK or bundle bigger than that cannot travel in one request: it is sent in parts of at most
 * {@link #MAX_PART_SIZE}, each one checked (size and, when the sender gives it, SHA-256) and kept on disk, and put
 * together at the end — where the whole file's size and SHA-256 are checked again. A part that failed is simply sent
 * again; an upload interrupted (the page closed, the server restarted) continues from the parts already here.
 * Uploads nobody finished are removed after {@link #ABANDONED_MS}.
 *
 * Everything lives under one directory ({@code root/<id>/}): {@code meta.properties} and {@code part-<n>}.
 */
public final class ChunkedUploads {

    /** The tunnel's limit is 100 MB per request; half of it leaves room for anything in between. */
    public static final long MAX_PART_SIZE = 50L * 1024 * 1024;
    public static final long MIN_PART_SIZE = 256L * 1024;
    public static final long DEFAULT_PART_SIZE = 24L * 1024 * 1024;
    /** No single file of the library is bigger than this. */
    public static final long MAX_TOTAL_SIZE = 4L * 1024 * 1024 * 1024;
    /** An upload with no part received for this long was abandoned. */
    public static final long ABANDONED_MS = 24L * 3600_000L;

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final String GONE = "La subida ya no existe o venció; empieza de nuevo.";

    /** Why an upload step was refused; {@link #getMessage()} is the text for the console (Spanish). */
    public static final class UploadException extends Exception {
        public final String code;

        public UploadException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** One upload in progress. */
    public static final class Session {
        public String id;
        public int customerId;
        public int userId;
        public String fileName;
        public long totalSize;
        public long partSize;
        /** Lower-case SHA-256 of the whole file when the sender declared it, else null. */
        public String sha256;
        public long createdAt;
        /** Indexes of the parts already here, ascending. */
        public TreeSet<Integer> received = new TreeSet<>();

        public int partCount() {
            return totalSize == 0 ? 0 : (int) ((totalSize + partSize - 1) / partSize);
        }

        /** The exact size part [index] must have. */
        public long expectedPartSize(int index) {
            return index == partCount() - 1 ? totalSize - partSize * (long) index : partSize;
        }

        public long receivedBytes() {
            long n = 0;
            for (int i : received) n += expectedPartSize(i);
            return n;
        }

        public boolean complete() {
            return received.size() == partCount();
        }
    }

    private final File root;

    public ChunkedUploads(File root) {
        this.root = root;
    }

    /** The part size actually used for a requested one (0 or less = the default). */
    public static long clampPartSize(long requested) {
        if (requested <= 0) return DEFAULT_PART_SIZE;
        return Math.max(MIN_PART_SIZE, Math.min(MAX_PART_SIZE, requested));
    }

    public synchronized Session start(int customerId, int userId, String fileName, long totalSize, long partSize,
                                      String sha256) throws UploadException, IOException {
        if (fileName == null || fileName.trim().isEmpty()) {
            throw new UploadException("name", "Falta el nombre del archivo.");
        }
        if (totalSize <= 0) {
            throw new UploadException("size", "El archivo está vacío.");
        }
        if (totalSize > MAX_TOTAL_SIZE) {
            throw new UploadException("size", "El archivo supera el máximo de " + (MAX_TOTAL_SIZE >> 30) + " GB.");
        }
        if (sha256 != null && !sha256.trim().isEmpty() && !SHA256.matcher(sha256.trim()).matches()) {
            throw new UploadException("sha256", "El SHA-256 declarado no es válido.");
        }
        Session s = new Session();
        s.id = UUID.randomUUID().toString();
        s.customerId = customerId;
        s.userId = userId;
        // Only the last path element: the name never chooses where anything is written.
        s.fileName = new File(fileName.trim().replace('\\', '/')).getName();
        s.totalSize = totalSize;
        s.partSize = clampPartSize(partSize);
        s.sha256 = sha256 == null || sha256.trim().isEmpty() ? null : sha256.trim().toLowerCase();
        s.createdAt = System.currentTimeMillis();
        File dir = dir(s.id);
        if (!dir.mkdirs()) throw new IOException("cannot create " + dir);
        writeMeta(dir, s);
        return s;
    }

    /** The upload and the parts it already has, or null when there is none with that id (finished, removed, never existed). */
    public synchronized Session get(String id) {
        if (id == null || !ID.matcher(id).matches()) return null;
        File dir = dir(id);
        File meta = new File(dir, "meta.properties");
        if (!meta.isFile()) return null;
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(meta)) {
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
        try {
            Session s = new Session();
            s.id = id;
            s.customerId = Integer.parseInt(p.getProperty("customerId"));
            s.userId = Integer.parseInt(p.getProperty("userId"));
            s.fileName = p.getProperty("fileName");
            s.totalSize = Long.parseLong(p.getProperty("totalSize"));
            s.partSize = Long.parseLong(p.getProperty("partSize"));
            String sha = p.getProperty("sha256", "");
            s.sha256 = sha.isEmpty() ? null : sha;
            s.createdAt = Long.parseLong(p.getProperty("createdAt"));
            int count = s.partCount();
            for (int i = 0; i < count; i++) {
                File part = new File(dir, "part-" + i);
                if (part.isFile() && part.length() == s.expectedPartSize(i)) s.received.add(i);
            }
            return s;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Store part [index]. The body must be exactly the size that part has; [partSha256] (optional) must match it.
     * Sending a part again replaces it, so a retry after a cut connection is always safe.
     */
    public Session putPart(String id, int index, InputStream body, String partSha256) throws UploadException, IOException {
        Session s = get(id);
        if (s == null) throw new UploadException("gone", GONE);
        if (index < 0 || index >= s.partCount()) {
            throw new UploadException("index", "La parte " + index + " no existe en esta subida.");
        }
        long expected = s.expectedPartSize(index);
        File dir = dir(id);
        File tmp = new File(dir, "part-" + index + "." + UUID.randomUUID() + ".tmp");
        try {
            MessageDigest md = sha256();
            long n = 0;
            try (OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = body.read(buf)) >= 0) {
                    n += r;
                    if (n > expected) {
                        throw new UploadException("partSize", "La parte " + index + " es más grande de lo esperado.");
                    }
                    md.update(buf, 0, r);
                    out.write(buf, 0, r);
                }
            }
            if (n != expected) {
                throw new UploadException("partSize", "La parte " + index + " llegó incompleta (" + n + " de " + expected
                        + " bytes); envíala de nuevo.");
            }
            if (partSha256 != null && !partSha256.trim().isEmpty() && !hex(md.digest()).equalsIgnoreCase(partSha256.trim())) {
                throw new UploadException("partSha256", "La parte " + index + " llegó dañada (no coincide su SHA-256); envíala de nuevo.");
            }
            synchronized (this) {
                File meta = new File(dir, "meta.properties");
                if (!meta.isFile()) throw new UploadException("gone", GONE);
                Files.move(tmp.toPath(), new File(dir, "part-" + index).toPath(), StandardCopyOption.REPLACE_EXISTING);
                // The meta file's date is the upload's last activity (what cleanupAbandoned looks at).
                meta.setLastModified(System.currentTimeMillis());
            }
        } finally {
            tmp.delete();
        }
        return get(id);
    }

    /**
     * Put the parts together into [dest], check the whole file (size, and SHA-256 when one was declared) and remove
     * the upload. Returns the file's SHA-256. On a mismatch the upload is removed too: its parts cannot be trusted.
     */
    public String assemble(String id, File dest) throws UploadException, IOException {
        Session s = get(id);
        if (s == null) throw new UploadException("gone", GONE);
        if (!s.complete()) {
            int missing = s.partCount() - s.received.size();
            throw new UploadException("incomplete", "Faltan " + missing + " parte" + (missing == 1 ? "" : "s") + " por subir.");
        }
        File dir = dir(id);
        MessageDigest md = sha256();
        long n = 0;
        try (OutputStream out = new java.io.BufferedOutputStream(new FileOutputStream(dest), 256 * 1024)) {
            byte[] buf = new byte[256 * 1024];
            for (int i = 0; i < s.partCount(); i++) {
                try (InputStream in = new FileInputStream(new File(dir, "part-" + i))) {
                    int r;
                    while ((r = in.read(buf)) >= 0) {
                        md.update(buf, 0, r);
                        out.write(buf, 0, r);
                        n += r;
                    }
                }
            }
        } catch (IOException e) {
            dest.delete();
            throw e;
        }
        String actual = hex(md.digest());
        if (n != s.totalSize) {
            dest.delete();
            abort(id);
            throw new UploadException("size", "El archivo armado no mide lo declarado; súbelo de nuevo.");
        }
        if (s.sha256 != null && !s.sha256.equals(actual)) {
            dest.delete();
            abort(id);
            throw new UploadException("sha256", "El archivo llegó dañado (su SHA-256 no coincide con el del original); súbelo de nuevo.");
        }
        abort(id);
        return actual;
    }

    /** Remove an upload and its parts (finished, cancelled or abandoned). */
    public synchronized void abort(String id) {
        if (id == null || !ID.matcher(id).matches()) return;
        deleteDir(dir(id));
    }

    /** Remove the uploads with no activity since {@code now - ABANDONED_MS}; returns how many. */
    public synchronized int cleanupAbandoned(long now) {
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return 0;
        int removed = 0;
        for (File d : dirs) {
            if (!ID.matcher(d.getName()).matches()) continue;
            File meta = new File(d, "meta.properties");
            long last = meta.isFile() ? meta.lastModified() : d.lastModified();
            if (now - last > ABANDONED_MS) {
                deleteDir(d);
                removed++;
            }
        }
        return removed;
    }

    /** Bytes the customer's unfinished uploads will take once whole: they count against its storage limit. */
    public synchronized long reservedBytes(int customerId, String exceptId) {
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return 0;
        long total = 0;
        for (File d : dirs) {
            if (d.getName().equals(exceptId)) continue;
            Session s = get(d.getName());
            if (s != null && s.customerId == customerId) total += s.totalSize;
        }
        return total;
    }

    private File dir(String id) {
        return new File(root, id);
    }

    private static void writeMeta(File dir, Session s) throws IOException {
        Properties p = new Properties();
        p.setProperty("customerId", String.valueOf(s.customerId));
        p.setProperty("userId", String.valueOf(s.userId));
        p.setProperty("fileName", s.fileName);
        p.setProperty("totalSize", String.valueOf(s.totalSize));
        p.setProperty("partSize", String.valueOf(s.partSize));
        p.setProperty("sha256", s.sha256 == null ? "" : s.sha256);
        p.setProperty("createdAt", String.valueOf(s.createdAt));
        try (java.io.Writer w = new java.io.OutputStreamWriter(new FileOutputStream(new File(dir, "meta.properties")), StandardCharsets.UTF_8)) {
            p.store(w, null);
        }
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
