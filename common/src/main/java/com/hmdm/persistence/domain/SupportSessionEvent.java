package com.hmdm.persistence.domain;

import java.io.Serializable;

/** Something that happened inside a remote-support session: a chat line, a file, a call, a power action. */
public class SupportSessionEvent implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long id;
    private Long sessionId;
    private Long ts;
    /** chat / file / call / clipboard / action. */
    private String kind;
    /** console or device: who caused it. */
    private String origin;
    private String detail;

    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long v) { this.sessionId = v; }
    public Long getTs() { return ts; }
    public void setTs(Long v) { this.ts = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { this.kind = v; }
    public String getOrigin() { return origin; }
    public void setOrigin(String v) { this.origin = v; }
    public String getDetail() { return detail; }
    public void setDetail(String v) { this.detail = v; }
}
