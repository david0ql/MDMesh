package com.hmdm.persistence.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;

/**
 * <p>One enrollment attempt as the server saw it (table agent_enroll_attempt): the phone downloaded the agent, asked
 * to enroll and was refused, or enrolled. It is what the console's Enroll page shows so a failed enrollment can be
 * diagnosed without the phone in hand: reason, maker, model and Android release.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentEnrollAttempt implements Serializable {

    private static final long serialVersionUID = 1L;

    /** ok, failed, download (the phone fetched the agent APK) or note (a failure the agent had before reaching us). */
    public static final String OK = "ok";
    public static final String FAILED = "failed";
    public static final String DOWNLOAD = "download";
    public static final String NOTE = "note";

    private Integer id;
    private Integer customerId;
    private Integer tokenId;
    private Integer groupId;
    private long at;
    private long lastAt;
    private int count = 1;
    private String outcome;
    private String reason;
    private String detail;
    private String path;
    private String manufacturer;
    private String model;
    private String androidRelease;
    private Integer sdkInt;
    private String agentVersion;
    private String hardwareId;
    private String serial;
    private String ip;
    private String deviceNumber;
    /** Enroll calls the agent had made when it sent this one. */
    private Integer tries;
    // Joined for the list.
    private String groupName;
    private String tokenLabel;
    private boolean reusable;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getCustomerId() { return customerId; }
    public void setCustomerId(Integer customerId) { this.customerId = customerId; }
    public Integer getTokenId() { return tokenId; }
    public void setTokenId(Integer tokenId) { this.tokenId = tokenId; }
    public Integer getGroupId() { return groupId; }
    public void setGroupId(Integer groupId) { this.groupId = groupId; }
    public long getAt() { return at; }
    public void setAt(long at) { this.at = at; }
    public long getLastAt() { return lastAt; }
    public void setLastAt(long lastAt) { this.lastAt = lastAt; }
    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getManufacturer() { return manufacturer; }
    public void setManufacturer(String manufacturer) { this.manufacturer = manufacturer; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getAndroidRelease() { return androidRelease; }
    public void setAndroidRelease(String androidRelease) { this.androidRelease = androidRelease; }
    public Integer getSdkInt() { return sdkInt; }
    public void setSdkInt(Integer sdkInt) { this.sdkInt = sdkInt; }
    public String getAgentVersion() { return agentVersion; }
    public void setAgentVersion(String agentVersion) { this.agentVersion = agentVersion; }
    public String getHardwareId() { return hardwareId; }
    public void setHardwareId(String hardwareId) { this.hardwareId = hardwareId; }
    public String getSerial() { return serial; }
    public void setSerial(String serial) { this.serial = serial; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String getDeviceNumber() { return deviceNumber; }
    public void setDeviceNumber(String deviceNumber) { this.deviceNumber = deviceNumber; }
    public Integer getTries() { return tries; }
    public void setTries(Integer tries) { this.tries = tries; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getTokenLabel() { return tokenLabel; }
    public void setTokenLabel(String tokenLabel) { this.tokenLabel = tokenLabel; }
    public boolean isReusable() { return reusable; }
    public void setReusable(boolean reusable) { this.reusable = reusable; }
}
