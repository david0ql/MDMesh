package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * <p>The Wi-Fi part of {@link DcPolicy}: corporate networks (every security Android can save, with its per-network
 * options and enterprise parameters) and the lock-down rules.</p>
 * <ul>
 *     <li>{@link #clean} / {@link #cleanRules} — what is stored: anything invalid is dropped, never an exception;</li>
 *     <li>{@link #validate} — what a save refuses: a policy that would leave phones without a network, or an
 *     enterprise network that does not check the server's certificate;</li>
 *     <li>{@link #redact} / {@link #mergeSecrets} — Wi-Fi passwords never go back to the console: a saved one is
 *     answered as {@code passwordSet: true}, and a save that sends {@code passwordSet} without a password keeps the
 *     stored one.</li>
 * </ul>
 */
public final class DcWifi {
    public static final String NONE = "NONE", OWE = "OWE", WEP = "WEP", WPA = "WPA", WPA3 = "WPA3",
            WPA3_TRANSITION = "WPA3_TRANSITION", EAP = "EAP", EAP_WPA3 = "EAP_WPA3";
    /** Securities an agent older than the Wi-Fi rework can save (anything it does not know it treats as WPA-PSK). */
    private static final Set<String> LEGACY = new LinkedHashSet<String>(Arrays.asList(NONE, WEP, WPA, WPA3_TRANSITION));
    private static final Set<String> ALL = new LinkedHashSet<String>(Arrays.asList(NONE, OWE, WEP, WPA, WPA3, WPA3_TRANSITION, EAP, EAP_WPA3));
    private static final Set<String> EAP_METHODS = new LinkedHashSet<String>(Arrays.asList("PEAP", "TTLS", "TLS"));
    private static final Set<String> PHASE2 = new LinkedHashSet<String>(Arrays.asList("MSCHAPV2", "PAP", "GTC"));
    public static final int MAX_NETWORKS = 20;
    public static final int MAX_SSIDS = 200;
    public static final int DEFAULT_SAFEGUARD_MINUTES = 10;
    private static final Pattern IPV4 = Pattern.compile("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$");
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9*]([A-Za-z0-9._*-]*[A-Za-z0-9])?$");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private DcWifi() {}

    public static boolean isEnterprise(String security) {
        return EAP.equals(security) || EAP_WPA3.equals(security);
    }

    /** An agent that predates the Wi-Fi rework saves this security correctly. */
    public static boolean isLegacy(String security) {
        return LEGACY.contains(security);
    }

    /** Android's ranking (DevicePolicyManager.WIFI_SECURITY_*): 0 open, 1 personal, 2 enterprise. */
    public static int level(String security) {
        if (NONE.equals(security) || OWE.equals(security)) return 0;
        return isEnterprise(security) ? 2 : 1;
    }

    public static int levelOf(String minSecurity) {
        if ("personal".equals(minSecurity)) return 1;
        if ("enterprise".equals(minSecurity)) return 2;
        if ("enterprise192".equals(minSecurity)) return 3;
        return 0;
    }

    // ------------------------------------------------------------------------------------------------ cleaning

    /** The networks as stored: valid ones only, at most {@link #MAX_NETWORKS}, one per SSID; null when none. */
    public static List<DcPolicy.Wifi> clean(List<DcPolicy.Wifi> in) {
        if (in == null) return null;
        List<DcPolicy.Wifi> out = new ArrayList<DcPolicy.Wifi>();
        Set<String> seen = new LinkedHashSet<String>();
        Set<String> ids = new LinkedHashSet<String>();
        for (DcPolicy.Wifi w : in) {
            if (w == null || w.getSsid() == null) continue;
            String ssid = w.getSsid().trim();
            if (ssid.isEmpty() || ssid.getBytes(StandardCharsets.UTF_8).length > 32 || containsControl(ssid) || !seen.add(ssid)) continue;
            String sec = w.getSecurity() == null ? WPA : w.getSecurity().trim().toUpperCase(Locale.ROOT);
            if (!ALL.contains(sec)) sec = WPA;
            DcPolicy.Wifi n = new DcPolicy.Wifi();
            n.setSsid(ssid);
            n.setSecurity(sec);
            String id = w.getId() != null && ID.matcher(w.getId()).matches() ? w.getId() : derivedId(ssid);
            while (!ids.add(id)) id = derivedId(id + ssid);
            n.setId(id);
            boolean keyed = WEP.equals(sec) || WPA.equals(sec) || WPA3.equals(sec) || WPA3_TRANSITION.equals(sec);
            n.setPassword(keyed && w.getPassword() != null && !w.getPassword().isEmpty() && w.getPassword().length() <= 64
                    && !containsControl(w.getPassword()) ? w.getPassword() : null);
            n.setHidden(Boolean.TRUE.equals(w.getHidden()) ? Boolean.TRUE : null);
            n.setAutoConnect(Boolean.FALSE.equals(w.getAutoConnect()) ? Boolean.FALSE : null); // on unless turned off
            n.setPriority(w.getPriority() != null && w.getPriority() >= 0 && w.getPriority() <= 999 ? w.getPriority() : null);
            n.setProxy(proxy(w.getProxy()));
            n.setIp(ip(w.getIp()));
            String mac = lower(w.getMac());
            n.setMac("random".equals(mac) || "device".equals(mac) ? mac : null);
            String metered = lower(w.getMetered());
            n.setMetered("metered".equals(metered) || "unmetered".equals(metered) ? metered : null);
            n.setEap(isEnterprise(sec) ? eap(w.getEap()) : null);
            out.add(n);
            if (out.size() >= MAX_NETWORKS) break;
        }
        return out.isEmpty() ? null : out;
    }

    private static DcPolicy.WifiProxy proxy(DcPolicy.WifiProxy p) {
        if (p == null) return null;
        DcPolicy.WifiProxy n = new DcPolicy.WifiProxy();
        if ("pac".equals(lower(p.getMode()))) {
            String url = p.getPacUrl() == null ? "" : p.getPacUrl().trim();
            if (!(url.startsWith("http://") || url.startsWith("https://")) || url.length() > 1024 || containsControl(url) || url.contains(" ")) return null;
            n.setMode("pac");
            n.setPacUrl(url);
            return n;
        }
        String host = p.getHost() == null ? "" : p.getHost().trim();
        if (host.isEmpty() || host.length() > 255 || !HOST.matcher(host).matches() || host.contains("*")) return null;
        n.setMode("manual");
        n.setHost(host);
        n.setPort(p.getPort() != null && p.getPort() >= 1 && p.getPort() <= 65535 ? p.getPort() : Integer.valueOf(8080));
        if (p.getExclude() != null) {
            List<String> ex = new ArrayList<String>();
            for (String e : p.getExclude()) {
                String t = e == null ? "" : e.trim();
                if (!t.isEmpty() && t.length() <= 255 && HOST.matcher(t).matches() && !ex.contains(t) && ex.size() < 50) ex.add(t);
            }
            n.setExclude(ex.isEmpty() ? null : ex);
        }
        return n;
    }

    private static DcPolicy.WifiIp ip(DcPolicy.WifiIp p) {
        if (p == null || !isIpv4(p.getAddress())) return null;
        DcPolicy.WifiIp n = new DcPolicy.WifiIp();
        n.setAddress(p.getAddress().trim());
        n.setPrefix(p.getPrefix() != null && p.getPrefix() >= 1 && p.getPrefix() <= 32 ? p.getPrefix() : Integer.valueOf(24));
        n.setGateway(isIpv4(p.getGateway()) ? p.getGateway().trim() : null);
        if (p.getDns() != null) {
            List<String> dns = new ArrayList<String>();
            for (String d : p.getDns()) if (isIpv4(d) && !dns.contains(d.trim()) && dns.size() < 4) dns.add(d.trim());
            n.setDns(dns.isEmpty() ? null : dns);
        }
        return n;
    }

    private static DcPolicy.WifiEap eap(DcPolicy.WifiEap e) {
        DcPolicy.WifiEap n = new DcPolicy.WifiEap();
        String method = e == null || e.getMethod() == null ? "PEAP" : e.getMethod().trim().toUpperCase(Locale.ROOT);
        n.setMethod(EAP_METHODS.contains(method) ? method : "PEAP");
        if (e == null) return n;
        boolean tls = "TLS".equals(n.getMethod());
        String phase2 = e.getPhase2() == null ? "" : e.getPhase2().trim().toUpperCase(Locale.ROOT);
        n.setPhase2(!tls && PHASE2.contains(phase2) ? phase2 : (tls ? null : ("TTLS".equals(n.getMethod()) ? "PAP" : "MSCHAPV2")));
        n.setIdentity(text(e.getIdentity(), 255));
        n.setAnonymousIdentity(text(e.getAnonymousIdentity(), 255));
        n.setPassword(!tls && e.getPassword() != null && !e.getPassword().isEmpty() && e.getPassword().length() <= 255
                && !containsControl(e.getPassword()) ? e.getPassword() : null);
        n.setDomain(text(e.getDomain(), 255));
        n.setAltSubjectMatch(text(e.getAltSubjectMatch(), 1024));
        if (e.getCaCertIds() != null) {
            List<Integer> ids = new ArrayList<Integer>();
            for (Integer id : e.getCaCertIds()) if (id != null && id > 0 && !ids.contains(id) && ids.size() < 5) ids.add(id);
            n.setCaCertIds(ids.isEmpty() ? null : ids);
        }
        n.setClientCertId(tls && e.getClientCertId() != null && e.getClientCertId() > 0 ? e.getClientCertId() : null);
        return n;
    }

    /** The lock-down rules as stored; null when none is set. */
    public static DcPolicy.WifiRules cleanRules(DcPolicy.WifiRules r) {
        if (r == null) return null;
        DcPolicy.WifiRules n = new DcPolicy.WifiRules();
        n.setLockManaged(Boolean.TRUE.equals(r.getLockManaged()) ? Boolean.TRUE : null);
        n.setBlockAdd(Boolean.TRUE.equals(r.getBlockAdd()) ? Boolean.TRUE : null);
        n.setBlockConfig(Boolean.TRUE.equals(r.getBlockConfig()) ? Boolean.TRUE : null);
        n.setBlockState(Boolean.TRUE.equals(r.getBlockState()) ? Boolean.TRUE : null);
        String mode = lower(r.getSsidMode());
        if ("allow".equals(mode) || "deny".equals(mode)) {
            n.setSsidMode(mode);
            List<String> ssids = new ArrayList<String>();
            if (r.getSsids() != null) {
                for (String s : r.getSsids()) {
                    String t = s == null ? "" : s.trim();
                    if (!t.isEmpty() && t.getBytes(StandardCharsets.UTF_8).length <= 32 && !containsControl(t) && !ssids.contains(t)
                            && ssids.size() < MAX_SSIDS) ssids.add(t);
                }
            }
            n.setSsids(ssids.isEmpty() ? null : ssids);
        }
        String min = lower(r.getMinSecurity());
        n.setMinSecurity(levelOf(min) > 0 ? min : null);
        boolean any = n.getLockManaged() != null || n.getBlockAdd() != null || n.getBlockConfig() != null || n.getBlockState() != null
                || n.getSsidMode() != null || n.getMinSecurity() != null;
        if (!any) return null;
        n.setSafeguardMinutes(r.getSafeguardMinutes() != null && r.getSafeguardMinutes() >= 1 && r.getSafeguardMinutes() <= 120
                && r.getSafeguardMinutes() != DEFAULT_SAFEGUARD_MINUTES ? r.getSafeguardMinutes() : null);
        return n;
    }

    // ---------------------------------------------------------------------------------------------- validation

    /**
     * Why this policy must not be saved, as an error key ({@code error.configuration.wifi.*}); null when it is fine.
     * The checks are the ones that would strand a phone or make an enterprise network unsafe.
     *
     * @param radio the policy's Wi-Fi radio setting (false = forced off)
     */
    public static String validate(DcPolicy p, Boolean radio) {
        List<DcPolicy.Wifi> nets = p.getWifi() == null ? Collections.<DcPolicy.Wifi>emptyList() : p.getWifi();
        for (DcPolicy.Wifi w : nets) {
            String sec = w.getSecurity();
            boolean keyed = WEP.equals(sec) || WPA.equals(sec) || WPA3.equals(sec) || WPA3_TRANSITION.equals(sec);
            if (keyed) {
                String pw = w.getPassword();
                if (pw == null) return "error.configuration.wifi.password.missing";
                // WPA/WPA2: 8-63 characters, or the 64-hex key. WPA3 takes the passphrase only.
                if (!WEP.equals(sec) && (pw.length() < 8 || (pw.length() == 64 && (WPA3.equals(sec) || !pw.matches("^[0-9A-Fa-f]{64}$")))))
                    return "error.configuration.wifi.password.length";
            }
            if (isEnterprise(sec)) {
                DcPolicy.WifiEap e = w.getEap();
                if (e == null) return "error.configuration.wifi.eap.missing";
                // Without a CA and a name to match, any access point with the same SSID gets the credentials.
                if (e.getCaCertIds() == null) return "error.configuration.wifi.eap.ca";
                if (e.getDomain() == null && e.getAltSubjectMatch() == null) return "error.configuration.wifi.eap.domain";
                if ("TLS".equals(e.getMethod())) {
                    if (e.getClientCertId() == null) return "error.configuration.wifi.eap.clientcert";
                } else {
                    if (e.getIdentity() == null) return "error.configuration.wifi.eap.identity";
                    if (e.getPassword() == null) return "error.configuration.wifi.password.missing";
                }
            }
            if (w.getIp() != null && w.getIp().getGateway() == null) return "error.configuration.wifi.ip.gateway";
        }
        DcPolicy.WifiRules r = p.getWifiRules();
        if (Boolean.FALSE.equals(radio) && !nets.isEmpty()) return "error.configuration.wifi.radio.off";
        if (r == null) return null;
        if ("allow".equals(r.getSsidMode()) && nets.isEmpty() && r.getSsids() == null) return "error.configuration.wifi.allow.empty";
        if ("deny".equals(r.getSsidMode()) && r.getSsids() == null) return "error.configuration.wifi.deny.empty";
        if ("deny".equals(r.getSsidMode())) {
            for (DcPolicy.Wifi w : nets) if (r.getSsids().contains(w.getSsid())) return "error.configuration.wifi.deny.managed";
        }
        int min = levelOf(r.getMinSecurity());
        if (min > 0 && !nets.isEmpty()) {
            int best = 0;
            for (DcPolicy.Wifi w : nets) best = Math.max(best, level(w.getSecurity()));
            if (best < min) return "error.configuration.wifi.minsecurity";
        }
        // Nobody can add or change a network and the policy brings none: the only way out would be mobile data.
        if ((Boolean.TRUE.equals(r.getBlockAdd()) || Boolean.TRUE.equals(r.getBlockConfig())) && nets.isEmpty())
            return "error.configuration.wifi.block.nonetworks";
        return null;
    }

    // ------------------------------------------------------------------------------------------------- secrets

    /** The policy JSON as the console may see it: Wi-Fi passwords replaced by {@code passwordSet: true}. */
    public static String redact(String dcPolicyJson) {
        if (dcPolicyJson == null || dcPolicyJson.indexOf("\"wifi\"") < 0) return dcPolicyJson;
        try {
            JsonNode root = JSON.readTree(dcPolicyJson);
            JsonNode wifi = root.get("wifi");
            if (!(root instanceof ObjectNode) || wifi == null || !wifi.isArray()) return dcPolicyJson;
            for (JsonNode n : wifi) {
                if (!(n instanceof ObjectNode)) continue;
                hide((ObjectNode) n);
                if (n.get("eap") instanceof ObjectNode) hide((ObjectNode) n.get("eap"));
            }
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            // Unreadable JSON cannot be shown safely: better nothing than a password.
            return null;
        }
    }

    private static void hide(ObjectNode n) {
        JsonNode pw = n.remove("password");
        n.remove("passwordSet");
        if (pw != null && pw.isTextual() && !pw.asText().isEmpty()) n.put("passwordSet", true);
    }

    /**
     * What a save stores: the incoming policy JSON, where every network that says {@code passwordSet} without a
     * password gets the one already stored for it (same network {@code id}, else same SSID). {@code passwordSet}
     * itself is never stored.
     *
     * @param stored policies to take passwords from, the policy being replaced first
     */
    public static String mergeSecrets(String incomingJson, List<String> stored) {
        if (incomingJson == null || incomingJson.indexOf("\"wifi\"") < 0) return incomingJson;
        try {
            JsonNode root = JSON.readTree(incomingJson);
            JsonNode wifi = root.get("wifi");
            if (!(root instanceof ObjectNode) || wifi == null || !wifi.isArray()) return incomingJson;
            Map<String, JsonNode> byId = new HashMap<String, JsonNode>(), bySsid = new HashMap<String, JsonNode>();
            if (stored != null) {
                for (String s : stored) {
                    if (s == null || s.indexOf("\"wifi\"") < 0) continue;
                    JsonNode old = JSON.readTree(s).get("wifi");
                    if (old == null || !old.isArray()) continue;
                    for (JsonNode o : old) {
                        if (o.hasNonNull("id") && !byId.containsKey(o.get("id").asText())) byId.put(o.get("id").asText(), o);
                        if (o.hasNonNull("ssid") && !bySsid.containsKey(o.get("ssid").asText())) bySsid.put(o.get("ssid").asText(), o);
                    }
                }
            }
            for (JsonNode n : wifi) {
                if (!(n instanceof ObjectNode)) continue;
                JsonNode old = n.hasNonNull("id") ? byId.get(n.get("id").asText()) : null;
                if (old == null && n.hasNonNull("ssid")) old = bySsid.get(n.get("ssid").asText());
                keep((ObjectNode) n, old);
                if (n.get("eap") instanceof ObjectNode) keep((ObjectNode) n.get("eap"), old == null ? null : old.get("eap"));
            }
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            return incomingJson; // normalize() refuses it right after
        }
    }

    private static void keep(ObjectNode n, JsonNode old) {
        boolean keep = n.path("passwordSet").asBoolean(false);
        n.remove("passwordSet");
        JsonNode pw = n.get("password");
        if (pw != null && pw.isTextual() && !pw.asText().isEmpty()) return; // a new one was typed
        if (keep && old != null && old.hasNonNull("password")) n.put("password", old.get("password").asText());
    }

    /** Certificate ids the policy JSON refers to (to refuse deleting one in use). */
    public static Set<Integer> certificateIds(String dcPolicyJson) {
        Set<Integer> ids = new LinkedHashSet<Integer>();
        List<DcPolicy.Wifi> nets = DcPolicy.parse(dcPolicyJson).getWifi();
        if (nets == null) return ids;
        for (DcPolicy.Wifi w : nets) {
            if (w.getEap() == null) continue;
            if (w.getEap().getCaCertIds() != null) ids.addAll(w.getEap().getCaCertIds());
            if (w.getEap().getClientCertId() != null) ids.add(w.getEap().getClientCertId());
        }
        return ids;
    }

    /** Writes a policy JSON string through {@link #redact}; on {@code Configuration.dcPolicy}. */
    public static final class RedactingSerializer extends com.fasterxml.jackson.databind.JsonSerializer<String> {
        @Override
        public void serialize(String value, com.fasterxml.jackson.core.JsonGenerator gen,
                              com.fasterxml.jackson.databind.SerializerProvider serializers) throws java.io.IOException {
            String safe = redact(value);
            if (safe == null) gen.writeNull(); else gen.writeString(safe);
        }
    }

    /** The Spanish text the console shows for a {@link #validate} error key. */
    public static String message(String key) {
        switch (key == null ? "" : key) {
            case "error.configuration.wifi.password.missing":
                return "Wi‑Fi: falta la contraseña de una red (escríbela de nuevo si cambiaste el tipo de seguridad).";
            case "error.configuration.wifi.password.length":
                return "Wi‑Fi: la contraseña WPA debe tener entre 8 y 63 caracteres (o 64 hexadecimales en WPA/WPA2).";
            case "error.configuration.wifi.eap.missing":
                return "Wi‑Fi empresarial: faltan los datos de EAP de una red.";
            case "error.configuration.wifi.eap.ca":
                return "Wi‑Fi empresarial: elige el certificado de la CA del servidor. Sin él, cualquier punto de acceso con el mismo nombre recibiría las credenciales.";
            case "error.configuration.wifi.eap.domain":
                return "Wi‑Fi empresarial: indica el dominio del certificado del servidor (o la coincidencia de nombre alternativo).";
            case "error.configuration.wifi.eap.clientcert":
                return "Wi‑Fi empresarial: EAP‑TLS necesita un certificado de cliente (PKCS#12).";
            case "error.configuration.wifi.eap.identity":
                return "Wi‑Fi empresarial: falta la identidad (usuario) de una red.";
            case "error.configuration.wifi.ip.gateway":
                return "Wi‑Fi: una red con IP fija necesita la puerta de enlace.";
            case "error.configuration.wifi.radio.off":
                return "Wi‑Fi: la política fuerza el Wi‑Fi apagado y a la vez configura redes. Deja el radio sin administrar o encendido.";
            case "error.configuration.wifi.allow.empty":
                return "Wi‑Fi: «solo redes permitidas» sin ninguna red dejaría los equipos sin Wi‑Fi. Agrega una red gestionada o un SSID a la lista.";
            case "error.configuration.wifi.deny.empty":
                return "Wi‑Fi: la lista de redes bloqueadas está vacía.";
            case "error.configuration.wifi.deny.managed":
                return "Wi‑Fi: una red gestionada está en la lista de redes bloqueadas.";
            case "error.configuration.wifi.minsecurity":
                return "Wi‑Fi: ninguna red gestionada alcanza la seguridad mínima exigida; los equipos no podrían conectarse a ninguna.";
            case "error.configuration.wifi.block.nonetworks":
                return "Wi‑Fi: impides agregar o cambiar redes pero la política no trae ninguna. Agrega al menos una red gestionada.";
            default:
                return "La configuración de Wi‑Fi no es válida.";
        }
    }

    // ------------------------------------------------------------------------------------------------- helpers

    /** A stable id for a network that has none (policies saved before networks had ids). */
    static String derivedId(String ssid) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(ssid.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("n");
            for (int i = 0; i < 5; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean isIpv4(String s) {
        return s != null && IPV4.matcher(s.trim()).matches();
    }

    private static String text(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() || t.length() > max || containsControl(t) ? null : t;
    }

    private static boolean containsControl(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isISOControl(s.charAt(i))) return true;
        return false;
    }

    private static String lower(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }
}
