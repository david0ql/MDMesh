package com.dallycontrol.core.command.wipe

import com.dallycontrol.proto.ProtocolJson
import kotlinx.serialization.Serializable

/**
 * Payload of `device.wipe`. Every field is optional: an empty payload (an older console) wipes as before, keeping
 * the factory-reset protection.
 *
 * @property intent `lost` (lost or stolen: the protection stays, the phone is of no use to whoever has it) or
 *   `reassign` (prepare for another person: the protection is removed so the phone sets up clean).
 * @property keepFrp overrides what [intent] implies.
 * @property reason shown on the phone while it wipes (Android 9-13).
 */
@Serializable
data class WipePayload(
    val intent: String? = null,
    val externalStorage: Boolean = false,
    val keepFrp: Boolean? = null,
    val euicc: Boolean = false,
    val silent: Boolean = false,
    val reason: String? = null,
)

/** The platform call a wipe ends in. */
enum class WipeCall { WIPE_DEVICE, WIPE_DATA_WITH_REASON, WIPE_DATA }

/**
 * @property flags DevicePolicyManager.WIPE_* bits.
 * @property dropped options this Android version cannot honour (reported, never silently ignored).
 */
@Serializable
data class WipePlan(
    val flags: Int,
    val call: WipeCall,
    val reason: String? = null,
    val dropped: List<String> = emptyList(),
) {
    /** One line for the command's result and the device's history. */
    fun describe(): String = buildString {
        append("flags=").append(flags)
        append(" sd=").append(flags and WipePlanner.WIPE_EXTERNAL_STORAGE != 0)
        append(" frp=").append(if (flags and WipePlanner.WIPE_RESET_PROTECTION_DATA != 0) "removed" else "kept")
        append(" esim=").append(flags and WipePlanner.WIPE_EUICC != 0)
        append(" silent=").append(flags and WipePlanner.WIPE_SILENTLY != 0)
        append(" call=").append(call.name)
        if (dropped.isNotEmpty()) append(" notApplied=").append(dropped.joinToString(","))
    }
}

/** The device side of a wipe; the real one calls DevicePolicyManager. */
interface WipeExecutor {
    /** Android 14+: full factory reset by a Device Owner. */
    fun wipeDevice(flags: Int)

    fun wipeData(flags: Int)

    /** Android 9+. */
    fun wipeData(flags: Int, reason: String)
}

/**
 * Pure: which flags and which platform call a wipe needs on an Android version.
 *
 *  - Android 14+ (API 34): `wipeDevice(flags)` — `wipeData` there only removes the calling user and fails on the
 *    system user. It takes no reason.
 *  - Android 9-13 (API 28-33): `wipeData(flags, reason)` when there is a reason to show and the wipe is not silent
 *    (the platform rejects a reason together with WIPE_SILENTLY); otherwise `wipeData(flags)`.
 *  - Android 6-8.1: `wipeData(flags)`; eSIM (API 28) and silent (API 29) do not exist yet.
 */
object WipePlanner {
    const val WIPE_EXTERNAL_STORAGE = 0x0001
    const val WIPE_RESET_PROTECTION_DATA = 0x0002
    const val WIPE_EUICC = 0x0004
    const val WIPE_SILENTLY = 0x0008

    const val INTENT_LOST = "lost"
    const val INTENT_REASSIGN = "reassign"

    private const val API_REASON = 28
    private const val API_EUICC = 28
    private const val API_SILENT = 29
    private const val API_WIPE_DEVICE = 34
    private const val MAX_REASON = 200

    /** The protection survives the wipe: what the payload says, else what its intent implies (kept by default). */
    fun keepsFrp(p: WipePayload): Boolean = p.keepFrp ?: (p.intent != INTENT_REASSIGN)

    fun plan(sdkInt: Int, p: WipePayload): WipePlan {
        val euicc = p.euicc && sdkInt >= API_EUICC
        val silent = p.silent && sdkInt >= API_SILENT
        val reason = p.reason?.trim()?.take(MAX_REASON)?.takeIf { it.isNotEmpty() }
        val call = when {
            sdkInt >= API_WIPE_DEVICE -> WipeCall.WIPE_DEVICE
            reason != null && !silent && sdkInt >= API_REASON -> WipeCall.WIPE_DATA_WITH_REASON
            else -> WipeCall.WIPE_DATA
        }
        val shown = reason.takeIf { call == WipeCall.WIPE_DATA_WITH_REASON }
        val flags = flag(p.externalStorage, WIPE_EXTERNAL_STORAGE) or flag(!keepsFrp(p), WIPE_RESET_PROTECTION_DATA) or
            flag(euicc, WIPE_EUICC) or flag(silent, WIPE_SILENTLY)
        val dropped = listOfNotNull(
            "euicc".takeIf { p.euicc && !euicc },
            "silent".takeIf { p.silent && !silent },
            "reason".takeIf { reason != null && shown == null },
        )
        return WipePlan(flags, call, shown, dropped)
    }

    private fun flag(on: Boolean, bit: Int): Int = if (on) bit else 0

    fun run(plan: WipePlan, executor: WipeExecutor) = when (plan.call) {
        WipeCall.WIPE_DEVICE -> executor.wipeDevice(plan.flags)
        WipeCall.WIPE_DATA_WITH_REASON -> executor.wipeData(plan.flags, plan.reason.orEmpty())
        WipeCall.WIPE_DATA -> executor.wipeData(plan.flags)
    }
}

/**
 * The wipe this device has announced to the server and not yet carried out, kept on disk. If the agent dies between
 * the announcement and the platform call, the next start finds it here and wipes ([WipeRunner.resume]) — the server
 * was already told the wipe ran, so it must run. Bounded: after [MAX_ATTEMPTS] starts the order is dropped and
 * reported as failed instead of looping forever on a device whose system does not wipe.
 */
class WipeJournal(
    private val load: () -> String?,
    private val save: (String?) -> Unit,
) {
    @Serializable
    data class Entry(val commandId: String, val plan: WipePlan, val attempts: Int = 0)

    fun pending(): Entry? = runCatching {
        load()?.let { ProtocolJson.json.decodeFromString(Entry.serializer(), it) }
    }.getOrNull()

    fun begin(commandId: String, plan: WipePlan) = write(Entry(commandId, plan))

    /** Count one more start that found the order still here; returns the updated entry. */
    fun attempt(): Entry? = pending()?.let { e -> e.copy(attempts = e.attempts + 1).also(::write) }

    fun clear() = save(null)

    private fun write(e: Entry) = save(ProtocolJson.json.encodeToString(Entry.serializer(), e))

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}

/** Carries out a planned wipe and resumes one the agent announced but did not get to run. */
class WipeRunner(
    private val executor: WipeExecutor,
    private val journal: WipeJournal,
) {
    fun run(plan: WipePlan) = WipePlanner.run(plan, executor)

    /**
     * Called when the agent starts. @return null when nothing was pending; otherwise what happened: `resumed`, or
     * `gaveUp: …` once the order was retried [WipeJournal.MAX_ATTEMPTS] times without the system wiping.
     */
    fun resume(): String? = journal.attempt()?.let { e ->
        if (e.attempts > WipeJournal.MAX_ATTEMPTS) {
            journal.clear()
            "gaveUp: the system did not wipe after ${WipeJournal.MAX_ATTEMPTS} attempts (${e.plan.describe()})"
        } else {
            runCatching {
                run(e.plan)
                "resumed"
            }.getOrElse {
                journal.clear()
                "gaveUp: ${it.javaClass.simpleName}: ${it.message}"
            }
        }
    }
}
