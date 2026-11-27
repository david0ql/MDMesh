package com.hmdm.persistence.domain;

import java.io.Serializable;

/**
 * One remote-support session (docs/adr/0012): who opened it on which device, when, for how long and what was used
 * (screen control, voice, chat, files). The audit trail of the console's remote module.
 */
public class SupportSession implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long id;
    /** Public one-time key of the live session (never the console token). */
    private String sessionKey;
    private Integer customerId;
    private String deviceNumber;
    private Integer userId;
    private String userLogin;
    private Boolean viewOnly;
    /** builtin (agent captures and injects), droidvnc (screen through the VNC repeater) or none (chat/voice only). */
    private String engine;
    private Long startedAt;
    /** When the device joined the session; null = it never answered. */
    private Long connectedAt;
    private Long endedAt;
    /** console / device / timeout / replaced / server. */
    private String endReason;
    private Integer chatCount;
    private Integer fileCount;
    private Integer voiceSeconds;
    private Integer inputCount;

    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public String getSessionKey() { return sessionKey; }
    public void setSessionKey(String v) { this.sessionKey = v; }
    public Integer getCustomerId() { return customerId; }
    public void setCustomerId(Integer v) { this.customerId = v; }
    public String getDeviceNumber() { return deviceNumber; }
    public void setDeviceNumber(String v) { this.deviceNumber = v; }
    public Integer getUserId() { return userId; }
    public void setUserId(Integer v) { this.userId = v; }
    public String getUserLogin() { return userLogin; }
    public void setUserLogin(String v) { this.userLogin = v; }
    public Boolean getViewOnly() { return viewOnly; }
    public void setViewOnly(Boolean v) { this.viewOnly = v; }
    public String getEngine() { return engine; }
    public void setEngine(String v) { this.engine = v; }
    public Long getStartedAt() { return startedAt; }
    public void setStartedAt(Long v) { this.startedAt = v; }
    public Long getConnectedAt() { return connectedAt; }
    public void setConnectedAt(Long v) { this.connectedAt = v; }
    public Long getEndedAt() { return endedAt; }
    public void setEndedAt(Long v) { this.endedAt = v; }
    public String getEndReason() { return endReason; }
    public void setEndReason(String v) { this.endReason = v; }
    public Integer getChatCount() { return chatCount; }
    public void setChatCount(Integer v) { this.chatCount = v; }
    public Integer getFileCount() { return fileCount; }
    public void setFileCount(Integer v) { this.fileCount = v; }
    public Integer getVoiceSeconds() { return voiceSeconds; }
    public void setVoiceSeconds(Integer v) { this.voiceSeconds = v; }
    public Integer getInputCount() { return inputCount; }
    public void setInputCount(Integer v) { this.inputCount = v; }
}
