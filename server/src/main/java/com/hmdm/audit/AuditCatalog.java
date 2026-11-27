/*
 *
 * DallyControl — the audit catalog: ONE place that says, for every REST route that changes state or exposes
 * sensitive data, what the audit trail calls it (module, action, Spanish label) and what it acts on.
 *
 * The audit filter (AuditTrailFilter) records every state-changing request under /rest/private even when its route
 * is missing here (with a generic label), so nothing escapes; this catalog is what makes each row readable.
 * AuditCatalogCoverageTest reflects over every JAX-RS resource and FAILS when a reachable POST/PUT/DELETE route has
 * no entry here — a new mutating endpoint must be given a name (or be declared a read / a device call) to build.
 *
 * Routes are written exactly as the JAX-RS templates declare them ("/private/devices/{id}"), without "/rest".
 *
 */

package com.hmdm.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AuditCatalog {
    private AuditCatalog() {}

    /** Why a state-changing-looking route is, or is not, in the trail. */
    public enum Kind {
        /** Recorded with its own label. */
        AUDIT,
        /** A query that happens to use POST/PUT (search, autocomplete, validation): recorded only when denied. */
        READ,
        /** Called by the device agent with the device's own secret, not by a console user. */
        DEVICE,
        /** An interface preference of the user themself (hints shown), or one chunk of an upload already recorded. */
        MINOR
    }

    // --- modules -------------------------------------------------------------------------------------------------

    public static final String SESSION = "sesion";
    public static final String DEVICES = "dispositivos";
    public static final String COMMANDS = "comandos";
    public static final String REMOTE = "remoto";
    public static final String FLEET = "flota";
    public static final String POLICIES = "politicas";
    public static final String APPS = "aplicaciones";
    public static final String FILES = "archivos";
    public static final String ENROLL = "inscripcion";
    public static final String USERS = "usuarios";
    public static final String ROLES = "roles";
    public static final String SCRIPTS = "scripts";
    public static final String ANNOUNCEMENTS = "anuncios";
    public static final String GEOFENCES = "geocercas";
    public static final String REPORTS = "reportes";
    public static final String ALERTS = "alertas";
    public static final String SETTINGS = "ajustes";
    public static final String UPDATES = "actualizaciones";
    public static final String AUDIT = "auditoria";
    public static final String OTHER = "otros";

    /** Module key → what the console shows. Insertion order is the order of the filter. */
    public static final Map<String, String> CATEGORIES;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(SESSION, "Sesión y acceso");
        m.put(DEVICES, "Dispositivos");
        m.put(COMMANDS, "Comandos remotos");
        m.put(REMOTE, "Control remoto");
        m.put(FLEET, "Carpetas y flota");
        m.put(POLICIES, "Políticas");
        m.put(APPS, "Aplicaciones");
        m.put(FILES, "Archivos");
        m.put(ENROLL, "Inscripción");
        m.put(USERS, "Usuarios");
        m.put(ROLES, "Roles y permisos");
        m.put(SCRIPTS, "Scripts");
        m.put(ANNOUNCEMENTS, "Anuncios");
        m.put(GEOFENCES, "Geocercas");
        m.put(REPORTS, "Reportes y exportaciones");
        m.put(ALERTS, "Alertas");
        m.put(SETTINGS, "Ajustes");
        m.put(UPDATES, "Actualizaciones del agente");
        m.put(AUDIT, "Auditoría");
        m.put(OTHER, "Otros");
        CATEGORIES = Collections.unmodifiableMap(m);
    }

    // --- rules ---------------------------------------------------------------------------------------------------

    public static final class Rule {
        public final String method;
        public final String template;
        public final Kind kind;
        public final String category;
        public final String action;
        public final String label;
        final Pattern pattern;
        final List<String> params = new ArrayList<>();

        /** What the action is about: a type of AuditTargets plus where its id comes from ("path:id", "body:id"…). */
        public String targetType;
        public String targetSource;
        /** Compare the target's row before and after (edits, deletes). */
        public boolean snapshot;
        /** The label and action key depend on the agent command in the body ({type} or {command:{type}}). */
        public boolean command;
        /** Label when the request is refused (sign-in). */
        public String failLabel;
        /** A read that exposes data (export, download, impersonation): recorded although it is a GET. */
        public boolean sensitiveRead;
        /** Why a READ / DEVICE / MINOR route is not recorded. */
        public String reason;

        Rule(String method, String template, Kind kind, String category, String action, String label) {
            this.method = method;
            this.template = template;
            this.kind = kind;
            this.category = category;
            this.action = action;
            this.label = label;
            StringBuilder re = new StringBuilder("^");
            Matcher m = Pattern.compile("\\{([^}:]+)(?::[^}]*)?}").matcher(template);
            int last = 0;
            while (m.find()) {
                re.append(Pattern.quote(template.substring(last, m.start()))).append("([^/]+)");
                params.add(m.group(1));
                last = m.end();
            }
            re.append(Pattern.quote(template.substring(last))).append("/?$");
            this.pattern = Pattern.compile(re.toString());
        }

        Rule target(String type, String source) {
            this.targetType = type;
            this.targetSource = source;
            return this;
        }

        Rule snapshot() {
            this.snapshot = true;
            return this;
        }

        Rule command() {
            this.command = true;
            return this;
        }

        Rule onFailure(String label) {
            this.failLabel = label;
            return this;
        }

        /** The values of the route's {placeholders} in this request path, or null when the path is another route. */
        public Map<String, String> match(String path) {
            Matcher m = pattern.matcher(path);
            if (!m.matches()) return null;
            Map<String, String> out = new LinkedHashMap<>();
            for (int i = 0; i < params.size(); i++) out.put(params.get(i), m.group(i + 1));
            return out;
        }

        public String key() {
            return method + " " + template;
        }
    }

    public static final class Match {
        public final Rule rule;
        public final Map<String, String> params;

        Match(Rule rule, Map<String, String> params) {
            this.rule = rule;
            this.params = params;
        }
    }

    private static final List<Rule> RULES = new ArrayList<>();

    private static Rule audit(String method, String template, String category, String action, String label) {
        Rule r = new Rule(method, template, Kind.AUDIT, category, action, label);
        RULES.add(r);
        return r;
    }

    /** A GET that exposes data (export, download, entering as another user). */
    private static Rule sensitive(String template, String category, String action, String label) {
        Rule r = audit("GET", template, category, action, label);
        r.sensitiveRead = true;
        return r;
    }

    private static void skip(Kind kind, String method, String template, String reason) {
        Rule r = new Rule(method, template, kind, OTHER, "skip", reason);
        r.reason = reason;
        RULES.add(r);
    }

    static {
        // --- sesión y acceso ------------------------------------------------------------------------------------
        audit("POST", "/public/auth/login", SESSION, "session.login", "Inicio de sesión")
                .onFailure("Inicio de sesión fallido");
        audit("POST", "/public/auth/logout", SESSION, "session.logout", "Cierre de sesión");
        audit("POST", "/public/passwordReset/reset", SESSION, "session.password.reset",
                "Cambio de contraseña (primer ingreso o enlace de recuperación)");
        sensitive("/public/passwordReset/recover/{username}", SESSION, "session.password.recover",
                "Solicitud de recuperación de contraseña");
        audit("PUT", "/private/users/current", SESSION, "session.password.change", "Cambio de contraseña propia");
        audit("PUT", "/private/users/details", SESSION, "session.profile", "Actualización de datos propios");
        audit("PUT", "/private/users/superadmin/password", SESSION, "session.password.superadmin",
                "Cambio de contraseña de un usuario por el superadministrador").target("user", "body:id");
        sensitive("/private/users/impersonate/{id}", SESSION, "session.impersonate", "Ingreso como otro usuario")
                .target("user", "path:id");
        sensitive("/private/customers/impersonate/{id}", SESSION, "session.impersonate.customer",
                "Ingreso como otra organización").target("customer", "path:id");

        // --- dispositivos ---------------------------------------------------------------------------------------
        audit("PUT", "/private/devices", DEVICES, "device.save", "Alta o edición de dispositivo")
                .target("deviceId", "body:id").snapshot();
        audit("DELETE", "/private/devices/{id}", DEVICES, "device.delete", "Eliminación de dispositivo")
                .target("deviceId", "path:id").snapshot();
        audit("POST", "/private/devices/deleteBulk", DEVICES, "device.delete.bulk", "Eliminación masiva de dispositivos")
                .target("devices", "body:ids");
        audit("POST", "/private/devices/groupBulk", DEVICES, "device.group.bulk", "Cambio masivo de carpeta de dispositivos")
                .target("devices", "body:ids");
        audit("POST", "/private/devices/{id}/description", DEVICES, "device.description", "Edición de la descripción del dispositivo")
                .target("deviceId", "path:id").snapshot();
        audit("POST", "/private/devices/{id}/applicationSettings", DEVICES, "device.appsettings",
                "Ajustes de aplicación del dispositivo").target("deviceId", "path:id");
        audit("POST", "/private/devices/{id}/applicationSettings/notify", DEVICES, "device.appsettings.notify",
                "Envío de ajustes de aplicación al dispositivo").target("deviceId", "path:id");
        skip(Kind.READ, "POST", "/private/devices/search", "Listado de dispositivos (consulta)");
        skip(Kind.READ, "POST", "/private/devices/autocomplete", "Autocompletar dispositivos (consulta)");

        audit("POST", "/private/agent/v1/devices/{deviceId}/commands", COMMANDS, "device.command", "Comando al dispositivo")
                .target("device", "path:deviceId").command();
        audit("POST", "/private/agent/v1/bulk/commands", COMMANDS, "device.command.bulk", "Comando masivo")
                .target("devices", "body:deviceIds").command();
        audit("POST", "/private/agent/v1/devices/{deviceId}/sync", COMMANDS, "device.sync", "Sincronización forzada del dispositivo")
                .target("device", "path:deviceId");
        audit("POST", "/private/agent/v1/devices/{deviceId}/syncApps", COMMANDS, "device.syncApps",
                "Actualización de las apps de la política en el dispositivo").target("device", "path:deviceId");
        audit("POST", "/private/agent/v1/devices/{deviceId}/config/reapply", COMMANDS, "device.config.reapply",
                "Reaplicación de la política al dispositivo").target("device", "path:deviceId");
        audit("POST", "/private/agent/v1/devices/{deviceId}/dataSharing", DEVICES, "device.dataSharing",
                "Cambio de uso compartido de datos del dispositivo").target("device", "path:deviceId");
        audit("POST", "/private/agent/v1/devices/{deviceId}/kioskApps", DEVICES, "device.kiosk.add",
                "App agregada al kiosko del dispositivo").target("device", "path:deviceId");
        audit("DELETE", "/private/agent/v1/devices/{deviceId}/kioskApps/{pkg}", DEVICES, "device.kiosk.remove",
                "App quitada del kiosko del dispositivo").target("device", "path:deviceId");
        audit("POST", "/private/agent/v1/devices/recover", DEVICES, "device.recover", "Recuperación de dispositivo eliminado")
                .target("device", "body:number");
        audit("DELETE", "/private/agent/v1/devices/deleted/{number}", DEVICES, "device.forget",
                "Descarte definitivo de dispositivo eliminado").target("device", "path:number");
        audit("POST", "/private/agent/v1/apps/install/retry", APPS, "app.install.retry", "Reintento de instalación de app en la flota")
                .target("devices", "body:deviceNumbers");

        // --- control remoto -------------------------------------------------------------------------------------
        audit("POST", "/private/agent/v1/devices/{deviceId}/remote/start", REMOTE, "remote.session.start",
                "Inicio de sesión de control remoto").target("device", "path:deviceId");
        audit("POST", "/private/agent/v1/devices/{deviceId}/remote/setup", REMOTE, "remote.setup",
                "Instalación del soporte de control remoto").target("device", "path:deviceId");

        // --- inscripción ----------------------------------------------------------------------------------------
        audit("POST", "/private/agent/v1/token", ENROLL, "enroll.token", "Generación de token de inscripción")
                .target("group", "body:groupId");
        audit("POST", "/private/agent/v1/codes", ENROLL, "enroll.code.create", "Creación de código de inscripción")
                .target("group", "body:groupId");
        audit("DELETE", "/private/agent/v1/codes/{id}", ENROLL, "enroll.code.revoke", "Revocación o borrado de código de inscripción")
                .target("code", "path:id").snapshot();

        // --- actualizaciones del agente -------------------------------------------------------------------------
        audit("POST", "/private/agent/v1/rollout", UPDATES, "rollout.create", "Despliegue de versión del agente");
        audit("POST", "/private/agent/v1/rollout/{id}/cancel", UPDATES, "rollout.cancel", "Cancelación de despliegue del agente")
                .target("rollout", "path:id");
        audit("POST", "/private/agent/v1/rollout/{id}/promote", UPDATES, "rollout.promote", "Promoción de despliegue del agente a toda la flota")
                .target("rollout", "path:id");

        // --- carpetas y flota -----------------------------------------------------------------------------------
        audit("POST", "/private/fleet/v1/groups", FLEET, "group.create", "Creación de carpeta").target("group", "body:parentId");
        audit("PUT", "/private/fleet/v1/groups/{id}", FLEET, "group.update", "Edición de carpeta (nombre, política o ubicación)")
                .target("group", "path:id").snapshot();
        audit("DELETE", "/private/fleet/v1/groups/{id}", FLEET, "group.delete", "Eliminación de carpeta")
                .target("group", "path:id").snapshot();
        audit("PUT", "/private/fleet/v1/groups/{id}/brand", FLEET, "group.brand", "Marca del kiosko de la carpeta")
                .target("group", "path:id");
        audit("PUT", "/private/fleet/v1/groups/{id}/consoleBrand", FLEET, "group.consoleBrand", "Marca de la consola de la carpeta")
                .target("group", "path:id");
        audit("PUT", "/private/fleet/v1/groups/{id}/accountEmail", FLEET, "group.accountEmail", "Cuenta de correo de la carpeta")
                .target("group", "path:id").snapshot();
        audit("POST", "/private/fleet/v1/groups/{id}/commands", COMMANDS, "group.command", "Comando a la carpeta")
                .target("group", "path:id").command();
        audit("POST", "/private/fleet/v1/global/commands", COMMANDS, "global.command", "Comando a toda la flota").command();
        audit("PUT", "/private/fleet/v1/global", FLEET, "global.policy", "Política global de la flota")
                .target("config", "body:configurationId");
        audit("POST", "/private/fleet/v1/devices/group", FLEET, "devices.move", "Dispositivos movidos de carpeta")
                .target("devices", "body:deviceIds");
        audit("PUT", "/private/fleet/v1/devices/configuration", FLEET, "devices.policy", "Política asignada a dispositivos")
                .target("devices", "body:deviceIds");
        audit("PUT", "/private/fleet/v1/devices/accountEmail", FLEET, "devices.accountEmail", "Cuenta de correo asignada a dispositivos")
                .target("devices", "body:deviceIds");
        audit("POST", "/private/fleet/v1/devices/refresh", COMMANDS, "devices.refresh", "Solicitud de reporte inmediato a dispositivos")
                .target("devices", "body:deviceIds");
        audit("POST", "/private/fleet/v1/syncApps", COMMANDS, "fleet.syncApps", "Actualización de las apps de la política en la flota")
                .target("devices", "body:deviceIds");
        audit("POST", "/private/fleet/v1/export", REPORTS, "export.devices", "Exportación de dispositivos")
                .target("devices", "body:numbers");
        sensitive("/private/fleet/v1/export.xlsx", REPORTS, "export.devices", "Exportación de dispositivos (Excel)")
                .target("group", "query:group");
        sensitive("/private/fleet/v1/export.csv", REPORTS, "export.devices", "Exportación de dispositivos (CSV)")
                .target("group", "query:group");
        audit("PUT", "/private/groups", FLEET, "group.legacy.save", "Alta o edición de grupo").target("group", "body:id").snapshot();
        audit("DELETE", "/private/groups/{id}", FLEET, "group.legacy.delete", "Eliminación de grupo").target("group", "path:id").snapshot();
        skip(Kind.READ, "POST", "/private/groups/autocomplete", "Autocompletar grupos (consulta)");

        // --- políticas ------------------------------------------------------------------------------------------
        audit("PUT", "/private/configurations", POLICIES, "config.save", "Alta o edición de política")
                .target("config", "body:id").snapshot();
        audit("PUT", "/private/configurations/copy", POLICIES, "config.copy", "Duplicado de política").target("config", "body:id");
        audit("PUT", "/private/configurations/application/upgrade", POLICIES, "config.app.upgrade",
                "Actualización de versión de app en la política").target("config", "body:configurationId");
        audit("DELETE", "/private/configurations/{id}", POLICIES, "config.delete", "Eliminación de política")
                .target("config", "path:id").snapshot();
        skip(Kind.READ, "POST", "/private/configurations/autocomplete", "Autocompletar políticas (consulta)");
        audit("POST", "/private/config-files", POLICIES, "config.file.upload", "Carga de archivo de política");
        audit("PUT", "/private/dc/versions/policy/{configurationId}/downgrade/{applicationId}", POLICIES, "config.app.downgrade",
                "Permiso de versión anterior de una app en la política").target("config", "path:configurationId");
        audit("PUT", "/private/dc/versions/{versionId}/label", APPS, "app.version.label", "Etiqueta de versión de app")
                .target("appVersion", "path:versionId");
        audit("PUT", "/private/dc/versions/{versionId}/policies", APPS, "app.version.assign", "Versión de app asignada a políticas")
                .target("appVersion", "path:versionId");

        // --- aplicaciones ---------------------------------------------------------------------------------------
        audit("PUT", "/private/applications/android", APPS, "app.save", "Alta o edición de aplicación")
                .target("app", "body:id").snapshot();
        audit("PUT", "/private/applications/web", APPS, "app.web.save", "Alta o edición de aplicación web")
                .target("app", "body:id").snapshot();
        audit("PUT", "/private/applications/versions", APPS, "app.version.save", "Alta o edición de versión de aplicación")
                .target("app", "body:applicationId");
        skip(Kind.READ, "PUT", "/private/applications/validatePkg", "Validación de paquete (consulta)");
        skip(Kind.READ, "POST", "/private/applications/autocomplete", "Autocompletar aplicaciones (consulta)");
        audit("POST", "/private/applications/configurations", APPS, "app.configs", "Aplicación asignada a políticas")
                .target("app", "body:applicationId");
        audit("POST", "/private/applications/version/configurations", APPS, "app.version.configs",
                "Versión de aplicación asignada a políticas").target("appVersion", "body:applicationVersionId");
        audit("POST", "/private/applications/{appId}/remove-from-my-configs", APPS, "app.unlink",
                "Aplicación quitada de las políticas propias").target("app", "path:appId");
        audit("DELETE", "/private/applications/versions/{id}", APPS, "app.version.delete", "Eliminación de versión de aplicación")
                .target("appVersion", "path:id").snapshot();
        audit("DELETE", "/private/applications/{id}", APPS, "app.delete", "Eliminación de aplicación")
                .target("app", "path:id").snapshot();
        sensitive("/private/applications/admin/common/{id}", APPS, "app.common", "Aplicación convertida en común")
                .target("app", "path:id");
        audit("POST", "/private/app-groups", APPS, "appgroup.create", "Creación de grupo de aplicaciones");
        audit("PUT", "/private/app-groups/{id}", APPS, "appgroup.update", "Edición de grupo de aplicaciones")
                .target("appGroup", "path:id").snapshot();
        audit("DELETE", "/private/app-groups/{id}", APPS, "appgroup.delete", "Eliminación de grupo de aplicaciones")
                .target("appGroup", "path:id").snapshot();

        // --- archivos -------------------------------------------------------------------------------------------
        audit("POST", "/private/web-ui-files", FILES, "file.upload", "Carga de archivo o APK");
        audit("POST", "/private/web-ui-files/raw", FILES, "file.upload", "Carga de archivo o APK");
        audit("POST", "/private/web-ui-files/bundle", FILES, "file.upload.bundle", "Carga de paquete de APK (bundle)");
        audit("POST", "/private/web-ui-files/chunked", FILES, "file.upload.start", "Inicio de carga de archivo por partes");
        audit("POST", "/private/web-ui-files/chunked/{id}/complete", FILES, "file.upload", "Carga de archivo o APK (por partes)");
        skip(Kind.MINOR, "POST", "/private/web-ui-files/chunked/{id}/{index}", "Parte de una carga ya registrada al iniciar y al terminar");
        skip(Kind.MINOR, "DELETE", "/private/web-ui-files/chunked/{id}", "Carga por partes cancelada antes de terminar (no se guardó nada)");
        audit("POST", "/private/web-ui-files/fetch", FILES, "file.fetch", "Descarga de APK desde una URL");
        audit("POST", "/private/web-ui-files/update", FILES, "file.update", "Edición de archivo");
        audit("POST", "/private/web-ui-files/remove", FILES, "file.delete", "Eliminación de archivo");
        audit("POST", "/private/web-ui-files/configurations", FILES, "file.configs", "Archivo asignado a políticas");
        audit("POST", "/private/icon-files", FILES, "icon.upload", "Carga de ícono");
        audit("PUT", "/private/icons", FILES, "icon.save", "Alta de ícono");
        audit("DELETE", "/private/icons/{id}", FILES, "icon.delete", "Eliminación de ícono").target("icon", "path:id");

        // --- usuarios y roles -----------------------------------------------------------------------------------
        audit("PUT", "/private/dc/users", USERS, "user.save", "Alta o edición de usuario").target("user", "body:id").snapshot();
        audit("DELETE", "/private/dc/users/{id}", USERS, "user.delete", "Eliminación de usuario").target("user", "path:id").snapshot();
        audit("PUT", "/private/users", USERS, "user.save", "Alta o edición de usuario").target("user", "body:id").snapshot();
        audit("DELETE", "/private/users/other/{id}", USERS, "user.delete", "Eliminación de usuario").target("user", "path:id").snapshot();
        audit("PUT", "/private/roles", ROLES, "role.save", "Alta o edición de rol y permisos").target("role", "body:id").snapshot();
        audit("DELETE", "/private/roles/{id}", ROLES, "role.delete", "Eliminación de rol").target("role", "path:id").snapshot();
        audit("PUT", "/private/customers", SETTINGS, "customer.save", "Alta o edición de organización").target("customer", "body:id");
        audit("DELETE", "/private/customers/{id}", SETTINGS, "customer.delete", "Eliminación de organización").target("customer", "path:id");
        skip(Kind.READ, "POST", "/private/customers/search", "Listado de organizaciones (consulta)");

        // --- scripts --------------------------------------------------------------------------------------------
        audit("PUT", "/private/scripts", SCRIPTS, "script.save", "Alta o edición de script").target("script", "body:id").snapshot();
        audit("DELETE", "/private/scripts/{id}", SCRIPTS, "script.delete", "Eliminación de script").target("script", "path:id").snapshot();
        audit("POST", "/private/scripts/{id}/run", SCRIPTS, "script.run", "Ejecución de script en dispositivos")
                .target("script", "path:id");

        // --- anuncios -------------------------------------------------------------------------------------------
        audit("POST", "/private/announcements", ANNOUNCEMENTS, "announcement.send", "Envío de anuncio");
        audit("POST", "/private/announcements/{id}/withdraw", ANNOUNCEMENTS, "announcement.withdraw", "Retiro de anuncio")
                .target("announcement", "path:id");
        audit("POST", "/private/push", ANNOUNCEMENTS, "push.send", "Envío de mensaje push por la API");

        // --- geocercas ------------------------------------------------------------------------------------------
        audit("POST", "/private/geofences/v1/zones", GEOFENCES, "zone.create", "Creación de geocerca");
        audit("PUT", "/private/geofences/v1/zones/{id}", GEOFENCES, "zone.update", "Edición de geocerca").target("zone", "path:id").snapshot();
        audit("DELETE", "/private/geofences/v1/zones/{id}", GEOFENCES, "zone.delete", "Eliminación de geocerca").target("zone", "path:id").snapshot();
        audit("POST", "/private/geofences/v1/recipients", GEOFENCES, "recipient.create", "Alta de destinatarios de alertas");
        audit("PUT", "/private/geofences/v1/recipients/{id}", GEOFENCES, "recipient.update", "Edición de destinatarios de alertas")
                .target("recipient", "path:id").snapshot();
        audit("DELETE", "/private/geofences/v1/recipients/{id}", GEOFENCES, "recipient.delete", "Eliminación de destinatarios de alertas")
                .target("recipient", "path:id").snapshot();
        audit("PUT", "/private/geofences/v1/settings", GEOFENCES, "geofence.settings", "Ajustes de geocercas e historial de ubicación");
        audit("POST", "/private/geofences/v1/mail/test", GEOFENCES, "geofence.mail.test", "Correo de prueba de alertas");
        sensitive("/private/geofences/v1/devices/{number}/track/export", REPORTS, "export.track", "Exportación del recorrido del dispositivo")
                .target("device", "path:number");

        // --- reportes y alertas ---------------------------------------------------------------------------------
        audit("POST", "/private/reports/v1/schedules", REPORTS, "schedule.create", "Creación de reporte programado");
        audit("PUT", "/private/reports/v1/schedules/{id}", REPORTS, "schedule.update", "Edición de reporte programado")
                .target("schedule", "path:id").snapshot();
        audit("DELETE", "/private/reports/v1/schedules/{id}", REPORTS, "schedule.delete", "Eliminación de reporte programado")
                .target("schedule", "path:id").snapshot();
        audit("POST", "/private/reports/v1/schedules/{id}/send", REPORTS, "schedule.send", "Envío inmediato de reporte programado")
                .target("schedule", "path:id");
        sensitive("/private/reports/v1/export", REPORTS, "export.report", "Exportación de reporte");
        sensitive("/private/reports/v1/inventory/export", REPORTS, "export.inventory", "Exportación del inventario");
        sensitive("/private/alerts/v1/export", REPORTS, "export.alerts", "Exportación de alertas");
        audit("POST", "/private/alerts/v1/review", ALERTS, "alert.review", "Alerta marcada como revisada");

        // --- ajustes --------------------------------------------------------------------------------------------
        audit("POST", "/private/settings/design", SETTINGS, "settings.design", "Ajustes de diseño");
        audit("POST", "/private/settings/lang", SETTINGS, "settings.lang", "Ajustes de idioma");
        audit("POST", "/private/settings/misc", SETTINGS, "settings.misc", "Ajustes generales");
        audit("POST", "/private/settings/userRoles/common", SETTINGS, "settings.columns", "Columnas visibles por rol");
        skip(Kind.MINOR, "POST", "/private/hints/disable", "Preferencia de interfaz del propio usuario");
        skip(Kind.MINOR, "POST", "/private/hints/enable", "Preferencia de interfaz del propio usuario");
        skip(Kind.MINOR, "POST", "/private/hints/history", "Preferencia de interfaz del propio usuario");

        // --- auditoría ------------------------------------------------------------------------------------------
        sensitive("/private/audit/v1/export", AUDIT, "audit.export", "Exportación de la auditoría");
        audit("PUT", "/private/audit/v1/settings", AUDIT, "audit.retention", "Cambio de la retención de la auditoría");

        // --- llamadas del agente del dispositivo (no son de un usuario de la consola) ----------------------------
        skip(Kind.DEVICE, "POST", "/public/agent/v1/enroll", "Inscripción del dispositivo (la hace el agente)");
        skip(Kind.DEVICE, "POST", "/public/agent/v1/checkin", "Reporte periódico del dispositivo");
    }

    /** Every rule, for the coverage test and the documentation of what is audited. */
    public static List<Rule> rules() {
        return Collections.unmodifiableList(RULES);
    }

    /** The rule of this request, or null. {@code path} is the path under /rest ("/private/devices/12"). */
    public static Match find(String method, String path) {
        if (method == null || path == null) return null;
        String m = method.toUpperCase(Locale.ROOT);
        for (Rule r : RULES) {
            if (!r.method.equals(m)) continue;
            Map<String, String> p = r.match(path);
            if (p != null) return new Match(r, p);
        }
        return null;
    }

    public static boolean isMutating(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method);
    }

    /** Fallback module of a route that is not in the catalog yet, from its first path segments. */
    public static String guessCategory(String path) {
        if (path == null) return OTHER;
        if (path.startsWith("/private/agent/v1/devices") || path.startsWith("/private/devices")) return DEVICES;
        if (path.startsWith("/private/fleet/")) return FLEET;
        if (path.startsWith("/private/configurations")) return POLICIES;
        if (path.startsWith("/private/applications") || path.startsWith("/private/app-groups")) return APPS;
        if (path.startsWith("/private/web-ui-files")) return FILES;
        if (path.startsWith("/private/geofences")) return GEOFENCES;
        if (path.startsWith("/private/reports")) return REPORTS;
        if (path.startsWith("/private/alerts")) return ALERTS;
        if (path.startsWith("/private/scripts")) return SCRIPTS;
        if (path.startsWith("/private/roles")) return ROLES;
        if (path.startsWith("/private/users") || path.startsWith("/private/dc/users")) return USERS;
        if (path.startsWith("/private/settings")) return SETTINGS;
        if (path.startsWith("/private/announcements")) return ANNOUNCEMENTS;
        return OTHER;
    }

    // --- agent commands ------------------------------------------------------------------------------------------

    private static final Map<String, String> COMMAND_LABELS = new LinkedHashMap<>();
    static {
        COMMAND_LABELS.put("device.wipe", "borrado de fábrica (wipe)");
        COMMAND_LABELS.put("device.lock", "bloqueo de pantalla");
        COMMAND_LABELS.put("device.reboot", "reinicio");
        COMMAND_LABELS.put("device.passcodereset", "cambio de clave de desbloqueo");
        COMMAND_LABELS.put("device.ring", "hacer sonar");
        COMMAND_LABELS.put("device.ringstop", "detener sonido");
        COMMAND_LABELS.put("device.alert", "alerta en pantalla");
        COMMAND_LABELS.put("device.announce", "anuncio");
        COMMAND_LABELS.put("device.lockscreenmessage", "mensaje en la pantalla de bloqueo");
        COMMAND_LABELS.put("device.display", "ajuste de pantalla");
        COMMAND_LABELS.put("device.locationmode", "modo de ubicación");
        COMMAND_LABELS.put("device.applaunch", "abrir aplicación");
        COMMAND_LABELS.put("device.openstore", "abrir tienda de aplicaciones");
        COMMAND_LABELS.put("device.systemupdate", "actualización del sistema");
        COMMAND_LABELS.put("device.storageaccess", "acceso al almacenamiento");
        COMMAND_LABELS.put("device.storagescan", "análisis de almacenamiento");
        COMMAND_LABELS.put("device.storageclean", "limpieza de almacenamiento");
        COMMAND_LABELS.put("device.clearcache", "borrado de caché");
        COMMAND_LABELS.put("device.powermode", "modo de energía");
        COMMAND_LABELS.put("device.silence", "silenciar");
        COMMAND_LABELS.put("device.diagnose", "diagnóstico");
        COMMAND_LABELS.put("device.datasharing", "uso compartido de datos");
        COMMAND_LABELS.put("device.notificationaction", "acción de notificación");
        COMMAND_LABELS.put("app.install", "instalación de aplicación");
        COMMAND_LABELS.put("app.silentinstall", "instalación silenciosa de aplicación");
        COMMAND_LABELS.put("app.uninstall", "desinstalación de aplicación");
        COMMAND_LABELS.put("app.cleardata", "borrado de datos de aplicación");
        COMMAND_LABELS.put("agent.update", "actualización del agente");
        COMMAND_LABELS.put("kiosk.enter", "entrar a modo kiosko");
        COMMAND_LABELS.put("kiosk.exit", "salir de modo kiosko");
        COMMAND_LABELS.put("policy.apply", "aplicar política");
        COMMAND_LABELS.put("policy.wifi", "configuración de Wi-Fi");
        COMMAND_LABELS.put("policy.camera", "política de cámara");
        COMMAND_LABELS.put("config.sync", "sincronizar configuración");
        COMMAND_LABELS.put("config.apply", "aplicar configuración");
        COMMAND_LABELS.put("remote.vnc.start", "inicio de sesión de control remoto");
        COMMAND_LABELS.put("remote.vnc.stop", "fin de sesión de control remoto");
        COMMAND_LABELS.put("remote.inputsetup", "habilitar control (entrada) remoto");
    }

    /** Spanish name of an agent command type; an unknown type is shown as it is. */
    public static String commandLabel(String type) {
        if (type == null || type.trim().isEmpty()) return "sin tipo";
        String l = COMMAND_LABELS.get(type.trim().toLowerCase(Locale.ROOT));
        return l != null ? l : type.trim();
    }

    /** Commands that interrupt the user or destroy data (the ones behind the disruptive_actions permission). */
    public static boolean isDisruptive(String type) {
        if (type == null) return false;
        String t = type.trim().toLowerCase(Locale.ROOT);
        return t.equals("device.wipe") || t.equals("device.lock") || t.equals("device.reboot") || t.equals("device.passcodereset");
    }
}
