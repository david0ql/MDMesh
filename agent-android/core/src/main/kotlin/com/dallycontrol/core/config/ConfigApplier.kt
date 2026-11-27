package com.dallycontrol.core.config

import com.dallycontrol.core.kiosk.KioskApplier
import com.dallycontrol.core.store.ConfigStateStore
import com.dallycontrol.kiosk.KioskResult
import com.dallycontrol.policy.PolicyOutcome
import com.dallycontrol.policy.TogglePolicy
import com.dallycontrol.proto.ConfigAppPolicy
import com.dallycontrol.proto.ConfigApplyPayload
import com.dallycontrol.proto.ConfigBrowser
import com.dallycontrol.proto.ConfigApplyResult
import com.dallycontrol.proto.ConfigOutcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Converges the device to a desired-state document. Each present section is applied through the code that
 * already serves the imperative commands (toggle strategies, [KioskApplier], location mode), so `config.apply`
 * adds no new device behavior — only orchestration and reporting.
 *
 * Idempotent: applying the same document twice is a no-op at the OS level. The document is persisted (and its
 * revision reported to the server) only when no section failed; `unsupported` is final and does not block.
 *
 * Serialized: a boot re-apply ([reapplyPersisted]) and a freshly delivered `config.apply` never interleave —
 * otherwise an older persisted document could finish last and overwrite the newer one.
 */
class ConfigApplier(
    private val toggles: Map<String, TogglePolicy>,
    private val kiosk: KioskApplier,
    private val setLocationMode: (String) -> Unit,
    private val store: ConfigStateStore,
    private val browser: ManagedBrowser? = null,
    private val apps: AppPolicyEnforcer? = null,
    private val wifi: ManagedWifi? = null,
    /** Apply (or clear, with null) the Android system-update policy; returns a ConfigOutcome. */
    private val systemUpdates: ((com.dallycontrol.proto.ConfigSystemUpdate?) -> String)? = null,
    /** Apply (or clear, with null) the device security rules; returns a ConfigOutcome. Runs after the browser section. */
    private val deviceRules: ((com.dallycontrol.proto.ConfigDevice?) -> String)? = null,
    /** Seconds an active location fix may wait (null = the agent's default). */
    private val setLocationFixTimeout: (Int?) -> Unit = {},
    /**
     * Apply the hardware and ports rules (null = clear what an earlier document set). Receives the restrictions other
     * sections of the document keep on; returns one outcome per rule (`hardware.<key>` / `restrictions.<key>`).
     * Runs last, so a rule it blocks wins over a toggle that allows the same restriction.
     */
    private val hardware: ((com.dallycontrol.proto.ConfigHardware?, Set<String>) -> Map<String, String>)? = null,
    // Keep last: tests pass it as a trailing lambda.
    private val setTrackingMinutes: (Int) -> Unit = {},
) {
    private val mutex = Mutex()

    suspend fun apply(doc: ConfigApplyPayload): ConfigApplyResult = mutex.withLock { applyLocked(doc) }

    private suspend fun applyLocked(doc: ConfigApplyPayload): ConfigApplyResult {
        val outcomes = linkedMapOf<String, String>()
        for ((key, enabled) in doc.policies) {
            outcomes["policies.$key"] = when (val o = toggles[key]?.setEnabled(enabled)) {
                null, PolicyOutcome.Unsupported -> ConfigOutcome.UNSUPPORTED
                PolicyOutcome.Applied -> ConfigOutcome.APPLIED
                is PolicyOutcome.Failed -> ConfigOutcome.failed(o.reason)
            }
        }
        val previous = store.load()
        applyKiosk(doc)?.let { outcomes["kiosk"] = it }
        doc.location?.let { loc ->
            outcomes["location"] = runCatching {
                setLocationMode(loc.mode)
                setLocationFixTimeout(loc.fixTimeoutSec)
                ConfigOutcome.APPLIED
            }
                .getOrElse { ConfigOutcome.failed(it.message ?: "location mode") }
        }
        // Browser and app policy: a section that disappears is undone (lists cleared, apps unsuspended), since
        // only a configuration ever set them.
        val desiredBrowser = doc.browser ?: previous?.browser?.let { ConfigBrowser(mode = "open") }
        desiredBrowser?.let { b ->
            val o = browser?.let { runCatching { it.apply(b) }.getOrElse { e -> ConfigOutcome.failed(e.message ?: "browser") } }
                ?: ConfigOutcome.UNSUPPORTED
            if (doc.browser != null) outcomes["browser"] = o
        }
        val desiredApps = doc.apps ?: previous?.apps?.let { ConfigAppPolicy(mode = "open") }
        desiredApps?.let { a ->
            val o = apps?.let { runCatching { it.apply(a) }.getOrElse { e -> ConfigOutcome.failed(e.message ?: "apps") } }
                ?: ConfigOutcome.UNSUPPORTED
            if (doc.apps != null) outcomes["apps"] = o
        }
        val desiredWifi = doc.wifi ?: previous?.wifi?.let { emptyList() }
        desiredWifi?.let { nets ->
            val o = wifi?.let { runCatching { it.apply(nets) }.getOrElse { e -> ConfigOutcome.failed(e.message ?: "wifi") } }
                ?: ConfigOutcome.UNSUPPORTED
            if (doc.wifi != null) outcomes["wifi"] = o
        }
        if (doc.systemUpdate != null || previous?.systemUpdate != null) {
            val o = systemUpdates?.let { runCatching { it(doc.systemUpdate) }.getOrElse { e -> ConfigOutcome.failed(e.message ?: "system update") } }
                ?: ConfigOutcome.UNSUPPORTED
            if (doc.systemUpdate != null) outcomes["systemUpdate"] = o
        }
        if (doc.device != null || previous?.device != null) {
            val o = deviceRules?.let { runCatching { it(doc.device) }.getOrElse { e -> ConfigOutcome.failed(e.message ?: "device rules") } }
                ?: ConfigOutcome.UNSUPPORTED
            if (doc.device != null) outcomes["device"] = o
        }
        runCatching { setTrackingMinutes(doc.tracking?.intervalMinutes ?: 0) }
            .onSuccess { if (doc.tracking != null) outcomes["tracking"] = ConfigOutcome.APPLIED }
            .onFailure { if (doc.tracking != null) outcomes["tracking"] = ConfigOutcome.failed(it.message ?: "tracking") }
        outcomes.putAll(applyHardware(doc, previous))
        val result = ConfigApplyResult(doc.revision, outcomes)
        if (succeeded(result)) store.save(doc)
        return result
    }

    /** Hardware and ports: applied when the document has the section, undone when only the previous one had it. */
    private fun applyHardware(doc: ConfigApplyPayload, previous: ConfigApplyPayload?): Map<String, String> {
        if (doc.hardware == null && previous?.hardware == null) return emptyMap()
        val held = com.dallycontrol.policy.hardware.HardwareCatalog.heldByOtherSections(
            doc.policies, doc.device?.tethering, doc.device?.factoryReset, doc.device?.installApps,
        )
        val asked = doc.hardware?.rules?.keys.orEmpty().map { "hardware.$it" } +
            doc.hardware?.restrictions.orEmpty().map { "restrictions.$it" }
        return hardware?.let { apply ->
            runCatching { apply(doc.hardware, held) }
                .getOrElse { e -> asked.associateWith { ConfigOutcome.failed(e.message ?: "hardware") } }
        } ?: asked.associateWith { ConfigOutcome.UNSUPPORTED }
    }

    /** @return the kiosk outcome, or null when nothing was asserted or exited (the key is then omitted). */
    private suspend fun applyKiosk(doc: ConfigApplyPayload): String? {
        // Absent kiosk = "configuration does not assert kiosk". Exit only when the LAST APPLIED CONFIG asserted
        // it (the admin turned it off). Kiosk entered by an ad-hoc kiosk.enter is never lifted here — otherwise
        // the first apply after upgrading would drop every manually-kiosked device.
        val previousConfigHadKiosk = store.load()?.kiosk != null
        val desiredKiosk = doc.kiosk
        val r = when {
            desiredKiosk != null -> kiosk.enter(desiredKiosk)
            previousConfigHadKiosk && kiosk.isPersisted() -> kiosk.exit()
            else -> return null
        }
        return when (r) {
            KioskResult.Ok -> ConfigOutcome.APPLIED
            KioskResult.Unsupported -> ConfigOutcome.UNSUPPORTED
            is KioskResult.Failed -> ConfigOutcome.failed(r.reason)
        }
    }

    /** Re-run the last fully-applied document (after boot / self-update). Null when nothing is persisted. */
    suspend fun reapplyPersisted(): ConfigApplyResult? = mutex.withLock { store.load()?.let { applyLocked(it) } }

    companion object {
        fun succeeded(r: ConfigApplyResult): Boolean = r.outcomes.values.none(ConfigOutcome::isFailed)
    }
}
