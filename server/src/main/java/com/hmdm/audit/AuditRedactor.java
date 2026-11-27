/*
 *
 * DallyControl — what of a request body is kept in the audit trail: a short, readable summary with every secret
 * removed. Passwords, passcodes, tokens, keys and Wi-Fi keys never reach the table, whatever the route (the rule is
 * by field name, so a new endpoint is covered without touching this class); long texts and long lists are cut.
 *
 */

package com.hmdm.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

public final class AuditRedactor {
    private AuditRedactor() {}

    public static final String MASK = "•••";
    static final int MAX_TEXT = 200;
    static final int MAX_ITEMS = 25;
    static final int MAX_DEPTH = 6;
    public static final int MAX_DETAIL = 4000;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Field names whose value is a secret. */
    private static final Pattern SECRET = Pattern.compile(
            "(?i).*(pass(word|code|wd)?|pwd|secret|token|credential|psk|apikey|api_key|privatekey|private_key|"
                    + "signingkey|authorization|authdata|twofactor|otp)[a-z0-9_]*$|^(pin|key|hash|md5|sha256)$|.*(pin|hash)$");

    /** Field names that only look secret. */
    private static final Pattern NOT_SECRET = Pattern.compile(
            "(?i)^(passwordReset|passwordless|keyProperty|tokenCount|passcodeRequired|passwordQuality|passwordLength|"
                    + "passwordRequired|shipping|mapping|apkSha256|sha256)$");

    public static boolean isSecretName(String name) {
        if (name == null) return false;
        if (NOT_SECRET.matcher(name).matches()) return false;
        return SECRET.matcher(name).matches();
    }

    /** The JSON body without secrets, shortened; null when there is nothing worth keeping. */
    public static JsonNode sanitize(String body) {
        if (body == null) return null;
        String t = body.trim();
        if (t.isEmpty()) return null;
        if (t.charAt(0) != '{' && t.charAt(0) != '[') {
            // A bare text body (e.g. the device description).
            return TextNode.valueOf(cut(t.charAt(0) == '"' && t.length() > 1 && t.endsWith("\"") ? t.substring(1, t.length() - 1) : t));
        }
        try {
            return clean(JSON.readTree(t), 0);
        } catch (Exception e) {
            return null;
        }
    }

    public static JsonNode parse(String body) {
        if (body == null || body.trim().isEmpty()) return null;
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    static JsonNode clean(JsonNode n, int depth) {
        if (n == null || n.isNull()) return n;
        if (depth > MAX_DEPTH) return TextNode.valueOf("…");
        if (n.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            int count = 0;
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> f = it.next();
                if (f.getValue() == null || f.getValue().isNull()) continue;
                if (++count > 60) {
                    out.put("…", "más campos");
                    break;
                }
                if (isSecretName(f.getKey())) {
                    // An empty secret was not set; a non-empty one is only marked as present.
                    if (!(f.getValue().isTextual() && f.getValue().asText().isEmpty())) out.put(f.getKey(), MASK);
                } else {
                    out.set(f.getKey(), clean(f.getValue(), depth + 1));
                }
            }
            return out;
        }
        if (n.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            int i = 0;
            for (JsonNode c : n) {
                if (i++ >= MAX_ITEMS) {
                    out.add("… (" + n.size() + " en total)");
                    break;
                }
                out.add(clean(c, depth + 1));
            }
            return out;
        }
        if (n.isTextual()) {
            String s = n.asText();
            // A JSON document sent as text (an agent command's payload): clean it as JSON, so its secrets go too.
            String t = s.trim();
            if (t.length() > 1 && (t.charAt(0) == '{' || t.charAt(0) == '[')) {
                try {
                    return clean(JSON.readTree(t), depth + 1);
                } catch (Exception ignored) {
                    // not JSON: fall through to plain text
                }
            }
            return TextNode.valueOf(cut(s));
        }
        return n;
    }

    static String cut(String s) {
        if (s == null) return null;
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT) + "… (" + s.length() + " caracteres)";
    }

    /** Compact JSON text of a detail document, never longer than {@link #MAX_DETAIL}. */
    public static String write(JsonNode detail) {
        if (detail == null || detail.isNull() || (detail.isContainerNode() && detail.size() == 0)) return null;
        try {
            String s = JSON.writeValueAsString(detail);
            if (s.length() <= MAX_DETAIL) return s;
            // Too long to keep whole: keep it valid JSON by wrapping the cut text.
            // (the cut text is escaped again as a string, so it is shortened until the whole fits).
            for (int keep = MAX_DETAIL - 200; keep > 0; keep -= 400) {
                ObjectNode o = JsonNodeFactory.instance.objectNode();
                o.put("resumen", s.substring(0, keep) + "…");
                o.put("recortado", true);
                String out = JSON.writeValueAsString(o);
                if (out.length() <= MAX_DETAIL) return out;
            }
            return "{\"recortado\":true}";
        } catch (Exception e) {
            return null;
        }
    }
}
