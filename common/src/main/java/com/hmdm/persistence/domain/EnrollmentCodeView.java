package com.hmdm.persistence.domain;

/** A reusable enrollment code as the console lists it (the code itself is shown: operators type or print it). */
public class EnrollmentCodeView {
    private Integer id;
    private String code;
    private String label;
    private Integer groupId;
    private String groupName;
    private int uses;
    private boolean revoked;
    private Long createdAt;
    private Long expiresAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Integer getGroupId() { return groupId; }
    public void setGroupId(Integer groupId) { this.groupId = groupId; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public int getUses() { return uses; }
    public void setUses(int uses) { this.uses = uses; }
    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }
}
