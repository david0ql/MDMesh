package com.dallycontrol.core.telemetry

import com.dallycontrol.proto.TelemetrySnapshot

/** Builds the device census snapshot from the available collectors. Null only if even the
 *  always-available dynamic state can't be built (it always can). */
fun interface TelemetrySource {
    fun snapshot(): TelemetrySnapshot?

    /** The server accepted [snapshot] (e.g. drop the location trail it carried). */
    fun delivered(snapshot: TelemetrySnapshot) {}
}
