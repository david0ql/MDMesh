/*
 *
 * DallyControl — one row of the audit trail (audit_trail): who did what, on what, from where, and how it ended.
 * New module, not part of upstream Headwind. Rows are written once and never updated (the table refuses UPDATE).
 *
 */

package com.hmdm.persistence.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AuditEntry implements Serializable {
    private static final long serialVersionUID = 1L;

    public Long id;
    public long createdAt;
    public Integer customerId;

    // --- who ---
    public Integer userId;
    public String login;
    public String userName;
    public Integer roleId;
    public String roleName;
    public String ip;
    public String userAgent;

    // --- what ---
    /** Module key, e.g. "dispositivos" (see AuditCatalog). */
    public String category;
    /** Stable machine key, e.g. "device.command.device.wipe". */
    public String action;
    /** What happened, in Spanish, e.g. "Comando: borrado de fábrica". */
    public String label;
    public String method;
    public String path;

    // --- on what ---
    /** device | devices | group | config | app | user | role | script | zone | ... */
    public String targetType;
    public String targetId;
    public String targetName;
    /** Every device the action touched (device numbers), as a Postgres text[] literal. Write side only. */
    @JsonIgnore
    public String deviceNumbersLiteral;
    /** Every folder the action touched (the devices' folders, or the folder itself), as an int[] literal. Write side only. */
    @JsonIgnore
    public String groupIdsLiteral;
    /** Read back from the database: "a,b,c". */
    public String deviceNumbers;
    public String groupIds;

    // --- how it ended ---
    /** ok | denied | error */
    public String result;
    public Integer httpStatus;
    public String message;
    /** Summary of the change (JSON text; secrets already removed). */
    public String detail;
    public Integer durationMs;

    // --- integrity ---
    public String prevHash;
    public String hash;
}
