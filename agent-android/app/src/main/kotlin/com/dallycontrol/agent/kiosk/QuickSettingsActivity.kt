package com.dallycontrol.agent.kiosk

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.dallycontrol.agent.R
import com.dallycontrol.agent.admin.AdminReceiver
import com.dallycontrol.agent.policy.WifiNetworks
import kotlin.concurrent.thread

/**
 * Quick settings inside the kiosk. Lock task keeps the system Quick Settings panel closed even when the status bar is
 * allowed, so the agent offers its own: screen brightness, volume, Wi-Fi (on/off, join a network) and Bluetooth. All of
 * it goes through Device-Owner APIs, so nothing opens the system Settings app (no way out of the kiosk).
 */
class QuickSettingsActivity : ComponentActivity() {
    private val dpm by lazy { getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
    private val admin by lazy { AdminReceiver.componentName(this) }
    private val wifi by lazy { WifiNetworks(this) }
    private lateinit var wifiState: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) grant(Manifest.permission.BLUETOOTH_CONNECT)
        setContentView(build())
    }

    private fun build(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
            setBackgroundColor(INK)
        }
        col.addView(text(getString(R.string.qs_title), 22f, TEXT, bold = true))
        col.addView(space())

        // Brightness: DPM system setting (API 28+), manual mode so it sticks.
        col.addView(label(getString(R.string.qs_brightness)))
        col.addView(SeekBar(this).apply {
            max = 255
            progress = runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
            isEnabled = Build.VERSION.SDK_INT >= 28
            contentDescription = "qs-brightness"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                    if (!fromUser || Build.VERSION.SDK_INT < 28) return
                    runCatching {
                        dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL.toString())
                        dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS, v.coerceIn(10, 255).toString())
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })
        col.addView(space())

        // Volume: media and ringer.
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for ((labelRes, stream) in listOf(R.string.qs_volume_media to AudioManager.STREAM_MUSIC, R.string.qs_volume_ring to AudioManager.STREAM_RING)) {
            col.addView(label(getString(labelRes)))
            col.addView(SeekBar(this).apply {
                max = audio.getStreamMaxVolume(stream)
                progress = audio.getStreamVolume(stream)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                        if (fromUser) runCatching { audio.setStreamVolume(stream, v, 0) }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            })
        }
        col.addView(space())

        // Wi-Fi.
        col.addView(label("WI-FI"))
        wifiState = text("", 14f, MUTED)
        col.addView(toggle(getString(R.string.qs_wifi), wifi.isEnabled(), "qs-wifi") { on -> wifi.setEnabled(on); refreshWifi() })
        col.addView(wifiState)
        col.addView(Button(this).apply {
            text = getString(R.string.qs_wifi_pick)
            contentDescription = "qs-wifi-pick"
            setOnClickListener { pickNetwork() }
        })
        refreshWifi()
        col.addView(space())

        // Bluetooth.
        col.addView(label("BLUETOOTH"))
        val bt = runCatching { (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter }.getOrNull()
        if (bt != null) {
            @SuppressLint("MissingPermission")
            val on = runCatching { bt.isEnabled }.getOrDefault(false)
            col.addView(toggle(getString(R.string.qs_bluetooth), on, "qs-bluetooth") { want ->
                @Suppress("DEPRECATION", "MissingPermission")
                runCatching { if (want) bt.enable() else bt.disable() }
            })
        } else {
            col.addView(text(getString(R.string.qs_no_bluetooth), 14f, MUTED))
        }
        col.addView(space())

        // Anti-theft: switching off or restarting asks for the policy's PIN.
        if (AntiTheft.enabled(this)) {
            col.addView(label(getString(R.string.at_title)))
            col.addView(text(getString(R.string.at_help), 13f, MUTED))
            col.addView(Button(this).apply {
                text = getString(R.string.at_button)
                contentDescription = "qs-power"
                setOnClickListener { askPowerPin() }
            })
            col.addView(space())
        }
        // (Sharing data is an icon on the kiosk home.)
        // Free space: Android's own "clear every app's cache" confirmation (Android 11+).
        if (Build.VERSION.SDK_INT >= 30) {
            col.addView(label(getString(R.string.cache_title)))
            col.addView(Button(this).apply {
                text = getString(R.string.cache_button)
                contentDescription = "qs-clear-cache"
                setOnClickListener {
                    com.dallycontrol.agent.storage.CacheCleaner.open(this@QuickSettingsActivity)
                        ?.let { android.widget.Toast.makeText(this@QuickSettingsActivity, getString(R.string.cache_unavailable), android.widget.Toast.LENGTH_LONG).show() }
                }
            })
            col.addView(space())
        }
        // Split screen from Recents: Android 11 and older refuse it while the phone is locked to the kiosk.
        if (Build.VERSION.SDK_INT >= 31) {
            col.addView(label(getString(R.string.split_title)))
            col.addView(text(getString(R.string.split_help), 13f, MUTED))
            col.addView(space())
        }

        col.addView(Button(this).apply {
            text = getString(R.string.qs_close)
            setOnClickListener { finish() }
        })
        return ScrollView(this).apply {
            setBackgroundColor(INK)
            addView(col)
        }
    }

    private fun askPowerPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.at_pin)
            contentDescription = "qs-power-pin"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.at_button)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (!AntiTheft.pinMatches(this, input.text.toString())) {
                    android.widget.Toast.makeText(this, R.string.at_wrong, android.widget.Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.at_button)
                    .setItems(arrayOf(getString(R.string.at_restart), getString(R.string.at_poweroff))) { _, which ->
                        if (which == 0) AntiTheft.reboot(this) else {
                            AntiTheft.allowPowerMenu(this)
                            android.widget.Toast.makeText(this, R.string.at_poweroff_hint, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                    .show()
            }
            .show()
    }

    private fun refreshWifi() {
        val ssid = wifi.connectedSsid()
        wifiState.text = when {
            !wifi.isEnabled() -> getString(R.string.qs_wifi_off)
            ssid != null -> getString(R.string.qs_wifi_connected, ssid)
            else -> getString(R.string.qs_wifi_none)
        }
    }

    private fun pickNetwork() {
        thread {
            val list = wifi.nearby()
            runOnUiThread {
                if (list.isEmpty()) {
                    AlertDialog.Builder(this).setMessage(R.string.qs_wifi_empty).setPositiveButton(android.R.string.ok, null).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.qs_wifi_pick)
                    .setItems(list.map { (s, locked) -> if (locked) "🔒 $s" else s }.toTypedArray()) { _, i ->
                        val (ssid, locked) = list[i]
                        if (!locked) join(ssid, null, "NONE") else askPassword(ssid)
                    }
                    .show()
            }
        }
    }

    private fun askPassword(ssid: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.qs_wifi_password)
        }
        AlertDialog.Builder(this)
            .setTitle(ssid)
            .setView(input)
            .setPositiveButton(R.string.qs_wifi_connect) { _, _ -> join(ssid, input.text.toString(), "WPA") }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun join(ssid: String, password: String?, security: String) {
        thread {
            val err = wifi.add(ssid, password, security)
            runOnUiThread {
                wifiState.text = err ?: getString(R.string.qs_wifi_joining, ssid)
                wifiState.postDelayed({ refreshWifi() }, 6_000)
            }
        }
    }

    private fun grant(permission: String) {
        runCatching {
            dpm.setPermissionGrantState(admin, packageName, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }
    }

    private fun toggle(title: String, on: Boolean, desc: String, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            text = title
            isChecked = on
            contentDescription = desc
            setTextColor(TEXT)
            textSize = 16f
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }

    private fun label(s: String) = text(s.uppercase(), 11f, MUTED).apply { setPadding(0, dp(8), 0, dp(4)) }

    private fun space() = TextView(this).apply { height = dp(12) }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.START
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        val INK = Color.parseColor("#0E1117")
        val TEXT = Color.parseColor("#E8EEF4")
        val MUTED = Color.parseColor("#8693A4")
    }
}
