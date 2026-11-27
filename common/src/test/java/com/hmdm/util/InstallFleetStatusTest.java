package com.hmdm.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class InstallFleetStatusTest {
    private static final Set<String> OWNER = new HashSet<>(Arrays.asList("app.silentInstall", "device.lock"));
    private static final Set<String> NOT_OWNER = new HashSet<>(Collections.singletonList("device.lock"));
    private static final String CAP = "app.silentInstall";

    private static InstallFleetStatus.Cmd cmd(int id, String status, String detail, long vc, long at) {
        return new InstallFleetStatus.Cmd(id, status, detail, vc, CAP, at, "pending".equals(status) || "delivered".equals(status) ? null : at + 1000);
    }

    private static List<InstallFleetStatus.Cmd> list(InstallFleetStatus.Cmd... c) {
        return new ArrayList<>(Arrays.asList(c));
    }

    // --- failure reasons ----------------------------------------------------------------------------------------

    @Test
    public void common_android_codes_are_explained_in_spanish() {
        assertEquals("storage", InstallFailureReasons.translate("INSTALL_FAILED_INSUFFICIENT_STORAGE: not enough space").code);
        assertEquals("storage", InstallFailureReasons.translate("FAILURE_STORAGE").code);
        assertEquals("storage", InstallFailureReasons.translate("install session error: write failed: ENOSPC (No space left on device)").code);
        assertEquals("signature", InstallFailureReasons.translate(
                "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.x signatures do not match newer version; ignoring!").code);
        assertEquals("signature", InstallFailureReasons.translate("FAILURE_CONFLICT").code);
        assertEquals("downgrade", InstallFailureReasons.translate("INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected").code);
        assertEquals("downgrade", InstallFailureReasons.translate("downgrade blocked for com.x: uninstall the current version first").code);
        assertEquals("blocked", InstallFailureReasons.translate("INSTALL_FAILED_USER_RESTRICTED: Install canceled by user").code);
        assertEquals("blocked", InstallFailureReasons.translate("FAILURE_BLOCKED").code);
        assertEquals("invalid", InstallFailureReasons.translate("INSTALL_PARSE_FAILED_NOT_APK: Failed to parse /data/app/vmdl1.tmp/part_0.apk").code);
        assertEquals("invalid", InstallFailureReasons.translate("FAILURE_INVALID").code);
        assertEquals("incompatible", InstallFailureReasons.translate("INSTALL_FAILED_OLDER_SDK: Requires newer sdk version #34").code);
        assertEquals("incompatible", InstallFailureReasons.translate("INSTALL_FAILED_NO_MATCHING_ABIS").code);
        assertEquals("checksum", InstallFailureReasons.translate("checksum mismatch: expected aa got bb").code);
        assertEquals("timeout", InstallFailureReasons.translate("INSTALL_TIMEOUT: Android no respondió en 10 min").code);
        assertEquals("notOwner", InstallFailureReasons.translate("unexpected STATUS_PENDING_USER_ACTION — silent install requires Device Owner / privilege").code);
        assertEquals("download", InstallFailureReasons.translate("apk fetch failed: HTTP 404 for https://x/y.apk").code);
        assertEquals("network", InstallFailureReasons.translate("apk fetch failed: timeout").code);
        assertEquals("unknown", InstallFailureReasons.translate(null).code);
        assertEquals("unknown", InstallFailureReasons.translate("FAILURE_UNKNOWN").code);
        for (String d : new String[]{"FAILURE_STORAGE", "FAILURE_CONFLICT", "FAILURE_INVALID", "x", null}) {
            String text = InstallFailureReasons.translate(d).text;
            assertTrue(text, text.length() > 15 && !text.contains("FAILURE_"));
        }
    }

    @Test
    public void only_failures_that_can_fix_themselves_are_retryable() {
        assertTrue(InstallFailureReasons.translate("FAILURE_STORAGE").retryable);
        assertTrue(InstallFailureReasons.translate("INSTALL_TIMEOUT").retryable);
        assertTrue(InstallFailureReasons.translate("checksum mismatch: expected a got b").retryable);
        assertFalse(InstallFailureReasons.translate("INSTALL_FAILED_UPDATE_INCOMPATIBLE").retryable);
        assertFalse(InstallFailureReasons.translate("INSTALL_FAILED_VERSION_DOWNGRADE").retryable);
        assertFalse(InstallFailureReasons.translate("INSTALL_PARSE_FAILED_NOT_APK").retryable);
        assertFalse(InstallFailureReasons.translate("FAILURE_BLOCKED").retryable);
    }

    // --- retry policy -------------------------------------------------------------------------------------------

    @Test
    public void retries_wait_longer_each_time_and_stop_at_the_cap() {
        long t = 1_000_000L;
        Long prev = null;
        for (int n = 1; n <= InstallRetryPolicy.MAX_RETRIES; n++) {
            Long at = InstallRetryPolicy.nextRetryAt(n, t, true);
            assertNotNull(at);
            if (prev != null) assertTrue("wait " + n + " must be longer", at > prev);
            prev = at;
        }
        assertEquals(Long.valueOf(t + 10 * 60_000L), InstallRetryPolicy.nextRetryAt(1, t, true));
        assertNull("no retry past the cap", InstallRetryPolicy.nextRetryAt(InstallRetryPolicy.MAX_RETRIES + 1, t, true));
        assertNull("no retry of a failure that cannot fix itself", InstallRetryPolicy.nextRetryAt(1, t, false));
        assertFalse(InstallRetryPolicy.due(1, t, true, t + 9 * 60_000L));
        assertTrue(InstallRetryPolicy.due(1, t, true, t + 10 * 60_000L));
        assertFalse(InstallRetryPolicy.due(2, t, true, t + 10 * 60_000L));
    }

    // --- classification -----------------------------------------------------------------------------------------

    @Test
    public void done_at_the_target_version_is_installed() {
        InstallFleetStatus.Result r = InstallFleetStatus.classify(7, list(cmd(1, "done", null, 7, 100)), OWNER);
        assertEquals(InstallFleetStatus.State.INSTALLED, r.state);
        r = InstallFleetStatus.classify(7, list(cmd(1, "done", "skipped: already at requested version", 7, 100)), OWNER);
        assertEquals(InstallFleetStatus.State.INSTALLED, r.state);
    }

    @Test
    public void an_install_of_an_older_version_does_not_count_for_the_new_target() {
        InstallFleetStatus.Result r = InstallFleetStatus.classify(8, list(cmd(1, "done", null, 7, 100)), OWNER);
        assertEquals(InstallFleetStatus.State.NOT_QUEUED, r.state);
        assertNotNull(r.reason);
    }

    @Test
    public void a_pending_install_on_a_phone_that_is_not_device_owner_is_held_with_the_reason() {
        InstallFleetStatus.Result r = InstallFleetStatus.classify(7, list(cmd(1, "pending", null, 7, 100)), NOT_OWNER);
        assertEquals(InstallFleetStatus.State.HELD, r.state);
        assertEquals("notOwner", r.reasonCode);
        assertTrue(r.reason.contains("Device Owner"));

        r = InstallFleetStatus.classify(7, list(cmd(1, "pending", null, 7, 100)), Collections.<String>emptySet());
        assertEquals(InstallFleetStatus.State.HELD, r.state);
        assertEquals("noCapabilities", r.reasonCode);

        r = InstallFleetStatus.classify(7, list(cmd(1, "pending", null, 7, 100)), OWNER);
        assertEquals(InstallFleetStatus.State.PENDING, r.state);
        r = InstallFleetStatus.classify(7, list(cmd(1, "delivered", null, 7, 100)), OWNER);
        assertEquals(InstallFleetStatus.State.INSTALLING, r.state);
    }

    @Test
    public void a_failure_carries_its_reason_its_attempts_and_the_next_retry() {
        List<InstallFleetStatus.Cmd> cmds = list(
                cmd(1, "done", null, 6, 50),
                cmd(2, "failed", "FAILURE_STORAGE", 7, 100),
                cmd(3, "failed", "INSTALL_FAILED_INSUFFICIENT_STORAGE", 7, 5000));
        InstallFleetStatus.Result r = InstallFleetStatus.classify(7, cmds, OWNER);
        assertEquals(InstallFleetStatus.State.FAILED, r.state);
        assertEquals("storage", r.reasonCode);
        assertEquals(2, r.attempts);
        assertEquals(Integer.valueOf(3), r.commandId);
        assertEquals(Long.valueOf(6000 + 30 * 60_000L), r.nextRetryAt);
        assertEquals("failed", r.toMap().get("state"));
    }

    @Test
    public void a_signature_failure_is_not_retried_by_itself() {
        InstallFleetStatus.Result r = InstallFleetStatus.classify(7,
                list(cmd(2, "failed", "INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match", 7, 100)), OWNER);
        assertEquals("signature", r.reasonCode);
        assertNull(r.nextRetryAt);
        assertEquals(1, r.attempts);
    }

    @Test
    public void retries_run_out_after_the_cap() {
        List<InstallFleetStatus.Cmd> cmds = new ArrayList<>();
        for (int i = 1; i <= InstallRetryPolicy.MAX_RETRIES + 1; i++) cmds.add(cmd(i, "failed", "FAILURE_STORAGE", 7, i * 100L));
        InstallFleetStatus.Result r = InstallFleetStatus.classify(7, cmds, OWNER);
        assertEquals(InstallRetryPolicy.MAX_RETRIES + 1, r.attempts);
        assertNull(r.nextRetryAt);
    }

    @Test
    public void an_expired_order_and_an_os_block_are_failures_too() {
        InstallFleetStatus.Result r = InstallFleetStatus.classify(7, list(cmd(1, "expired", null, 7, 100)), OWNER);
        assertEquals(InstallFleetStatus.State.FAILED, r.state);
        assertEquals("expired", r.reasonCode);
        assertNotNull(r.nextRetryAt);

        r = InstallFleetStatus.classify(7, list(cmd(1, "done",
                "skipped: el sistema del teléfono no deja instalar esta app desde el MDM (INSTALL_FAILED_USER_RESTRICTED)", 7, 100)), OWNER);
        assertEquals(InstallFleetStatus.State.FAILED, r.state);
        assertEquals("blocked", r.reasonCode);
        assertNull(r.nextRetryAt);
    }

    @Test
    public void a_success_between_failures_resets_the_attempt_count() {
        List<InstallFleetStatus.Cmd> cmds = list(
                cmd(1, "failed", "FAILURE_STORAGE", 7, 100),
                cmd(2, "done", null, 7, 200),
                cmd(3, "failed", "FAILURE_ABORTED", 7, 300));
        assertEquals(1, InstallFleetStatus.classify(7, cmds, OWNER).attempts);
    }

    @Test
    public void payload_target_is_read_even_with_spaces() {
        Object[] t = InstallFleetStatus.payloadTarget("{ \"packageName\": \"com.x\", \"versionCode\": 12 }");
        assertEquals(12L, t[0]);
        assertEquals("com.x", t[1]);
        assertNull(InstallFleetStatus.payloadTarget("nope")[1]);
    }
}
