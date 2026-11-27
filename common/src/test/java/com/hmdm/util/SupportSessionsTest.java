package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.rest.json.agent.DesiredConfig;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;

/** Rules of the remote-support session channel (ADR 0012) and its policy settings. */
public class SupportSessionsTest {

    @Test
    public void keysAndTokensAreRandomHex() {
        String key = SupportSessions.newKey();
        Assert.assertTrue(key, SupportSessions.isKey(key));
        Assert.assertTrue(SupportSessions.newToken().matches("^[0-9a-f]{64}$"));
        Assert.assertNotEquals(key, SupportSessions.newKey());
        Assert.assertFalse(SupportSessions.isKey(null));
        Assert.assertFalse(SupportSessions.isKey("../../etc"));
        Assert.assertFalse(SupportSessions.isKey(key.toUpperCase()));
    }

    @Test
    public void viewOnlyOperatorCannotActOnThePhone() {
        for (String t : new String[] {"tap", "swipe", "key", "text", "clip.set"}) {
            Assert.assertTrue(t, SupportSessions.isInput(t));
            Assert.assertFalse(t, SupportSessions.consoleMayRelay(t, true));
            Assert.assertTrue(t, SupportSessions.consoleMayRelay(t, false));
        }
        // Looking, talking and chatting stay allowed.
        for (String t : new String[] {"screen.start", "ack", "chat", "call.start", "call.end", "file.list", "bye"}) {
            Assert.assertTrue(t, SupportSessions.consoleMayRelay(t, true));
        }
    }

    @Test
    public void theOperatorsTokenNeverReachesTheDevice() {
        Assert.assertFalse(SupportSessions.consoleMayRelay("auth", false));
        Assert.assertFalse(SupportSessions.consoleMayRelay(null, false));
    }

    @Test
    public void aConsoleSendsAudioAndFilesButNeverScreenFrames() {
        Assert.assertTrue(SupportSessions.consoleMayRelayBinary(SupportSessions.BIN_AUDIO));
        Assert.assertTrue(SupportSessions.consoleMayRelayBinary(SupportSessions.BIN_FILE));
        Assert.assertFalse(SupportSessions.consoleMayRelayBinary(SupportSessions.BIN_FRAME));
        Assert.assertFalse(SupportSessions.consoleMayRelayBinary((byte) 9));
    }

    @Test
    public void auditKeepsChatFilesCallsAndActions() {
        JsonNode chat = SupportSessions.parse("{\"t\":\"chat\",\"s\":\"hola\"}");
        Assert.assertArrayEquals(new String[] {"chat", "hola"}, SupportSessions.audit(SupportSessions.type(chat), chat));
        JsonNode file = SupportSessions.parse("{\"t\":\"file.put\",\"id\":1,\"name\":\"manual.pdf\",\"size\":2048}");
        Assert.assertArrayEquals(new String[] {"file", "manual.pdf (2048 bytes)"}, SupportSessions.audit("file.put", file));
        JsonNode call = SupportSessions.parse("{\"t\":\"call.state\",\"s\":\"active\"}");
        Assert.assertArrayEquals(new String[] {"call", "active"}, SupportSessions.audit("call.state", call));
        JsonNode action = SupportSessions.parse("{\"t\":\"action\",\"k\":\"reboot\"}");
        Assert.assertArrayEquals(new String[] {"action", "reboot"}, SupportSessions.audit("action", action));
        // Touches and frame acknowledgements leave no line (they are only counted).
        Assert.assertNull(SupportSessions.audit("tap", SupportSessions.parse("{\"t\":\"tap\",\"x\":0.5,\"y\":0.5}")));
        Assert.assertNull(SupportSessions.audit("ack", SupportSessions.parse("{\"t\":\"ack\",\"seq\":3}")));
    }

    @Test
    public void clipboardTextIsNotWrittenToTheLog() {
        JsonNode clip = SupportSessions.parse("{\"t\":\"clip.set\",\"s\":\"clave-secreta\"}");
        String[] line = SupportSessions.audit("clip.set", clip);
        Assert.assertEquals("clipboard", line[0]);
        Assert.assertFalse(line[1], line[1].contains("clave-secreta"));
    }

    @Test
    public void malformedMessagesAreIgnored() {
        Assert.assertNull(SupportSessions.parse("not json"));
        Assert.assertNull(SupportSessions.parse("[1,2]"));
        Assert.assertNull(SupportSessions.type(SupportSessions.parse("{\"t\":5}")));
    }

    @Test
    public void wakeNamesTheSessionAndOldAgentsStillSync() {
        JsonNode wake = SupportSessions.parse(SupportSessions.wakePayload("abc123abc123abc123abc123"));
        // An agent that predates the channel reads only "wake": any kind but "interactive" makes it check in.
        Assert.assertEquals("support", wake.get("wake").asText());
        Assert.assertEquals("abc123abc123abc123abc123", wake.get("sessionId").asText());
        Assert.assertEquals("abc123abc123abc123abc123",
                SupportSessions.parse(SupportSessions.commandPayload("abc123abc123abc123abc123")).get("sessionId").asText());
    }

    @Test
    public void readinessFromCapabilities() {
        String ready = "{\"remoteControl\":{\"tier\":\"control\",\"support\":{\"session\":true,\"screen\":\"ready\",\"control\":true,"
                + "\"voice\":\"ready\",\"capture\":\"a11y\"}}}";
        Assert.assertEquals("ready", SupportSessions.readiness(ready));
        Map<String, Object> s = SupportSessions.support(ready);
        Assert.assertEquals(Boolean.TRUE, s.get("session"));
        Assert.assertEquals("a11y", s.get("capture"));

        String pending = "{\"remoteControl\":{\"tier\":\"none\",\"support\":{\"session\":true,\"screen\":\"needsAuthorization\"}}}";
        Assert.assertEquals("needsAuthorization", SupportSessions.readiness(pending));
        String off = "{\"remoteControl\":{\"support\":{\"session\":true,\"screen\":\"disabled\"}}}";
        Assert.assertEquals("disabled", SupportSessions.readiness(off));
    }

    @Test
    public void olderAgentsHaveNoSessionChannel() {
        // 0.8.x - 0.9.8 report remoteControl without "support".
        String old = "{\"remoteControl\":{\"tier\":\"control\",\"transport\":[\"vnc-repeater-wss\"]}}";
        Assert.assertEquals("unsupported", SupportSessions.readiness(old));
        Assert.assertTrue(SupportSessions.support(old).isEmpty());
        Assert.assertTrue(SupportSessions.support(null).isEmpty());
        Assert.assertTrue(SupportSessions.support("garbage").isEmpty());
        Assert.assertFalse(AgentCapabilityTokens.flatten(old).contains(SupportSessions.CAPABILITY));
        String fresh = "{\"device\":[\"lock\",\"supportSession\"],\"remoteControl\":{\"tier\":\"none\"}}";
        Assert.assertTrue(AgentCapabilityTokens.flatten(fresh).contains(SupportSessions.CAPABILITY));
    }

    // --- policy ------------------------------------------------------------------------------------------------------

    @Test
    public void policyKeepsOnlyNonDefaultRemoteSupportSettings() {
        Assert.assertNull(DcPolicy.normalize("{\"remoteSupport\":{\"engine\":\"auto\",\"notice\":true,\"callAccept\":\"ask\"}}"));
        String stored = DcPolicy.normalize("{\"remoteSupport\":{\"engine\":\"BuiltIn\",\"notice\":false,\"callAccept\":\"auto\",\"setupPrompt\":false,\"x\":1}}");
        DcPolicy p = DcPolicy.parse(stored);
        Assert.assertEquals("builtin", p.getRemoteSupport().getEngine());
        Assert.assertEquals(Boolean.FALSE, p.getRemoteSupport().getNotice());
        Assert.assertEquals("auto", p.getRemoteSupport().getCallAccept());
        Assert.assertEquals(Boolean.FALSE, p.getRemoteSupport().getSetupPrompt());
        Assert.assertNull(DcPolicy.parse("{\"remoteSupport\":{\"engine\":\"telnet\"}}").getRemoteSupport());
    }

    @Test
    public void desiredConfigCarriesRemoteSupportOnlyWhenThePolicySetsIt() {
        Configuration plain = new Configuration();
        plain.setId(7);
        DesiredConfig before = DesiredConfigBuilder.build(plain, Collections.emptyList());
        Assert.assertNull(before.getRemoteSupport());
        Assert.assertFalse(DesiredConfigBuilder.toPayloadJson(before).contains("remoteSupport"));

        Configuration with = new Configuration();
        with.setId(7);
        with.setDcPolicy("{\"remoteSupport\":{\"callAccept\":\"auto\"}}");
        DesiredConfig after = DesiredConfigBuilder.build(with, Collections.emptyList());
        Assert.assertEquals("auto", after.getRemoteSupport().getCallAccept());
        // A policy that does not mention remote support keeps the revision it had before this feature existed.
        Assert.assertNotEquals(before.getRevision(), after.getRevision());
        Configuration again = new Configuration();
        again.setId(7);
        Assert.assertEquals(before.getRevision(), DesiredConfigBuilder.build(again, Collections.emptyList()).getRevision());
    }
}
