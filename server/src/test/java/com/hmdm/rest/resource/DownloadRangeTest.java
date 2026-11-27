package com.hmdm.rest.resource;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/** The byte ranges the agent's resumable download asks the file servlet for. */
public class DownloadRangeTest {
    private static final long LEN = 1000;

    @Test
    public void an_open_range_runs_to_the_last_byte() {
        assertArrayEquals(new long[]{400, 999}, DownloadFilesServlet.parseRange("400-", LEN));
        assertArrayEquals(new long[]{0, 999}, DownloadFilesServlet.parseRange("0-", LEN));
    }

    @Test
    public void a_closed_range_includes_both_ends_and_is_cut_at_the_file_size() {
        assertArrayEquals(new long[]{0, 99}, DownloadFilesServlet.parseRange("0-99", LEN));
        assertArrayEquals(new long[]{990, 999}, DownloadFilesServlet.parseRange("990-5000", LEN));
        assertArrayEquals(new long[]{5, 5}, DownloadFilesServlet.parseRange("5-5", LEN));
    }

    @Test
    public void a_suffix_range_is_the_last_bytes() {
        assertArrayEquals(new long[]{900, 999}, DownloadFilesServlet.parseRange("-100", LEN));
        assertArrayEquals(new long[]{0, 999}, DownloadFilesServlet.parseRange("-5000", LEN));
    }

    @Test
    public void a_range_at_or_past_the_end_cannot_be_satisfied() {
        assertNull("a download already whole asks from its own size: 416", DownloadFilesServlet.parseRange("1000-", LEN));
        assertNull(DownloadFilesServlet.parseRange("5000-6000", LEN));
        assertNull(DownloadFilesServlet.parseRange("10-5", LEN));
        assertNull(DownloadFilesServlet.parseRange("0-", 0));
    }

    @Test
    public void only_the_first_of_several_ranges_is_served_and_garbage_is_rejected() {
        assertArrayEquals(new long[]{0, 9}, DownloadFilesServlet.parseRange("0-9, 20-29", LEN));
        try {
            DownloadFilesServlet.parseRange("abc", LEN);
            fail();
        } catch (IllegalArgumentException expected) {
            // served as the whole file by the caller
        }
    }
}
