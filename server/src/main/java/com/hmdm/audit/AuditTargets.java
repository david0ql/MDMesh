/*
 *
 * DallyControl — the kinds of things an audited action can be about, and where each one lives. The table names
 * here are the ONLY ones the audit ever reads a row from (AuditTrailMapper.rowJson takes its table from this list,
 * never from a request).
 *
 */

package com.hmdm.audit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AuditTargets {
    private AuditTargets() {}

    public static final class Type {
        public final String table;
        /** Columns (lower case, as Postgres returns them) that name the row, in order of preference. */
        public final String[] nameColumns;
        /** What the console calls it. */
        public final String label;

        Type(String table, String label, String... nameColumns) {
            this.table = table;
            this.label = label;
            this.nameColumns = nameColumns;
        }
    }

    private static final Map<String, Type> TYPES;
    static {
        Map<String, Type> m = new LinkedHashMap<>();
        m.put("device", new Type("devices", "Dispositivo", "description", "number"));
        m.put("deviceId", new Type("devices", "Dispositivo", "description", "number"));
        m.put("group", new Type("groups", "Carpeta", "name"));
        m.put("config", new Type("configurations", "Política", "name"));
        m.put("app", new Type("applications", "Aplicación", "name", "pkg"));
        m.put("appVersion", new Type("applicationVersions", "Versión de aplicación", "version"));
        m.put("appGroup", new Type("app_group", "Grupo de aplicaciones", "name"));
        m.put("user", new Type("users", "Usuario", "login", "name"));
        m.put("role", new Type("userRoles", "Rol", "name"));
        m.put("script", new Type("savedScript", "Script", "name"));
        m.put("zone", new Type("geofence", "Geocerca", "name"));
        m.put("recipient", new Type("alert_recipient", "Destinatarios de alertas", "emails"));
        m.put("schedule", new Type("report_schedule", "Reporte programado", "name"));
        m.put("announcement", new Type("announcement", "Anuncio", "title"));
        m.put("code", new Type("agentEnrollmentToken", "Código de inscripción", "label"));
        m.put("rollout", new Type("agentRollout", "Despliegue del agente", "targetversion"));
        m.put("customer", new Type("customers", "Organización", "name"));
        m.put("icon", new Type("icons", "Ícono", "name"));
        TYPES = Collections.unmodifiableMap(m);
    }

    public static Type of(String type) {
        return type == null ? null : TYPES.get(type);
    }

    public static Map<String, Type> all() {
        return TYPES;
    }

    /** The row's display name. */
    public static String name(String type, Map<String, String> row) {
        Type t = of(type);
        if (t == null || row == null) return null;
        for (String c : t.nameColumns) {
            String v = row.get(c);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }
}
