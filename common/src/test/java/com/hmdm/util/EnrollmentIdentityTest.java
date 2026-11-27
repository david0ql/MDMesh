package com.hmdm.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EnrollmentIdentityTest {

    @Test
    public void a_real_serial_is_kept_and_placeholders_are_dropped() {
        assertEquals("ZY32FCBNM5", EnrollmentIdentity.usableSerial(" ZY32FCBNM5 "));
        assertNull(EnrollmentIdentity.usableSerial(null));
        assertNull(EnrollmentIdentity.usableSerial("unknown"));
        assertNull(EnrollmentIdentity.usableSerial("0123456789ABCDEF"));
        assertNull(EnrollmentIdentity.usableSerial("000000000000"));
        assertNull(EnrollmentIdentity.usableSerial("abc"));
        // Every emulator of one Android release reports the same serial: never a device identity.
        assertNull(EnrollmentIdentity.usableSerial("EMULATOR36X1X8X0"));
    }

    @Test
    public void an_imei_must_look_like_one() {
        assertEquals("356938035643809", EnrollmentIdentity.usableImei("356938035643809"));
        assertNull(EnrollmentIdentity.usableImei("000000000000000"));
        assertNull(EnrollmentIdentity.usableImei("12345"));
        assertNull(EnrollmentIdentity.usableImei("35693803564380X"));
        assertNull(EnrollmentIdentity.usableImei(null));
        // The Android emulator's fixed IMEIs.
        assertNull(EnrollmentIdentity.usableImei("358240051111110"));
        assertNull(EnrollmentIdentity.usableImei("867400022047199"));
    }

    @Test
    public void the_device_that_claimed_a_token_may_retry_with_it() {
        // Same hardware id: the response of the first enroll was lost and the agent asks again.
        assertTrue(EnrollmentIdentity.sameDevice("hw-1", null, null, "hw-1", null, null));
        // The phone was reset in between (ANDROID_ID changed below Android 12) but it is the same serial / IMEI.
        assertTrue(EnrollmentIdentity.sameDevice("hw-1", "ZY32FCBNM5", null, "hw-2", "ZY32FCBNM5", null));
        assertTrue(EnrollmentIdentity.sameDevice("hw-1", null, "356938035643809", "hw-2", null, "356938035643809"));
    }

    @Test
    public void another_device_cannot_reuse_a_claimed_token() {
        assertFalse(EnrollmentIdentity.sameDevice("hw-1", "ZY32FCBNM5", null, "hw-2", "ZY99999999", null));
        assertFalse(EnrollmentIdentity.sameDevice("hw-1", null, null, null, null, null));
        assertFalse(EnrollmentIdentity.sameDevice(null, null, null, null, null, null));
        assertFalse(EnrollmentIdentity.sameDevice(null, null, null, "hw-2", null, null));
        // Placeholder serials never match two phones.
        assertFalse(EnrollmentIdentity.sameDevice("hw-1", "unknown", null, "hw-2", "unknown", null));
        assertFalse(EnrollmentIdentity.sameDevice("", null, null, "", null, null));
    }

    @Test
    public void the_download_user_agent_gives_model_and_android() {
        String[] m = EnrollmentIdentity.parseDownloadUserAgent(
                "AndroidDownloadManager/11 (Linux; U; Android 11; SM-A125M Build/RP1A.200720.012)");
        assertEquals("11", m[0]);
        assertEquals("SM-A125M", m[1]);
        m = EnrollmentIdentity.parseDownloadUserAgent("Dalvik/2.1.0 (Linux; U; Android 9; moto g(7) Build/PPO29.114)");
        assertEquals("9", m[0]);
        assertEquals("moto g(7)", m[1]);
        m = EnrollmentIdentity.parseDownloadUserAgent("curl/8.0");
        assertNull(m[0]);
        assertNull(m[1]);
        assertNull(EnrollmentIdentity.parseDownloadUserAgent(null)[0]);
    }

    @Test
    public void only_an_apk_under_the_published_folders_is_a_download_target() {
        assertTrue(EnrollmentIdentity.isAgentApkPath("/files/dallycontrol-agent.apk"));
        assertTrue(EnrollmentIdentity.isAgentApkPath("/update/agent.apk"));
        assertFalse(EnrollmentIdentity.isAgentApkPath("//evil.example/files/a.apk"));
        assertFalse(EnrollmentIdentity.isAgentApkPath("/files/../rest/private/x.apk"));
        assertFalse(EnrollmentIdentity.isAgentApkPath("https://evil.example/a.apk"));
        assertFalse(EnrollmentIdentity.isAgentApkPath("/files/a.txt"));
        assertFalse(EnrollmentIdentity.isAgentApkPath(null));
    }

    @Test
    public void text_is_capped_for_storage() {
        assertEquals("abc", EnrollmentIdentity.cap(" abc ", 10));
        assertEquals("ab", EnrollmentIdentity.cap("abcdef", 2));
        assertNull(EnrollmentIdentity.cap("  ", 5));
        assertNull(EnrollmentIdentity.cap(null, 5));
    }
}
