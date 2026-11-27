/*
 *
 * DallyControl — the "before / after" of an audited change: only the fields that changed, with short values, and
 * never a secret (a changed password is reported as "modificado", without either value).
 *
 */

package com.hmdm.audit;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class AuditDiff {
    private AuditDiff() {}

    static final int MAX_VALUE = 160;
    static final int MAX_FIELDS = 40;

    /** Columns that change by themselves (timestamps, what the device reports, counters): not a user's change. */
    private static final Set<String> NOISE = new HashSet<>(Arrays.asList(
            "lastupdate", "updatedat", "info", "infojson", "fastsearch", "publicip", "lastloginfail", "imeiupdatets",
            "agentcapabilities", "uses", "lastrunat", "nextrunat", "laststatus", "lasterror", "enrolltime",
            "simphone", "simphoneat", "simmismatch", "oldnumber", "customerid", "id"));

    /** What the console calls the most common columns. Anything else keeps its column name. */
    private static final Map<String, String> LABELS = new HashMap<>();
    static {
        LABELS.put("name", "nombre");
        LABELS.put("description", "descripción");
        LABELS.put("login", "usuario");
        LABELS.put("email", "correo");
        LABELS.put("number", "número");
        LABELS.put("configurationid", "política");
        LABELS.put("parentid", "carpeta superior");
        LABELS.put("userroleid", "rol");
        LABELS.put("alldevicesavailable", "acceso a toda la flota");
        LABELS.put("allconfigavailable", "acceso a todas las políticas");
        LABELS.put("password", "contraseña");
        LABELS.put("passwordreset", "debe cambiar la contraseña");
        LABELS.put("authtoken", "sesiones (cierre en otros equipos)");
        LABELS.put("passwordresettoken", "enlace de cambio de contraseña");
        LABELS.put("account_email", "cuenta de correo");
        LABELS.put("configurationpinned", "política fijada al dispositivo");
        LABELS.put("active", "activo");
        LABELS.put("emails", "correos");
        LABELS.put("revoked", "revocado");
        LABELS.put("label", "etiqueta");
        LABELS.put("title", "título");
        LABELS.put("body", "contenido");
        LABELS.put("imei", "IMEI");
        LABELS.put("phone", "teléfono");
        LABELS.put("expectedphone", "teléfono esperado");
        LABELS.put("groupid", "carpeta");
        LABELS.put("carpetas", "carpetas");
        LABELS.put("permisos", "permisos");
    }

    /** Snapshot fields that hold a comma-separated list. */
    private static final Set<String> LISTS = new HashSet<>(Arrays.asList("permisos", "carpetas"));

    private static Set<String> items(String list) {
        Set<String> out = new LinkedHashSet<>();
        if (list != null) for (String s : list.split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    static String label(String column) {
        String l = LABELS.get(column);
        return l != null ? l : column;
    }

    /** { campo: { antes, despues } } for every field that differs; a secret only says that it changed. */
    public static ObjectNode changes(Map<String, String> before, Map<String, String> after) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        int n = 0;
        for (String k : keys) {
            if (NOISE.contains(k)) continue;
            String a = before.get(k);
            String b = after.get(k);
            if (a == null ? b == null : a.equals(b)) continue;
            if (++n > MAX_FIELDS) {
                out.put("…", "más campos modificados");
                break;
            }
            if (AuditRedactor.isSecretName(k)) {
                out.put(label(k), "modificado");
                continue;
            }
            ObjectNode c = JsonNodeFactory.instance.objectNode();
            if (LISTS.contains(k)) {
                // A list (permissions, folders): what was added and what was removed says more than two long lists.
                Set<String> was = items(a);
                Set<String> is = items(b);
                Set<String> added = new LinkedHashSet<>(is);
                added.removeAll(was);
                Set<String> removed = new LinkedHashSet<>(was);
                removed.removeAll(is);
                if (!added.isEmpty()) c.put("agregado", cut(String.join(", ", added)));
                if (!removed.isEmpty()) c.put("quitado", cut(String.join(", ", removed)));
            }
            c.put("antes", cut(a));
            c.put("despues", cut(b));
            out.set(label(k), c);
        }
        return out;
    }

    /** What a deleted row was: its short, non-secret fields. */
    public static ObjectNode summary(Map<String, String> row) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        int n = 0;
        for (Map.Entry<String, String> f : row.entrySet()) {
            String k = f.getKey();
            String v = f.getValue();
            if (v == null || v.isEmpty() || NOISE.contains(k) || AuditRedactor.isSecretName(k)) continue;
            if (v.length() > 400) continue; // long documents (a policy's settings) do not describe the row
            if (++n > 16) break;
            out.put(label(k), cut(v));
        }
        return out;
    }

    private static String cut(String s) {
        if (s == null) return null;
        return s.length() <= MAX_VALUE ? s : s.substring(0, MAX_VALUE) + "… (" + s.length() + " caracteres)";
    }
}
