package com.dallycontrol.core.telemetry

import com.dallycontrol.proto.DynamicState
import com.dallycontrol.proto.HardwareInfo
import com.dallycontrol.proto.IdentityInfo
import com.dallycontrol.proto.SecurityPosture
import com.dallycontrol.proto.TelemetrySnapshot

/**
 * Composes the census from per-category producers. [dynamic] always succeeds; the other three are
 * wrapped in runCatching so a missing permission / failure contributes null (graceful degradation).
 * Producers are lambdas so this is unit-testable without Android.
 */
class TelemetryAssembler(
    private val hardware: () -> HardwareInfo,
    private val identity: () -> IdentityInfo,
    private val dynamic: () -> DynamicState,
    private val security: () -> SecurityPosture,
    private val onDelivered: (TelemetrySnapshot) -> Unit = {},
) : TelemetrySource {
    override fun delivered(snapshot: TelemetrySnapshot) = onDelivered(snapshot)

    override fun snapshot(): TelemetrySnapshot = TelemetrySnapshot(
        dynamic = dynamic(),
        hardware = runCatching { hardware() }.getOrNull(),
        identity = runCatching { identity() }.getOrNull(),
        security = runCatching { security() }.getOrNull(),
    )
}
