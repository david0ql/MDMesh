package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

/**
 * Remote-support section of {@link DesiredConfig} and of the stored policy (ADR 0012). Every field is optional;
 * absent = the agent's default.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DesiredRemoteSupport {
    /**
     * Who shows and controls the screen: {@code auto} (default: the agent itself when it is authorised, droidVNC-NG
     * otherwise), {@code builtin} (only the agent), {@code droidvnc} (only droidVNC-NG; the agent's own screen
     * service stays switched off and hidden).
     */
    private String engine;
    /** false = no "Soporte está viendo tu pantalla" notice on the phone during a session (shown by default). */
    private Boolean notice;
    /** Voice calls: {@code ask} (default: the person accepts on the phone) or {@code auto} (answered unattended). */
    private String callAccept;
    /** false = the agent does not ask for the one-time authorisation right after enrollment (asked by default). */
    private Boolean setupPrompt;

    /** Known values only; null when nothing is left. */
    public DesiredRemoteSupport cleaned() {
        DesiredRemoteSupport c = new DesiredRemoteSupport();
        String e = engine == null ? "" : engine.trim().toLowerCase(java.util.Locale.ROOT);
        c.engine = "builtin".equals(e) || "droidvnc".equals(e) ? e : null; // auto is the default: not stored
        c.notice = Boolean.FALSE.equals(notice) ? Boolean.FALSE : null;
        String a = callAccept == null ? "" : callAccept.trim().toLowerCase(java.util.Locale.ROOT);
        c.callAccept = "auto".equals(a) ? a : null; // ask is the default: not stored
        c.setupPrompt = Boolean.FALSE.equals(setupPrompt) ? Boolean.FALSE : null;
        return c.isBlank() ? null : c;
    }

    @JsonIgnore
    public boolean isBlank() {
        return engine == null && notice == null && callAccept == null && setupPrompt == null;
    }
}
