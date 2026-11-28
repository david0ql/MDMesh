package com.hmdm.notification.push;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The FCM HTTP v1 client against a simulated service: what goes on the wire, and what is retried. */
public class FcmClientTest {

    private static final String UNREGISTERED = "{\"error\":{\"code\":404,\"message\":\"Requested entity was not found.\","
            + "\"status\":\"NOT_FOUND\",\"details\":[{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\","
            + "\"errorCode\":\"UNREGISTERED\"}]}}";
    private static final String UNAVAILABLE = "{\"error\":{\"code\":503,\"message\":\"The service is currently unavailable.\",\"status\":\"UNAVAILABLE\"}}";

    private FakeFcm fcm;
    private FcmClient client;
    private final List<Long> sleeps = new ArrayList<>();

    @Before
    public void start() throws Exception {
        fcm = new FakeFcm();
        client = new FcmClient(fcm.settings(), HttpClient.newHttpClient(), sleeps::add);
    }

    @After
    public void stop() {
        fcm.close();
    }

    @Test
    public void the_message_is_a_high_priority_signal_for_that_token_and_carries_no_command() {
        FcmClient.Result r = client.send("token-A", "commands", 120);
        assertTrue(r.error, r.ok);
        assertEquals(1, r.attempts);
        assertEquals(1, fcm.sends.size());
        JsonNode message = fcm.sends.get(0).body.path("message");
        assertEquals("token-A", message.path("token").asText());
        assertEquals("HIGH", message.path("android").path("priority").asText());
        assertEquals("120s", message.path("android").path("ttl").asText());
        assertEquals("wake", message.path("android").path("collapse_key").asText());
        assertEquals("commands", message.path("data").path("wake").asText());
        assertEquals("only the signal travels", 1, message.path("data").size());
        assertTrue("data-only: a notification block would be shown by Android instead of waking the agent",
                message.path("notification").isMissingNode());
    }

    @Test
    public void it_authenticates_with_a_signed_service_account_assertion_and_reuses_the_access_token() throws Exception {
        assertTrue(client.send("t1", "commands", 60).ok);
        assertTrue(client.send("t2", "commands", 60).ok);
        assertEquals("one token request for two sends", 1, fcm.tokenRequests);
        assertEquals("access-1", fcm.sends.get(0).bearer);
        assertEquals("access-1", fcm.sends.get(1).bearer);
        JsonNode claims = FakeFcm.claims(fcm.assertions.get(0));
        assertEquals("wake@demo-project.iam.gserviceaccount.com", claims.path("iss").asText());
        assertEquals(FcmClient.SCOPE, claims.path("scope").asText());
        assertEquals(fcm.url() + "/token", claims.path("aud").asText());
        assertEquals(3600, claims.path("exp").asLong() - claims.path("iat").asLong());
    }

    @Test
    public void a_temporary_failure_is_retried_with_a_growing_wait() {
        fcm.answer(503, UNAVAILABLE);
        fcm.answer(500, "oops");
        FcmClient.Result r = client.send("t", "commands", 60);
        assertTrue(r.ok);
        assertEquals(3, r.attempts);
        assertEquals(3, fcm.sends.size());
        assertEquals(java.util.Arrays.asList(500L, 1_500L), sleeps);
    }

    @Test
    public void too_many_requests_waits_what_the_service_asks() {
        fcm.answer(429, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}", "2");
        assertTrue(client.send("t", "commands", 60).ok);
        assertEquals(java.util.Collections.singletonList(2_000L), sleeps);
    }

    @Test
    public void it_gives_up_after_four_attempts_and_says_why() {
        for (int i = 0; i < 6; i++) fcm.answer(503, UNAVAILABLE);
        FcmClient.Result r = client.send("t", "commands", 60);
        assertFalse(r.ok);
        assertFalse(r.unregistered);
        assertEquals(FcmClient.MAX_ATTEMPTS, r.attempts);
        assertEquals(FcmClient.MAX_ATTEMPTS, fcm.sends.size());
        assertEquals(503, r.httpStatus);
        assertTrue(r.error, r.error.contains("UNAVAILABLE"));
    }

    @Test
    public void an_unknown_token_is_reported_as_unregistered_and_not_retried() {
        fcm.answer(404, UNREGISTERED);
        FcmClient.Result r = client.send("gone", "commands", 60);
        assertFalse(r.ok);
        assertTrue(r.unregistered);
        assertEquals(1, fcm.sends.size());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    public void a_refused_request_is_not_retried() {
        fcm.answer(400, "{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"message\":\"bad\"}}");
        FcmClient.Result r = client.send("t", "commands", 60);
        assertFalse(r.ok);
        assertFalse(r.unregistered);
        assertEquals(1, fcm.sends.size());
        assertEquals("HTTP 400 INVALID_ARGUMENT: bad", r.error);
    }

    @Test
    public void an_expired_access_token_is_renewed_once() {
        assertTrue(client.send("t", "commands", 60).ok);
        fcm.answer(401, "{\"error\":{\"status\":\"UNAUTHENTICATED\"}}");
        FcmClient.Result r = client.send("t", "commands", 60);
        assertTrue(r.ok);
        assertEquals(2, fcm.tokenRequests);
        assertEquals("access-2", fcm.sends.get(2).bearer);
        // Refused again with the fresh token: an answer, not a loop.
        fcm.answer(401, "{\"error\":{\"status\":\"UNAUTHENTICATED\"}}");
        fcm.answer(401, "{\"error\":{\"status\":\"UNAUTHENTICATED\"}}");
        assertFalse(client.send("t", "commands", 60).ok);
        assertEquals(5, fcm.sends.size());
    }

    @Test
    public void a_rejected_service_account_fails_without_reaching_the_send_endpoint() {
        fcm.tokenStatus = 400;
        FcmClient.Result r = client.send("t", "commands", 60);
        assertFalse(r.ok);
        assertTrue(r.error, r.error.contains("rechazó la cuenta de servicio"));
        assertTrue(fcm.sends.isEmpty());
    }

    @Test
    public void an_unreachable_service_is_retried_then_reported() {
        FcmSettings s = fcm.settings();
        fcm.close();
        FcmClient.Result r = new FcmClient(s, HttpClient.newHttpClient(), sleeps::add).send("t", "commands", 60);
        assertFalse(r.ok);
        assertEquals(FcmClient.MAX_ATTEMPTS, r.attempts);
        assertTrue(r.error, r.error.contains("sin conexión"));
    }

    @Test
    public void settings_come_from_the_environment_and_nothing_set_means_off() {
        assertFalse(FcmSettings.from(new HashMap<>(), p -> null).isConfigured());
        assertNull(FcmSettings.from(new HashMap<>(), p -> null).problem);

        FcmSettings s = fcm.settings();
        assertTrue(s.isConfigured());
        assertEquals("demo-project", s.projectId);
        assertEquals("123456", s.senderId);
        assertEquals("1:123456:android:abc", s.appIds.get("*"));

        // Service account alone: the server could send, but no agent can register — and the console is told.
        Map<String, String> env = new HashMap<>();
        env.put("FCM_SERVICE_ACCOUNT_JSON", java.util.Base64.getEncoder().encodeToString(fcm.serviceAccountJson().getBytes()));
        FcmSettings half = FcmSettings.from(env, p -> null);
        assertTrue(half.canSend());
        assertFalse(half.isConfigured());
        assertNotNull(half.problem);

        // The project's google-services.json gives the per-package app ids, the key and the sender id.
        env.put("FCM_GOOGLE_SERVICES_FILE", "/run/secrets/gs.json");
        String gs = "{\"project_info\":{\"project_number\":\"987\",\"project_id\":\"demo-project\"},\"client\":["
                + "{\"client_info\":{\"mobilesdk_app_id\":\"1:987:android:rel\",\"android_client_info\":{\"package_name\":\"com.dallycontrol.agent\"}},"
                + "\"api_key\":[{\"current_key\":\"AIza-gs\"}]},"
                + "{\"client_info\":{\"mobilesdk_app_id\":\"1:987:android:dbg\",\"android_client_info\":{\"package_name\":\"com.dallycontrol.agent.debug\"}},"
                + "\"api_key\":[{\"current_key\":\"AIza-gs\"}]}]}";
        FcmSettings full = FcmSettings.from(env, p -> "/run/secrets/gs.json".equals(p) ? gs : null);
        assertTrue(full.isConfigured());
        assertNull(full.problem);
        assertEquals("AIza-gs", full.apiKey);
        assertEquals("987", full.senderId);
        assertEquals("1:987:android:dbg", full.appIds.get("com.dallycontrol.agent.debug"));
        assertEquals("1:987:android:rel", full.appIds.get("com.dallycontrol.agent"));

        Map<String, String> broken = new HashMap<>();
        broken.put("FCM_SERVICE_ACCOUNT_JSON", "{not json");
        assertFalse(FcmSettings.from(broken, p -> null).canSend());
        assertNotNull(FcmSettings.from(broken, p -> null).problem);
    }
}
