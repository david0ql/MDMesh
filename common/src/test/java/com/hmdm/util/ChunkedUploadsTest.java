package com.hmdm.util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.*;

public class ChunkedUploadsTest {
    private File root;
    private File dest;
    private ChunkedUploads uploads;
    private static final int PART = (int) ChunkedUploads.MIN_PART_SIZE;

    @Before
    public void setUp() throws Exception {
        root = Files.createTempDirectory("chunked").toFile();
        dest = File.createTempFile("assembled", ".bin");
        uploads = new ChunkedUploads(root);
    }

    @After
    public void tearDown() {
        dest.delete();
        File[] dirs = root.listFiles();
        if (dirs != null) for (File d : dirs) uploads.abort(d.getName());
        root.delete();
    }

    private static byte[] data(int size) {
        byte[] b = new byte[size];
        new Random(7).nextBytes(b);
        return b;
    }

    private static String sha(byte[] b) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] part(byte[] all, int index) {
        return Arrays.copyOfRange(all, index * PART, Math.min(all.length, (index + 1) * PART));
    }

    private void put(String id, byte[] all, int index) throws Exception {
        byte[] p = part(all, index);
        uploads.putPart(id, index, new ByteArrayInputStream(p), sha(p));
    }

    @Test
    public void parts_sent_in_any_order_are_put_together_and_the_whole_file_is_verified() throws Exception {
        byte[] all = data(PART * 2 + 1234);
        ChunkedUploads.Session s = uploads.start(1, 10, "app.apk", all.length, PART, sha(all));
        assertEquals(3, s.partCount());
        put(s.id, all, 2);
        put(s.id, all, 0);
        assertEquals(Arrays.asList(0, 2), new java.util.ArrayList<>(uploads.get(s.id).received));
        put(s.id, all, 1);
        assertEquals(sha(all), uploads.assemble(s.id, dest));
        assertArrayEquals(all, Files.readAllBytes(dest.toPath()));
        assertNull("a finished upload is removed", uploads.get(s.id));
    }

    @Test
    public void an_interrupted_upload_resumes_from_the_parts_already_received() throws Exception {
        byte[] all = data(PART * 3);
        ChunkedUploads.Session s = uploads.start(1, 10, "big.xapk", all.length, PART, null);
        put(s.id, all, 0);
        // The server restarted: a new instance over the same directory still knows the upload.
        ChunkedUploads again = new ChunkedUploads(root);
        ChunkedUploads.Session seen = again.get(s.id);
        assertEquals(1, seen.received.size());
        assertEquals(PART, seen.receivedBytes());
        try {
            again.assemble(s.id, dest);
            fail("must not assemble with parts missing");
        } catch (ChunkedUploads.UploadException e) {
            assertEquals("incomplete", e.code);
        }
        uploads = again;
        put(s.id, all, 1);
        put(s.id, all, 2);
        assertEquals(sha(all), again.assemble(s.id, dest));
    }

    @Test
    public void a_damaged_or_short_part_is_refused_and_can_be_sent_again() throws Exception {
        byte[] all = data(PART + 100);
        ChunkedUploads.Session s = uploads.start(1, 10, "app.apk", all.length, PART, sha(all));
        byte[] p0 = part(all, 0);
        byte[] bad = p0.clone();
        bad[5] ^= 1;
        try {
            uploads.putPart(s.id, 0, new ByteArrayInputStream(bad), sha(p0));
            fail();
        } catch (ChunkedUploads.UploadException e) {
            assertEquals("partSha256", e.code);
        }
        try {
            uploads.putPart(s.id, 0, new ByteArrayInputStream(Arrays.copyOf(p0, 50)), null);
            fail();
        } catch (ChunkedUploads.UploadException e) {
            assertEquals("partSize", e.code);
        }
        try {
            uploads.putPart(s.id, 1, new ByteArrayInputStream(new byte[PART]), null);
            fail("the last part is 100 bytes, not a whole part");
        } catch (ChunkedUploads.UploadException e) {
            assertEquals("partSize", e.code);
        }
        assertTrue(uploads.get(s.id).received.isEmpty());
        put(s.id, all, 0);
        put(s.id, all, 1);
        assertEquals(sha(all), uploads.assemble(s.id, dest));
    }

    @Test
    public void a_whole_file_that_does_not_match_its_declared_sha256_is_refused_and_removed() throws Exception {
        byte[] all = data(PART + 1);
        ChunkedUploads.Session s = uploads.start(1, 10, "app.apk", all.length, PART, sha(new byte[]{1}));
        // Parts without their own checksum: only the whole-file check can catch it.
        uploads.putPart(s.id, 0, new ByteArrayInputStream(part(all, 0)), null);
        uploads.putPart(s.id, 1, new ByteArrayInputStream(part(all, 1)), null);
        try {
            uploads.assemble(s.id, dest);
            fail();
        } catch (ChunkedUploads.UploadException e) {
            assertEquals("sha256", e.code);
        }
        assertFalse(dest.exists());
        assertNull(uploads.get(s.id));
    }

    @Test
    public void part_size_is_capped_at_fifty_megabytes() {
        assertEquals(ChunkedUploads.MAX_PART_SIZE, ChunkedUploads.clampPartSize(200L * 1024 * 1024));
        assertEquals(ChunkedUploads.DEFAULT_PART_SIZE, ChunkedUploads.clampPartSize(0));
        assertEquals(ChunkedUploads.MIN_PART_SIZE, ChunkedUploads.clampPartSize(10));
        assertTrue(ChunkedUploads.MAX_PART_SIZE <= 50L * 1024 * 1024);
    }

    @Test
    public void abandoned_uploads_are_cleaned_up_and_active_ones_kept() throws Exception {
        byte[] all = data(PART);
        ChunkedUploads.Session old = uploads.start(1, 10, "old.apk", all.length, PART, null);
        ChunkedUploads.Session fresh = uploads.start(1, 10, "fresh.apk", all.length, PART, null);
        long now = System.currentTimeMillis();
        new File(new File(root, old.id), "meta.properties").setLastModified(now - ChunkedUploads.ABANDONED_MS - 60_000);
        assertEquals(1, uploads.cleanupAbandoned(now));
        assertNull(uploads.get(old.id));
        assertNotNull(uploads.get(fresh.id));
        try {
            uploads.putPart(old.id, 0, new ByteArrayInputStream(all), null);
            fail();
        } catch (ChunkedUploads.UploadException e) {
            assertEquals("gone", e.code);
        }
    }

    @Test
    public void unfinished_uploads_count_against_the_customers_storage() throws Exception {
        ChunkedUploads.Session a = uploads.start(1, 10, "a.apk", 1000, PART, null);
        uploads.start(1, 11, "b.apk", 500, PART, null);
        uploads.start(2, 12, "c.apk", 70, PART, null);
        assertEquals(1500, uploads.reservedBytes(1, null));
        assertEquals(500, uploads.reservedBytes(1, a.id));
        assertEquals(70, uploads.reservedBytes(2, null));
    }

    @Test
    public void ids_and_names_cannot_escape_the_upload_directory() throws Exception {
        assertNull(uploads.get("../etc"));
        assertNull(uploads.get(null));
        ChunkedUploads.Session s = uploads.start(1, 10, "../../evil/app.apk", 10, PART, null);
        assertEquals("app.apk", s.fileName);
        try {
            uploads.start(1, 10, "x.apk", 0, PART, null);
            fail();
        } catch (ChunkedUploads.UploadException e) {
            assertEquals("size", e.code);
        }
        try {
            uploads.start(1, 10, "x.apk", 10, PART, "zz");
            fail();
        } catch (ChunkedUploads.UploadException e) {
            assertEquals("sha256", e.code);
        }
    }
}
