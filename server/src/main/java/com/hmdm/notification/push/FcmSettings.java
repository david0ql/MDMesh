package com.hmdm.notification.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * <p>The Firebase Cloud Messaging project of this installation, read from the environment (never from the
 * repository or the database):</p>
 * <ul>
 *     <li>{@code FCM_SERVICE_ACCOUNT_FILE} (path, e.g. a Docker secret) or {@code FCM_SERVICE_ACCOUNT_JSON} (the JSON
 *     itself, plain or base64): the service-account key the server signs its requests with. Secret.</li>
 *     <li>{@code FCM_GOOGLE_SERVICES_FILE} / {@code FCM_GOOGLE_SERVICES_JSON}: the project's
 *     {@code google-services.json}, from which the agents' public client values are taken; or, instead,
 *     {@code FCM_API_KEY} + {@code FCM_APP_ID} (+ {@code FCM_SENDER_ID}).</li>
 *     <li>{@code FCM_ENDPOINT} / {@code FCM_TOKEN_URI}: only for tests against a simulated service.</li>
 * </ul>
 * <p>Nothing set = push is off and the server behaves exactly as before.</p>
 */
public final class FcmSettings {

    public static final String DEFAULT_ENDPOINT = "https://fcm.googleapis.com";
    private static final ObjectMapper JSON = new ObjectMapper();

    public final String projectId;
    public final String clientEmail;
    public final String privateKeyPem;
    public final String tokenUri;
    public final String endpoint;
    public final String apiKey;
    public final String senderId;
    /** Firebase app id by Android package name; never null. */
    public final Map<String, String> appIds;
    /** Why what was provided cannot be used (shown in the console); null when fine or when nothing was provided. */
    public final String problem;

    private FcmSettings(String projectId, String clientEmail, String privateKeyPem, String tokenUri, String endpoint,
                        String apiKey, String senderId, Map<String, String> appIds, String problem) {
        this.projectId = projectId;
        this.clientEmail = clientEmail;
        this.privateKeyPem = privateKeyPem;
        this.tokenUri = tokenUri;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.senderId = senderId;
        this.appIds = appIds == null ? Collections.<String, String>emptyMap() : Collections.unmodifiableMap(appIds);
        this.problem = problem;
    }

    public static FcmSettings off(String problem) {
        return new FcmSettings(null, null, null, null, DEFAULT_ENDPOINT, null, null, null, problem);
    }

    /** The server can send: it has a usable service-account key. */
    public boolean canSend() {
        return projectId != null && clientEmail != null && privateKeyPem != null && tokenUri != null;
    }

    /** The agents can register: the public client values are known. */
    public boolean canRegister() {
        return projectId != null && apiKey != null && !appIds.isEmpty();
    }

    public boolean isConfigured() {
        return canSend() && canRegister();
    }

    public static FcmSettings fromEnvironment() {
        return from(System.getenv(), path -> {
            try {
                return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
        });
    }

    public static FcmSettings from(Map<String, String> env, Function<String, String> readFile) {
        String account = content(env, "FCM_SERVICE_ACCOUNT", readFile);
        String services = content(env, "FCM_GOOGLE_SERVICES", readFile);
        String apiKey = trim(env.get("FCM_API_KEY"));
        String appId = trim(env.get("FCM_APP_ID"));
        String senderId = trim(env.get("FCM_SENDER_ID"));
        boolean fileNamed = trim(env.get("FCM_SERVICE_ACCOUNT_FILE")) != null;
        if (account == null) {
            return off(fileNamed ? "No se pudo leer el archivo de la cuenta de servicio (FCM_SERVICE_ACCOUNT_FILE)." : null);
        }
        String projectId;
        String clientEmail;
        String privateKey;
        String tokenUri;
        try {
            JsonNode a = JSON.readTree(account);
            projectId = text(a, "project_id");
            clientEmail = text(a, "client_email");
            privateKey = text(a, "private_key");
            tokenUri = text(a, "token_uri");
        } catch (Exception e) {
            return off("La cuenta de servicio no es un JSON válido.");
        }
        if (projectId == null || clientEmail == null || privateKey == null) {
            return off("A la cuenta de servicio le falta project_id, client_email o private_key.");
        }
        if (tokenUri == null) tokenUri = "https://oauth2.googleapis.com/token";
        if (trim(env.get("FCM_TOKEN_URI")) != null) tokenUri = trim(env.get("FCM_TOKEN_URI"));
        String endpoint = trim(env.get("FCM_ENDPOINT")) != null ? trim(env.get("FCM_ENDPOINT")) : DEFAULT_ENDPOINT;
        endpoint = endpoint.replaceAll("/+$", "");

        Map<String, String> appIds = new LinkedHashMap<>();
        String problem = null;
        if (services != null) {
            try {
                JsonNode g = JSON.readTree(services);
                String number = text(g.path("project_info"), "project_number");
                if (senderId == null) senderId = number;
                for (JsonNode client : g.path("client")) {
                    JsonNode info = client.path("client_info");
                    String pkg = text(info.path("android_client_info"), "package_name");
                    String id = text(info, "mobilesdk_app_id");
                    if (pkg != null && id != null) appIds.put(pkg, id);
                    if (apiKey == null) {
                        for (JsonNode k : client.path("api_key")) {
                            if (text(k, "current_key") != null) {
                                apiKey = text(k, "current_key");
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                problem = "El google-services.json no es un JSON válido.";
            }
        }
        if (appId != null) {
            // FCM_APP_ID: one id for every agent package, or "paquete=id,paquete=id".
            for (String part : appId.split("[,\\s]+")) {
                if (part.isEmpty()) continue;
                int eq = part.indexOf('=');
                if (eq > 0) appIds.put(part.substring(0, eq), part.substring(eq + 1));
                else appIds.put("*", part);
            }
        }
        if (senderId == null) {
            // "1:1234567890:android:abcdef" — the second field is the project number (the sender id).
            for (String id : appIds.values()) {
                String[] f = id.split(":");
                if (f.length >= 2 && f[1].matches("\\d+")) {
                    senderId = f[1];
                    break;
                }
            }
        }
        if (problem == null && (apiKey == null || appIds.isEmpty())) {
            problem = "Falta el google-services.json del proyecto (o FCM_API_KEY y FCM_APP_ID): los equipos no pueden registrarse.";
        }
        return new FcmSettings(projectId, clientEmail, privateKey, tokenUri, endpoint, apiKey, senderId, appIds, problem);
    }

    /** {@code <prefix>_FILE} (a path) wins over {@code <prefix>_JSON} (inline, plain or base64). */
    private static String content(Map<String, String> env, String prefix, Function<String, String> readFile) {
        String file = trim(env.get(prefix + "_FILE"));
        if (file != null) {
            String c = trim(readFile.apply(file));
            if (c != null) return c;
        }
        String inline = trim(env.get(prefix + "_JSON"));
        if (inline == null) return null;
        if (inline.startsWith("{")) return inline;
        try {
            return new String(Base64.getMimeDecoder().decode(inline), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return inline;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : trim(v.asText());
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
