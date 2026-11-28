package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

/** A Wi-Fi network of {@link DesiredConfig} the agent saves on the device (the agent's ConfigWifi). */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DesiredWifi {
    private String ssid;
    private String password;
    private String security;
    private Boolean hidden;
    private Boolean autoConnect;
    private Integer priority;
    private com.hmdm.util.DcPolicy.WifiProxy proxy;
    private com.hmdm.util.DcPolicy.WifiIp ip;
    private String mac;
    private String metered;
    private Eap eap;

    /** Enterprise parameters as the agent takes them: certificates travel inline, resolved from the server's store. */
    @Getter
    @Setter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Eap {
        private String method;
        private String phase2;
        private String identity;
        private String anonymousIdentity;
        private String password;
        private String domain;
        private String altSubjectMatch;
        /** CA chain, base64 DER. */
        private java.util.List<String> caCerts;
        /** Client private key, base64 PKCS#8. */
        private String clientKey;
        private String clientKeyAlgorithm;
        /** Client certificate chain, base64 DER, leaf first. */
        private java.util.List<String> clientChain;
        /** Not sent: the stored certificates to resolve into the fields above (see ConfigReconciler). */
        @com.fasterxml.jackson.annotation.JsonIgnore
        private java.util.List<Integer> caCertIds;
        @com.fasterxml.jackson.annotation.JsonIgnore
        private Integer clientCertId;
    }
}
