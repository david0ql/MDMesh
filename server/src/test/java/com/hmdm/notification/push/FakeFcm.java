package com.hmdm.notification.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A simulated Firebase Cloud Messaging service for tests: the OAuth token endpoint (it verifies the signed
 * service-account assertion) and the HTTP v1 {@code messages:send} endpoint. Answers can be scripted per call.
 */
final class FakeFcm implements AutoCloseable {

    static final class Sent {
        final String bearer;
        final JsonNode body;

        Sent(String bearer, JsonNode body) {
            this.bearer = bearer;
            this.body = body;
        }

        String token() {
            return body.path("message").path("token").asText();
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    final KeyPair keys;
    final HttpServer server;
    /** Every send request received, including the refused ones. */
    final List<Sent> sends = Collections.synchronizedList(new ArrayList<Sent>());
    final List<String> assertions = Collections.synchronizedList(new ArrayList<String>());
    /** Scripted answers to the next sends: {status, body[, Retry-After]}. Empty = 200. */
    final Deque<String[]> script = new ArrayDeque<>();
    volatile int tokenRequests;
    volatile int tokenStatus = 200;
    private int issued;

    FakeFcm() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keys = gen.generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", this::token);
        server.createContext("/v1/projects/demo-project/messages:send", this::send);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String serviceAccountJson() {
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        try {
            Map<String, String> m = new HashMap<>();
            m.put("type", "service_account");
            m.put("project_id", "demo-project");
            m.put("client_email", "wake@demo-project.iam.gserviceaccount.com");
            m.put("private_key", pem);
            m.put("token_uri", url() + "/token");
            return JSON.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    FcmSettings settings() {
        Map<String, String> env = new HashMap<>();
        env.put("FCM_SERVICE_ACCOUNT_JSON", serviceAccountJson());
        env.put("FCM_ENDPOINT", url());
        env.put("FCM_API_KEY", "AIza-test");
        env.put("FCM_APP_ID", "1:123456:android:abc");
        return FcmSettings.from(env, path -> null);
    }

    void answer(int status, String body) {
        synchronized (script) {
            script.add(new String[]{String.valueOf(status), body, null});
        }
    }

    void answer(int status, String body, String retryAfter) {
        synchronized (script) {
            script.add(new String[]{String.valueOf(status), body, retryAfter});
        }
    }

    private void token(HttpExchange ex) throws IOException {
        tokenRequests++;
        String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String assertion = null;
        for (String pair : form.split("&")) {
            if (pair.startsWith("assertion=")) assertion = URLDecoder.decode(pair.substring(10), "UTF-8");
        }
        assertions.add(assertion);
        if (tokenStatus != 200 || !verifies(assertion)) {
            reply(ex, tokenStatus != 200 ? tokenStatus : 400, "{\"error\":{\"status\":\"INVALID_GRANT\",\"message\":\"bad assertion\"}}", null);
            return;
        }
        String token;
        synchronized (this) {
            token = "access-" + (++issued);
        }
        reply(ex, 200, "{\"access_token\":\"" + token + "\",\"expires_in\":3600,\"token_type\":\"Bearer\"}", null);
    }

    private boolean verifies(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(keys.getPublic());
            s.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getUrlDecoder().decode(parts[2]));
        } catch (Exception e) {
            return false;
        }
    }

    static JsonNode claims(String jwt) throws IOException {
        return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
    }

    private void send(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        JsonNode body = JSON.readTree(ex.getRequestBody().readAllBytes());
        sends.add(new Sent(auth == null ? null : auth.replaceFirst("^Bearer ", ""), body));
        String[] next;
        synchronized (script) {
            next = script.poll();
        }
        if (next == null) {
            reply(ex, 200, "{\"name\":\"projects/demo-project/messages/1\"}", null);
        } else {
            reply(ex, Integer.parseInt(next[0]), next[1], next[2]);
        }
    }

    private static void reply(HttpExchange ex, int status, String body, String retryAfter) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        if (retryAfter != null) ex.getResponseHeaders().add("Retry-After", retryAfter);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
