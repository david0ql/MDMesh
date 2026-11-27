package com.dallycontrol.agent.provisioning

import android.os.Bundle
import com.dallycontrol.core.enroll.EnrollStatusStore

/**
 * Handles `ACTION_ADMIN_POLICY_COMPLIANCE`, launched right after provisioning.
 *
 * On Android 12+ (the modern provisioning contract) the `PROVISIONING_ADMIN_EXTRAS_BUNDLE`
 * - which carries our enroll token and the deployment's server URL - is delivered
 * to THIS activity's intent, not to [com.dallycontrol.agent.admin.AdminReceiver.onProfileProvisioningComplete].
 * [ProvisioningIntake] persists both, applies the Device-Owner baseline and starts enrolling against the server the
 * QR named, not the baked fallback (which in release builds is a placeholder).
 *
 * While the phone enrolls it shows what is happening ([EnrollStatusActivity]): a technician holding a phone that
 * cannot reach the server sees the reason and a retry button here, instead of a phone that silently never shows up
 * in the console. The result is always RESULT_OK: a server that is down must not fail the provisioning itself -
 * the agent keeps retrying afterwards.
 */
class AdminPolicyComplianceActivity : EnrollStatusActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hasToken = ProvisioningIntake.accept(
            this, ProvisioningIntake.extrasOf(intent), EnrollStatusStore.PATH_QR_MODERN,
        )
        setResult(RESULT_OK)
        if (hasToken) showStatus() else finish()
    }

    override fun onDone() {
        setResult(RESULT_OK)
        finish()
    }
}
