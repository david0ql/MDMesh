package com.hmdm.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns what a phone answered to an {@code app.install} (Android's {@code FAILURE_*} / {@code INSTALL_FAILED_*} codes,
 * or the agent's own messages) into a reason an operator can read, in Spanish, and says whether trying again by
 * itself can help. The raw text is always kept next to it in the console.
 */
public final class InstallFailureReasons {

    public static final class Reason {
        /** Stable key: storage, signature, downgrade, blocked, invalid, checksum, incompatible, timeout, aborted, notOwner, download, network, expired, unsupported, unknown. */
        public final String code;
        /** The reason, in Spanish. */
        public final String text;
        /** Whether a later automatic retry can succeed without anybody changing anything. */
        public final boolean retryable;

        Reason(String code, String text, boolean retryable) {
            this.code = code;
            this.text = text;
            this.retryable = retryable;
        }
    }

    private InstallFailureReasons() {}

    private static final Pattern HTTP = Pattern.compile("HTTP (\\d{3})");

    public static final Reason EXPIRED = new Reason("expired",
            "La orden venció sin que el equipo la recibiera o respondiera (apagado o sin conexión).", true);
    public static final Reason UNSUPPORTED = new Reason("unsupported",
            "El agente del equipo no reconoce la orden de instalación (agente muy antiguo): actualiza el agente.", false);

    /** The reason for a failed install whose result text is [detail] (may be null). Never null. */
    public static Reason translate(String detail) {
        String d = detail == null ? "" : detail;
        String u = d.toUpperCase();
        if (has(u, "INSUFFICIENT_STORAGE", "FAILURE_STORAGE", "ENOSPC", "NO SPACE LEFT", "NOT ENOUGH SPACE", "FAILED TO ALLOCATE")) {
            return new Reason("storage", "No hay espacio suficiente en el equipo. Libera almacenamiento; se reintentará.", true);
        }
        if (has(u, "UPDATE_INCOMPATIBLE", "SIGNATURES DO NOT MATCH", "INCONSISTENT_CERTIFICATES", "SHARED_USER_INCOMPATIBLE")) {
            return new Reason("signature", "La firma del APK no coincide con la de la versión instalada en el equipo. "
                    + "Sube el APK firmado con el mismo certificado, o desinstala la app del equipo antes de instalar esta.", false);
        }
        if (has(u, "VERSION_DOWNGRADE", "DOWNGRADE BLOCKED", "DOWNGRADE DETECTED")) {
            return new Reason("downgrade", "El equipo ya tiene una versión más nueva: Android no instala una versión menor. "
                    + "Sube una versión con versionCode mayor, o permite volver a la versión anterior en la política.", false);
        }
        if (has(u, "PENDING_USER_ACTION", "REQUIRES DEVICE OWNER")) {
            return new Reason("notOwner", "El equipo pidió confirmación del usuario: el agente no es Device Owner, "
                    + "así que no puede instalar en silencio. Vuelve a enrolarlo como Device Owner.", false);
        }
        if (has(u, "CHECKSUM MISMATCH")) {
            return new Reason("checksum", "El archivo descargado llegó dañado (su SHA-256 no coincide). Se descargará de nuevo.", true);
        }
        if (has(u, "USER_RESTRICTED", "ISOLATION_VIOLATION", "FAILURE_BLOCKED", "VERIFICATION_FAILURE", "NO DEJA INSTALAR",
                "INSTALL_FAILED_ABORTED: USER REJECTED")) {
            return new Reason("blocked", "El sistema del equipo (fabricante o Play Protect) bloqueó la instalación desde el MDM.", false);
        }
        if (has(u, "OLDER_SDK", "NEWER_SDK", "NO_MATCHING_ABIS", "CPU_ABI_INCOMPATIBLE", "MISSING_SPLIT", "MISSING_FEATURE",
                "MISSING_SHARED_LIBRARY", "FAILURE_INCOMPATIBLE")) {
            return new Reason("incompatible", "La app no es compatible con este equipo (versión de Android, procesador o le faltan partes del bundle).", false);
        }
        if (has(u, "PARSE_FAILED", "INVALID_APK", "FAILURE_INVALID", "NO_CERTIFICATES", "BAD_MANIFEST", "NOT AN APK", "DEXOPT", "CORRUPT")) {
            return new Reason("invalid", "El APK es inválido o está dañado: Android no pudo leerlo. Vuelve a generarlo y súbelo de nuevo.", false);
        }
        if (has(u, "INSTALL_TIMEOUT")) {
            return new Reason("timeout", "Android no respondió a la instalación a tiempo. Se reintentará.", true);
        }
        if (has(u, "FAILURE_CONFLICT", "ALREADY_EXISTS", "DUPLICATE_PACKAGE", "CONFLICTING_PROVIDER", "DUPLICATE_PERMISSION")) {
            return new Reason("signature", "La app choca con otra ya instalada en el equipo (misma app con otra firma, o un "
                    + "permiso/proveedor duplicado). Desinstala la app existente o sube el APK con la firma original.", false);
        }
        if (has(u, "FAILURE_ABORTED", "INSTALL_FAILED_ABORTED", "SESSION WAS ABANDONED")) {
            return new Reason("aborted", "El sistema del equipo canceló la instalación. Se reintentará.", true);
        }
        if (has(u, "APK FETCH")) {
            Matcher m = HTTP.matcher(d);
            if (m.find() && m.group(1).startsWith("4")) {
                return new Reason("download", "El equipo no pudo descargar el APK (HTTP " + m.group(1)
                        + "): el archivo ya no está en el servidor. Vuelve a subir la versión.", false);
            }
            return new Reason("network", "El equipo no pudo descargar el APK (sin conexión o conexión inestable). Se reintentará.", true);
        }
        if (d.trim().isEmpty()) {
            return new Reason("unknown", "El equipo no indicó el motivo del fallo.", true);
        }
        return new Reason("unknown", "Android rechazó la instalación.", true);
    }

    private static boolean has(String upper, String... needles) {
        for (String n : needles) if (upper.contains(n)) return true;
        return false;
    }
}
