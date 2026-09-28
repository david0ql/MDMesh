package com.dallycontrol.policy.screenshots

import com.dallycontrol.policy.PolicyOutcome
import com.dallycontrol.policy.TogglePolicy

/**
 * Capability-abstracted screenshot / screen-capture control.
 *
 * `setEnabled(true)` allows screenshots; `setEnabled(false)` blocks them. Backed
 * by [android.app.admin.DevicePolicyManager.setScreenCaptureDisabled], whose
 * argument is inverted (it takes *disabled*). The single concrete strategy
 * ([ScreenCaptureDisablePolicy]) is selected by [ScreenshotsPolicyFactory].
 */
interface ScreenshotsPolicy : TogglePolicy {

    override fun setEnabled(enabled: Boolean): PolicyOutcome

    companion object {
        const val CAPABILITY_KEY = "screenshots"
    }
}
