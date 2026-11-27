package com.dallycontrol.core.wake

import com.dallycontrol.core.net.KeyValueStore
import com.dallycontrol.proto.ProtocolJson
import com.dallycontrol.proto.WakeConfig
import com.dallycontrol.proto.WakeReport

/**
 * When the next backup check-in is due. Two paces:
 *
 *  - the **base** pace ([DEFAULT_POLL_MINUTES], or the policy's), all the time;
 *  - the **fast** pace ([DEFAULT_FAST_MINUTES]) for [DEFAULT_WINDOW_MINUTES] after something happened on the phone
 *    (screen on or off, unlock, charger, network back). The agent cannot know that a command is waiting while it
 *    has no link, but activity is when somebody is likely acting on the device — and when a check-in is cheapest,
 *    because the radio is already up.
 *
 * Pure: the alarm that runs it lives in the app module.
 */
object WakeSchedule {
    const val DEFAULT_POLL_MINUTES = 5
    const val DEFAULT_FAST_MINUTES = 2
    const val DEFAULT_WINDOW_MINUTES = 10
    const val MINUTE_MS = 60_000L
    private const val MAX_POLL_MINUTES = 15
    private const val MAX_FAST_MINUTES = 5
    private const val MAX_WINDOW_MINUTES = 60
    private val POLL_RANGE = 1..MAX_POLL_MINUTES
    private val FAST_RANGE = 1..MAX_FAST_MINUTES
    private val WINDOW_RANGE = 0..MAX_WINDOW_MINUTES

    data class Plan(val delayMs: Long, val fast: Boolean)

    fun pollMinutes(config: WakeConfig?): Int = (config?.pollMinutes ?: DEFAULT_POLL_MINUTES).coerceIn(POLL_RANGE)

    fun fastMinutes(config: WakeConfig?): Int =
        (config?.fastPollMinutes ?: DEFAULT_FAST_MINUTES).coerceIn(FAST_RANGE).coerceAtMost(pollMinutes(config))

    fun windowMinutes(config: WakeConfig?): Int =
        (config?.fastWindowMinutes ?: DEFAULT_WINDOW_MINUTES).coerceIn(WINDOW_RANGE)

    /** @param lastActivityAt when something last happened on the phone (0 = never noted). */
    fun plan(config: WakeConfig?, lastActivityAt: Long, now: Long): Plan {
        val windowMs = windowMinutes(config) * MINUTE_MS
        val sinceActivity = now - lastActivityAt
        val fast = lastActivityAt > 0 && windowMs > 0 && sinceActivity in 0 until windowMs
        if (!fast) return Plan(pollMinutes(config) * MINUTE_MS, fast = false)
        // The last fast beat may not overshoot the window by more than a base beat would.
        return Plan(fastMinutes(config) * MINUTE_MS, fast = true)
    }
}

/**
 * What the wake channels remember across restarts: the server's schedule, the last activity on the phone, the push
 * registration and what triggered the check-in about to be reported.
 */
class WakeState(private val store: KeyValueStore, private val clock: () -> Long = System::currentTimeMillis) {

    @Volatile private var cachedConfig: WakeConfig? = null
    @Volatile private var configLoaded = false

    var config: WakeConfig?
        @Synchronized get() {
            if (!configLoaded) {
                cachedConfig = runCatching {
                    store.getString(KEY_CONFIG)?.let { ProtocolJson.json.decodeFromString(WakeConfig.serializer(), it) }
                }.getOrNull()
                configLoaded = true
            }
            return cachedConfig
        }
        @Synchronized set(value) {
            cachedConfig = value
            configLoaded = true
            store.putString(KEY_CONFIG, value?.let { ProtocolJson.json.encodeToString(WakeConfig.serializer(), it) })
        }

    val lastActivityAt: Long get() = store.getString(KEY_ACTIVITY)?.toLongOrNull() ?: 0L

    fun noteActivity() = store.putString(KEY_ACTIVITY, clock().toString())

    fun plan(): WakeSchedule.Plan = WakeSchedule.plan(config, lastActivityAt, clock())

    var pushToken: String?
        get() = store.getString(KEY_TOKEN)
        set(value) = store.putString(KEY_TOKEN, value)

    /** One of `PushState`; null until the first registration attempt. */
    var pushState: String?
        get() = store.getString(KEY_STATE)
        set(value) = store.putString(KEY_STATE, value)

    var pushError: String?
        get() = store.getString(KEY_ERROR)
        set(value) = store.putString(KEY_ERROR, value?.take(MAX_ERROR))

    /** The project the stored token belongs to (`projectId|appId`), so a changed project re-registers. */
    var pushProject: String?
        get() = store.getString(KEY_PROJECT)
        set(value) = store.putString(KEY_PROJECT, value)

    @Volatile private var trigger: String? = null

    /** Note what is about to cause a check-in. The first cause wins until a report takes it. */
    @Synchronized
    fun markTrigger(cause: String) {
        if (trigger == null) trigger = cause
    }

    @Synchronized
    fun takeTrigger(): String? = trigger.also { trigger = null }

    private companion object {
        const val KEY_CONFIG = "wake.config"
        const val KEY_ACTIVITY = "wake.activityAt"
        const val KEY_TOKEN = "wake.push.token"
        const val KEY_STATE = "wake.push.state"
        const val KEY_ERROR = "wake.push.error"
        const val KEY_PROJECT = "wake.push.project"
        const val MAX_ERROR = 300
    }
}

/** The check-in cycle's view of the wake channels (implemented in the app module, where the platform lives). */
interface WakeBridge {
    /** What to tell the server with this check-in; null = nothing (the field is omitted). */
    fun report(): WakeReport?

    /** The server's answer: the schedule to keep and the push project to register with (null = none). */
    fun onConfig(config: WakeConfig?)

    companion object {
        val NONE = object : WakeBridge {
            override fun report(): WakeReport? = null
            override fun onConfig(config: WakeConfig?) = Unit
        }
    }
}
