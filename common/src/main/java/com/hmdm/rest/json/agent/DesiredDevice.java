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
    /** One allowed domain: set only when there is exactly one (agents before 0.7.3 read just this). */
    private String accountDomain;
    /** Allowed Google account domains (any other Google account is removed). */
    private List<String> accountDomains;
    private String factoryReset;
    private List<String> frpAccounts;
    /** "block" = the user cannot install apps (DISALLOW_INSTALL_APPS); MDM silent installs still work. */
    private String installApps;
    /** "block" = no safe mode (DISALLOW_SAFE_BOOT). */
    private String safeBoot;
    /** "block" = no USB debugging / developer options (DISALLOW_DEBUGGING_FEATURES). */
    private String debugging;
    /** "block" = the bootloader cannot be unlocked from developer options (no flashing another system). */
    private String oemUnlock;
    /** Wipe the device after this many wrong unlock attempts in a row (3-100); null = never. */
    private Integer maxFailedPasswordsForWipe;
    /**
     * Stored with the policy only, never sent to a device: {@code off} = no company reset protection even when the
     * folder or the company has default accounts. Null = the policy's own {@link #frpAccounts}, else the defaults.
     */
    private String frpMode;

    public DesiredDevice copy() {
        DesiredDevice r = new DesiredDevice();
        r.tethering = tethering;
        r.googleAccounts = googleAccounts;
        r.accountDomain = accountDomain;
        r.accountDomains = accountDomains;
        r.factoryReset = factoryReset;
        r.frpAccounts = frpAccounts;
        r.installApps = installApps;
        r.safeBoot = safeBoot;
        r.debugging = debugging;
        r.oemUnlock = oemUnlock;
        r.maxFailedPasswordsForWipe = maxFailedPasswordsForWipe;
        r.frpMode = frpMode;
        return r;
    }

    /** Nothing is set. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isBlank() {
        return tethering == null && googleAccounts == null && accountDomain == null && accountDomains == null
                && factoryReset == null && frpAccounts == null && installApps == null && safeBoot == null
                && debugging == null && oemUnlock == null && maxFailedPasswordsForWipe == null && frpMode == null;
    }
}
