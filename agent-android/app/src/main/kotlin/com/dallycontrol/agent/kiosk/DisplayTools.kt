package com.dallycontrol.agent.kiosk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.DeviceAction
import com.dallycontrol.proto.ProtocolJson
import kotlinx.serialization.Serializable

/**
 * The phone's letter size (Android's font scale, applied at once to every app). Android lets an app change it only with
 * "Modificar ajustes del sistema" (WRITE_SETTINGS): the cable/Windows enrollment grants it; otherwise the person
 * allows it once ([permissionIntent]). The display size ("zoom") cannot be changed by an MDM on current Android: its
 * screen is opened instead, to adjust in person or with remote control.
 */
object DisplayTools {
    /** The steps offered in the kiosk and the console. */
    val STEPS = listOf(0.85f, 1.0f, 1.15f, 1.3f)

    fun canChangeFont(context: Context): Boolean = Settings.System.canWrite(context)

    fun fontScale(context: Context): Float =
        runCatching { Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE) }.getOrDefault(1f)

    /** @return null when done, else why not. */
    fun setFontScale(context: Context, scale: Float): String? {
        if (!canChangeFont(context)) return "falta el permiso «Modificar ajustes del sistema» para DallyControl"
        val v = scale.coerceIn(0.8f, 1.5f)
        return runCatching { Settings.System.putFloat(context.contentResolver, Settings.System.FONT_SCALE, v); null }
            .getOrElse { it.message ?: "no se pudo cambiar el tamaño de letra" }
    }

    /** One step smaller or bigger than now. */
    fun step(context: Context, delta: Int): String? {
        val now = fontScale(context)
        val i = STEPS.indexOfFirst { it >= now - 0.01f }.let { if (it < 0) STEPS.lastIndex else it }
        return setFontScale(context, STEPS[(i + delta).coerceIn(0, STEPS.lastIndex)])
    }

    fun permissionIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))

    fun displaySizeIntent() = Intent(Settings.ACTION_DISPLAY_SETTINGS)
}

/**
 * `device.display` — `{fontScale?}` sets the letter size; `{open: "display"}` opens the display settings (display size)
 * for a few minutes, to adjust in person or with remote control; `{open: "permission"}` opens "Modificar ajustes del
 * sistema" for DallyControl.
 */
class DisplayHandler(private val context: Context) : CommandHandler {
    override val type: String = DeviceAction.DISPLAY

    @Serializable
    private data class Payload(val fontScale: Float? = null, val open: String? = null)

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val p = command.payload?.let { runCatching { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }.getOrNull() }
            ?: return CommandResults.failed(command, "invalid payload")
        p.fontScale?.let { f ->
            val err = DisplayTools.setFontScale(context, f)
            return if (err == null) CommandResults.done(command, "letra al ${(f * 100).toInt()} %") else CommandResults.failed(command, err)
        }
        val intent = when (p.open) {
            "display" -> DisplayTools.displaySizeIntent()
            "permission" -> {
                if (DisplayTools.canChangeFont(context)) return CommandResults.done(command, "el permiso ya está activo")
                DisplayTools.permissionIntent(context)
            }
            else -> return CommandResults.failed(command, "nothing to do")
        }
        val err = TimedAllow.open(context, intent, 5 * 60_000L)
        return if (err == null) CommandResults.done(command, "abierto en el teléfono") else CommandResults.failed(command, err)
    }
}
