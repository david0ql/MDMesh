package com.hmdm.notification.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;

/**
 * <p>Minimal Firebase Cloud Messaging HTTP v1 client: signs a service-account assertion, trades it for an access
 * token (cached until shortly before it expires) and posts one high-priority, data-only message per call. The
 * message is a signal ({@code wake=commands}); it never carries a command, so reading it reveals nothing and the
 * device still authenticates its check-in with its own secret.</p>
 *
 * <p>{@link #send} blocks and retries what is worth retrying (network errors, 429, 5xx; one 401 with a fresh access
 * token). Callers run it off the request thread.</p>
 */
public class FcmClient {

    /** Result of one {@link #send}. */
    public static final class Result {
        public final boolean ok;
        /** The service says the token no longer exists: stop sending to it. */
        public final boolean unregistered;
        public final int httpStatus;
        public final String error;
        public final int attempts;

        Result(boolean ok, boolean unregistered, int httpStatus, String error, int attempts) {
            this.ok = ok;
            this.unregistered = unregistered;
            this.httpStatus = httpStatus;
            this.error = error;
            this.attempts = attempts;
        }
    }

    /** Waits between attempts; swapped in tests. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    static final int MAX_ATTEMPTS = 4;
    private static final long[] BACKOFF_MS = {500L, 1_500L, 4_000L};
    private static final long MAX_RETRY_AFTER_MS = 10_000L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FcmSettings settings;
    private final HttpClient http;
    private final Sleeper sleeper;

    private String accessToken;
    private long accessTokenUntil;

    public FcmClient(FcmSettings settings) {
        this(settings, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), Thread::sleep);
    }

    public FcmClient(FcmSettings settings, HttpClient http, Sleeper sleeper) {
        this.settings = settings;
        this.http = http;
        this.sleeper = sleeper;
    }

    /**
     * @param token  the device's registration token
     * @param kind   what the agent is asked to do; today always {@code commands}
     * @param ttlSec how long the service keeps the message for a device it cannot reach now
     */
    public Result send(String token, String kind, int ttlSec) {
        String body = message(token, kind, ttlSec);
        String error = null;
        int status = 0;
        boolean refreshed = false;
        int attempt = 0;
        while (attempt < MAX_ATTEMPTS) {
            attempt++;
            long retryAfter = -1;
            try {
                String bearer = accessToken();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(settings.endpoint + "/v1/projects/" + settings.projectId + "/messages:send"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + bearer)
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                status = response.statusCode();
                if (status / 100 == 2) {
                    return new Result(true, false, status, null, attempt);
                }
                error = describe(status, response.body());
                if (status == 404 || isUnregistered(response.body())) {
                    return new Result(false, true, status, error, attempt);
                }
                if (status == 401 && !refreshed) {
                    // The cached access token was revoked or expired early: one more try with a fresh one.
                    refreshed = true;
                    invalidateAccessToken();
                    attempt--;
                    continue;
                }
                if (status != 429 && status / 100 != 5) {
                    return new Result(false, false, status, error, attempt);
                }
                retryAfter = retryAfterMs(response.headers().firstValue("Retry-After").orElse(null));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(false, false, status, "interrumpido", attempt);
            } catch (IOException e) {
                error = "sin conexión con el servicio de mensajes: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " " + e.getMessage());
            } catch (Exception e) {
                // A key that cannot be parsed, a refused assertion: retrying will not help.
                return new Result(false, false, status, e.getMessage() == null ? e.toString() : e.getMessage(), attempt);
            }
            if (attempt < MAX_ATTEMPTS) {
                try {
                    sleeper.sleep(retryAfter >= 0 ? Math.min(retryAfter, MAX_RETRY_AFTER_MS) : BACKOFF_MS[attempt - 1]);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Result(false, false, status, error, attempt);
                }
            }
        }
        return new Result(false, false, status, error, attempt);
    }

    static String message(String token, String kind, int ttlSec) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode message = root.putObject("message");
        message.put("token", token);
        message.putObject("data").put("wake", kind);
        ObjectNode android = message.putObject("android");
        android.put("priority", "HIGH");
        android.put("ttl", ttlSec + "s");
        // Several wakes for a phone that is out of reach collapse into one when it comes back.
        android.put("collapse_key", "wake");
        return root.toString();
    }

    private static boolean isUnregistered(String body) {
        try {
            JsonNode error = JSON.readTree(body).path("error");
            for (JsonNode d : error.path("details")) {
                if ("UNREGISTERED".equals(d.path("errorCode").asText())) return true;
            }
            return "NOT_FOUND".equals(error.path("status").asText());
        } catch (Exception e) {
            return false;
        }
    }

    private static String describe(int status, String body) {
        String message = null;
        try {
            JsonNode error = JSON.readTree(body).path("error");
            message = error.path("message").asText(null);
            String code = error.path("status").asText(null);
            if (code != null) message = code + (message == null ? "" : ": " + message);
        } catch (Exception ignored) {
            // not JSON: the status alone
        }
        String text = "HTTP " + status + (message == null ? "" : " " + message);
        return text.length() > 380 ? text.substring(0, 380) : text;
    }

    private static long retryAfterMs(String header) {
        if (header == null) return -1;
        try {
            return Long.parseLong(header.trim()) * 1000L;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private synchronized void invalidateAccessToken() {
        accessToken = null;
        accessTokenUntil = 0;
    }

    private synchronized String accessToken() throws Exception {
        long now = System.currentTimeMillis();
        if (accessToken != null && now < accessTokenUntil) return accessToken;
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8")
                + "&assertion=" + URLEncoder.encode(assertion(now / 1000L), "UTF-8");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(settings.tokenUri))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 == 5) {
            throw new IOException("servicio de credenciales HTTP " + response.statusCode());
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Google rechazó la cuenta de servicio: " + describe(response.statusCode(), response.body()));
        }
        JsonNode json = JSON.readTree(response.body());
        String token = json.path("access_token").asText(null);
        if (token == null) throw new IllegalStateException("La respuesta de credenciales no trae access_token.");
        long expiresIn = json.path("expires_in").asLong(3600L);
        accessToken = token;
        accessTokenUntil = now + Math.max(30L, expiresIn - 60L) * 1000L;
        return token;
    }

    /** The signed service-account assertion (RFC 7523) traded for an access token. */
    String assertion(long nowSec) throws Exception {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        ObjectNode claims = JSON.createObjectNode();
        claims.put("iss", settings.clientEmail);
        claims.put("scope", SCOPE);
        claims.put("aud", settings.tokenUri);
        claims.put("iat", nowSec);
        claims.put("exp", nowSec + 3600L);
        String unsigned = b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
                + "." + b64.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey(settings.privateKeyPem));
        signature.update(unsigned.getBytes(StandardCharsets.UTF_8));
        return unsigned + "." + b64.encodeToString(signature.sign());
    }

    static PrivateKey privateKey(String pem) throws Exception {
        String base64 = pem.replace("\\n", "\n")
                .replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (Exception e) {
            throw new IllegalStateException("La clave privada de la cuenta de servicio no se puede leer.");
        }
    }
}
