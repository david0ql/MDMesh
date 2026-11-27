package com.hmdm.persistence.domain;

import lombok.Getter;
import lombok.Setter;

/**
 * Latest connection diagnostics of a device (table {@code device_conn_diag}, one row per device number): what the
 * device looked like at its last report (screen, network) and the last failure / silence its agent explained.
 */
@Getter
@Setter
public class DeviceConnection {
    private String deviceNumber;
    private Long updatedAt;
    private Boolean screenOn;
    private String network;
    private String serverHost;
    private Boolean usingBackup;
    /** Socket reconnections reported in this check-in (added to the stored total). */
    private Integer reconnects;

    private String failureKind;
    private Long failureAt;
    private String failureDetail;
    private Integer failureHttpCode;
    private String failureChannel;
    private String failureNetwork;
    private String failureSsid;
    private String failureHost;
    private String failureIssuer;
    private Integer failureCount;

    private String stopReason;
    private Long stopAt;
    private Long stopResumedAt;
    private Integer stopBattery;
}
