@file:Suppress("DEPRECATION") // android.hardware.Camera: one simple API that works from Android 6 to today

package com.dallycontrol.agent.provisioning

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Camera
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.dallycontrol.agent.R
import com.dallycontrol.agent.admin.AdminReceiver
import com.dallycontrol.core.enroll.EnrollQr
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** One camera preview frame (NV21: the luminance plane comes first) to the text of the QR in it, or null. */
object QrFrameDecoder {
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    fun decode(reader: QRCodeReader, data: ByteArray, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        // Two ways of telling dark from light: the local one copes with shadows and glare on a monitor, the global
        // one with frames the local one misreads. Whichever reads first wins; the next frame gets both again.
        return listOf({ BinaryBitmap(HybridBinarizer(source)) }, { BinaryBitmap(GlobalHistogramBinarizer(source)) })
            .firstNotNullOfOrNull { bitmap ->
                runCatching { reader.decode(bitmap(), hints).text }.getOrNull().also { reader.reset() }
            }
    }
}

/**
 * Scans the console's enrollment QR from inside the agent. For a phone that is already Device Owner but never
 * received the QR's payload (the setup wizard was abandoned or did not finish the provisioning): instead of another
 * factory reset, the technician opens DallyControl and points the phone at the same QR.
 */
class EnrollScanActivity : Activity(), SurfaceHolder.Callback, Camera.PreviewCallback {

    private var camera: Camera? = null
    private var decoder: HandlerThread? = null
    private var decodeHandler: Handler? = null
    private val main = Handler(Looper.getMainLooper())
    private val reader = QRCodeReader()
    private lateinit var hint: TextView
    @Volatile private var done = false
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val surface = SurfaceView(this).apply { holder.addCallback(this@EnrollScanActivity) }
        hint = TextView(this).apply {
            setText(R.string.enroll_scan_hint)
            setTextColor(Color.WHITE)
            setBackgroundColor(HINT_BACKGROUND)
            textSize = HINT_SP
            gravity = Gravity.CENTER
            setPadding(PAD, PAD, PAD, PAD)
            contentDescription = "enroll-scan-hint"
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(surface, ViewGroup.LayoutParams(MATCH, MATCH))
                addView(hint, FrameLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            },
        )
        ensureCameraPermission()
    }

    /** A Device Owner grants itself the camera; otherwise the person is asked once. */
    private fun ensureCameraPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || hasCamera()) return
        runCatching {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isDeviceOwnerApp(packageName)) {
                dpm.setPermissionGrantState(
                    AdminReceiver.componentName(this), packageName, Manifest.permission.CAMERA,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }
        }
        if (!hasCamera()) requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
    }

    private fun hasCamera() = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (requestCode == REQUEST_CAMERA && hasCamera()) recreate() else if (requestCode == REQUEST_CAMERA) noCamera()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!hasCamera()) return
        runCatching {
            decoder = HandlerThread("enroll-qr").also { it.start(); decodeHandler = Handler(it.looper) }
            camera = Camera.open().also { cam ->
                cam.setDisplayOrientation(PORTRAIT_DEGREES)
                cam.parameters = cam.parameters.apply {
                    if (supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true) {
                        focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                    }
                }
                cam.setPreviewDisplay(holder)
                cam.setPreviewCallback(this)
                cam.startPreview()
            }
        }.onFailure {
            Log.w(TAG, "camera unavailable: ${it.message}")
            noCamera()
        }
    }

    private fun noCamera() {
        hint.setText(R.string.enroll_scan_no_camera)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) = release()

    override fun onPause() {
        release()
        super.onPause()
    }

    private fun release() {
        runCatching {
            camera?.setPreviewCallback(null)
            camera?.stopPreview()
            camera?.release()
        }
        camera = null
        decoder?.quitSafely()
        decoder = null
        decodeHandler = null
    }

    /** One frame at a time goes to the decoder thread; frames that arrive meanwhile are dropped. */
    override fun onPreviewFrame(data: ByteArray?, cam: Camera?) {
        val size = runCatching { cam?.parameters?.previewSize }.getOrNull()
        if (done || busy || data == null || size == null) return
        busy = true
        val posted = decodeHandler?.post {
            val text = decode(data, size.width, size.height)
            busy = false
            if (text != null) main.post { onText(text) }
        }
        if (posted != true) busy = false
    }

    private fun decode(data: ByteArray, width: Int, height: Int): String? = QrFrameDecoder.decode(reader, data, width, height)

    private fun onText(text: String) {
        if (done) return
        val qr = EnrollQr.parse(text)
        if (qr == null) {
            hint.setText(R.string.enroll_scan_not_ours)
            return
        }
        done = true
        hint.setText(R.string.enroll_sent)
        ProvisioningIntake.acceptScanned(this, qr)
        setResult(RESULT_OK)
        finish()
    }

    private companion object {
        const val TAG = "EnrollScan"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val REQUEST_CAMERA = 41
        const val PORTRAIT_DEGREES = 90
        const val PAD = 36
        const val HINT_SP = 16f
        val HINT_BACKGROUND = Color.parseColor("#B0000000")
    }
}
