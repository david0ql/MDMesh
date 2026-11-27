/*
 *
 * DallyControl — how a request ended, for the audit trail: ok, denied (no permission, outside the user's folders,
 * not signed in, wrong password) or error. The console API answers most refusals with HTTP 200 and a JSON envelope
 * {"status":"ERROR","message":"error.permission.denied"}, so the start of the response body is read too.
 *
 */

package com.hmdm.audit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AuditOutcome {
    public static final String OK = "ok";
    public static final String DENIED = "denied";
    public static final String ERROR = "error";

    public final String result;
    /** A short reason in Spanish, or the server's own message; null when ok. */
    public final String message;

    private AuditOutcome(String result, String message) {
        this.result = result;
        this.message = message;
    }

    private static final Pattern STATUS = Pattern.compile("\"status\"\\s*:\\s*\"(OK|ERROR|WARNING)\"");
    private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * @param httpStatus the response status
     * @param bodyStart  the first bytes of the response body (may be null or cut)
     * @param jsonBody   whether the response was JSON (an export is not, and is never parsed)
     * @param signIn     the request was a sign-in: any refusal is "denied"
     */
    public static AuditOutcome of(int httpStatus, String bodyStart, boolean jsonBody, boolean signIn) {
        if (httpStatus == 401 || httpStatus == 403) {
            return new AuditOutcome(DENIED, httpStatus == 401 ? "No autenticado" : "Acceso denegado (permiso o alcance de carpetas)");
        }
        if (httpStatus == 429) return new AuditOutcome(DENIED, "Demasiados intentos");
        if (httpStatus >= 400) return new AuditOutcome(ERROR, "HTTP " + httpStatus);
        if (jsonBody && bodyStart != null) {
            // Only the envelope's own status counts: it is the first field the server writes.
            String head = bodyStart.length() > 400 ? bodyStart.substring(0, 400) : bodyStart;
            Matcher s = STATUS.matcher(head);
            if (s.find() && s.start() < 4 && "ERROR".equals(s.group(1))) {
                String msg = null;
                Matcher m = MESSAGE.matcher(head);
                if (m.find()) msg = unescape(m.group(1));
                if (signIn) return new AuditOutcome(DENIED, "Usuario o contraseña incorrectos, o cuenta bloqueada");
                if (msg != null && msg.contains("permission.denied")) {
                    return new AuditOutcome(DENIED, "Permiso denegado");
                }
                return new AuditOutcome(ERROR, humanize(msg));
            }
        }
        return new AuditOutcome(OK, null);
    }

    /** The server's message keys, in words. Anything else (already a Spanish sentence) is kept. */
    static String humanize(String msg) {
        if (msg == null || msg.isEmpty()) return "La operación fue rechazada";
        switch (msg) {
            case "error.internal.server": return "Error interno del servidor";
            case "error.notfound.object": return "El elemento no existe";
            case "error.notfound.device": return "El dispositivo no existe";
            case "error.agent.device.unknown": return "El dispositivo no existe";
            case "error.agent.command.invalid": return "Comando inválido";
            case "error.duplicate.role": return "Ya existe un rol con ese nombre";
            case "error.duplicate.device": return "Ya existe un dispositivo con ese número";
            case "error.agent.remote.unsupported": return "El dispositivo no admite control remoto";
            default: return msg.length() > 300 ? msg.substring(0, 300) : msg;
        }
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n': out.append(' '); break;
                case 't': out.append(' '); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            out.append('?');
                        }
                    }
                    break;
                default: out.append(n);
            }
        }
        return out.toString();
    }
}
