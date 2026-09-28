package com.dallycontrol.core.telemetry

import com.dallycontrol.proto.EventType
import com.dallycontrol.proto.SimSlotDto
import com.dallycontrol.proto.SimStateDto
import java.security.MessageDigest

/**
 * What changed between two SIM observations, as timeline events (Android-free, unit-tested).
 *
 * `unknown` (modem starting, card I/O error) carries no information: it neither raises an event nor replaces the
 * baseline, so a modem restart does not look like "removed, then inserted". The first observation only sets the
 * baseline.
 */
object SimDiff {
    const val READY = "ready"
    const val ABSENT = "absent"
    const val LOCKED = "locked"
    const val UNKNOWN = "unknown"

    data class Event(val type: String, val detail: String)

    fun events(previous: SimStateDto?, now: SimStateDto): List<Event> {
        if (previous == null || now.state == UNKNOWN || previous.state == UNKNOWN) return emptyList()
        val wasIn = previous.state != ABSENT
        val isIn = now.state != ABSENT
        return when {
            wasIn && !isIn -> listOf(Event(EventType.SIM_REMOVED, "was ${describe(previous)}"))
            !wasIn && isIn -> listOf(Event(EventType.SIM_INSERTED, describe(now)))
            isIn && previous.state == READY && now.state == READY && previous.fingerprint != now.fingerprint ->
                listOf(Event(EventType.SIM_CHANGED, "${describe(previous)} -> ${describe(now)}"))
            else -> emptyList()
        }
    }

    /** Whether [now] should become the baseline for the next comparison. */
    fun isBaseline(now: SimStateDto): Boolean = now.state != UNKNOWN

    fun fingerprint(slots: List<SimSlotDto>): String? {
        if (slots.isEmpty()) return null
        val canonical = slots.sortedBy { it.slot }
            .joinToString("|") { "${it.slot}:${it.subscriptionId}:${it.carrier.orEmpty()}:${it.number.orEmpty()}" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun describe(s: SimStateDto): String =
        s.slots.joinToString(", ") { slot ->
            listOfNotNull(slot.carrier?.takeIf { it.isNotBlank() }, slot.number?.takeIf { it.isNotBlank() })
                .joinToString(" ").ifBlank { "slot ${slot.slot}" }
        }.ifBlank { s.state }
}
