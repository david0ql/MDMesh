package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

/** Pure-unit tests for {@link RemoteSessions}: capability parsing, session secrets and the start payload. */
public class RemoteSessionsTest {

    @Test
    public void testTierAndTransports() {
        String json = "{\"remoteControl\":{\"tier\":\"control\",\"transport\":[\"vnc-repeater\",\"vnc-repeater-wss\"]}}";
        Assert.assertEquals("control", RemoteSessions.tier(json));
        Assert.assertEquals(Arrays.asList("vnc-repeater", "vnc-repeater-wss"), RemoteSessions.transports(json));
        Assert.assertTrue(RemoteSessions.supportsEncrypted(json));
    }

    @Test
    public void testOlderAgentIsPlainOnly() {
        String json = "{\"remoteControl\":{\"tier\":\"view\",\"transport\":[\"vnc-repeater\"]}}";
        Assert.assertEquals("view", RemoteSessions.tier(json));
        Assert.assertFalse(RemoteSessions.supportsEncrypted(json));
    }

    @Test
    public void testMissingOrBrokenCapabilities() {
        Assert.assertEquals("none", RemoteSessions.tier(null));
        Assert.assertEquals("none", RemoteSessions.tier("not json"));
        Assert.assertEquals("none", RemoteSessions.tier("{\"policy\":[]}"));
        Assert.assertTrue(RemoteSessions.transports("{\"remoteControl\":{\"tier\":\"none\"}}").isEmpty());
    }

    @Test
    public void testSessionIdIsEighteenDecimalDigits() {
        for (int i = 0; i < 1000; i++) {
            String id = RemoteSessions.newSessionId();
            Assert.assertTrue(id, id.matches("[1-9][0-9]{17}"));
        }
    }

    @Test
    public void testPasswordIsEightHexChars() {
        Assert.assertTrue(RemoteSessions.newPassword().matches("[0-9a-f]{8}"));
    }

    @Test
    public void testStartPayload() throws Exception {
        JsonNode enc = new ObjectMapper().readTree(RemoteSessions.startPayload("123456789012345678", "abcd1234", true, true));
        Assert.assertEquals("123456789012345678", enc.get("sessionId").asText());
        Assert.assertEquals("abcd1234", enc.get("password").asText());
        Assert.assertTrue(enc.get("viewOnly").asBoolean());
        Assert.assertEquals("wss", enc.get("transport").asText());

        JsonNode plain = new ObjectMapper().readTree(RemoteSessions.startPayload("123456789012345678", "abcd1234", false, false));
        Assert.assertFalse(plain.has("transport"));
    }
}
