package com.hmdm.rest.resource;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** The validator Android's downloader needs to resume the agent download during QR provisioning. */
public class DownloadResumeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void a_file_has_a_quoted_validator_that_changes_when_the_file_is_replaced() throws Exception {
        File apk = tmp.newFile("agent.apk");
        Files.write(apk.toPath(), new byte[1000]);
        assertTrue(apk.setLastModified(1_795_000_000_000L));
        String first = DownloadFilesServlet.etagOf(apk);
        assertTrue(first.startsWith("\"") && first.endsWith("\"") && first.length() > 4);
        assertEquals("same file, same validator", first, DownloadFilesServlet.etagOf(apk));

        Files.write(apk.toPath(), new byte[1001]);
        assertTrue(apk.setLastModified(1_795_000_000_000L));
        assertNotEquals("another size", first, DownloadFilesServlet.etagOf(apk));

        Files.write(apk.toPath(), new byte[1000]);
        assertTrue(apk.setLastModified(1_795_000_060_000L));
        assertNotEquals("same size, published again later", first, DownloadFilesServlet.etagOf(apk));
    }

    @Test
    public void a_resume_is_served_only_for_the_same_version_of_the_file() {
        String etag = "\"3e8-1a2b3c\"";
        assertTrue("no condition", DownloadFilesServlet.matches(null, etag));
        assertTrue(DownloadFilesServlet.matches("", etag));
        assertTrue(DownloadFilesServlet.matches(etag, etag));
        assertTrue(DownloadFilesServlet.matches("*", etag));
        assertTrue("weak form", DownloadFilesServlet.matches("W/" + etag, etag));
        assertTrue("one of several", DownloadFilesServlet.matches("\"old\", " + etag, etag));
        assertFalse("the APK was replaced mid-download", DownloadFilesServlet.matches("\"3e7-1a2b3c\"", etag));
        assertFalse("a date in If-Range sends the whole file", DownloadFilesServlet.matches("Fri, 27 Nov 2026 17:20:05 GMT", etag));
    }
}
