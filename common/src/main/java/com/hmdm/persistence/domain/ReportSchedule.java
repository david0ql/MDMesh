/*
 * DallyControl — scheduled fleet reports (new module, not part of upstream Headwind).
 */
package com.hmdm.persistence.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;

/**
 * A report sent by e-mail on a schedule (table report_schedule): which report, in which format, for which folder,
 * to whom, and when (daily / weekly / monthly at a local time of {@link #timezone}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReportSchedule implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer id;
    private int customerId;
    private String name;
    /** inventory, offline, outdated-agents… (see ReportScheduleService.REPORTS). */
    private String report = "inventory";
    /** xlsx | csv */
    private String format = "xlsx";
    /** CSV field separator: ";" "," "tab" "|". */
    private String csvSeparator = ";";
    /** daily | weekly | monthly */
    private String frequency = "daily";
    private int runHour = 7;
    private int runMinute;
    /** 1 = Monday … 7 = Sunday (weekly). */
    private Integer dayOfWeek;
    /** 1 … 31 (monthly); a month without that day uses its last one. */
    private Integer dayOfMonth;
    private String timezone = "America/Bogota";
    /** Comma-separated e-mail addresses. */
    private String recipients;
    /** Only this folder and its sub-folders; null = every device. */
    private Integer groupId;
    private boolean enabled = true;
    private Integer createdBy;
    private Long createdAt;
    private Long updatedAt;
    private Long lastRunAt;
    /** ok | error | smtp (SMTP not configured). */
    private String lastStatus;
    private String lastError;
    private Long nextRunAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public int getCustomerId() { return customerId; }
    public void setCustomerId(int customerId) { this.customerId = customerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getReport() { return report; }
    public void setReport(String report) { this.report = report; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public String getCsvSeparator() { return csvSeparator; }
    public void setCsvSeparator(String csvSeparator) { this.csvSeparator = csvSeparator; }
    public String getFrequency() { return frequency; }
    public void setFrequency(String frequency) { this.frequency = frequency; }
    public int getRunHour() { return runHour; }
    public void setRunHour(int runHour) { this.runHour = runHour; }
    public int getRunMinute() { return runMinute; }
    public void setRunMinute(int runMinute) { this.runMinute = runMinute; }
    public Integer getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(Integer dayOfWeek) { this.dayOfWeek = dayOfWeek; }
    public Integer getDayOfMonth() { return dayOfMonth; }
    public void setDayOfMonth(Integer dayOfMonth) { this.dayOfMonth = dayOfMonth; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public String getRecipients() { return recipients; }
    public void setRecipients(String recipients) { this.recipients = recipients; }
    public Integer getGroupId() { return groupId; }
    public void setGroupId(Integer groupId) { this.groupId = groupId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Integer getCreatedBy() { return createdBy; }
    public void setCreatedBy(Integer createdBy) { this.createdBy = createdBy; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public Long getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(Long lastRunAt) { this.lastRunAt = lastRunAt; }
    public String getLastStatus() { return lastStatus; }
    public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Long getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(Long nextRunAt) { this.nextRunAt = nextRunAt; }
}
