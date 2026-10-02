package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Device security rules of a policy: data sharing ({@code allow} | {@code block}), Google accounts ({@code block} =
 * none can be added; {@code accountDomain} = only that domain stays), factory reset from Settings ({@code block}) and
 * the Google account ids that may set the phone up again after a reset from recovery (Android 11+).
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DesiredDevice {
    private String tethering;
    private String googleAccounts;
    private String accountDomain;
    private String factoryReset;
    private List<String> frpAccounts;
}
