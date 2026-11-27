package com.dallycontrol.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.dallycontrol.proto.LocationDto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the device location for telemetry. Passive mode (default) returns the OS's freshest
 * last-known fix across providers — near-zero battery, no active GPS. Active mode requests one fresh
 * fix per call (getCurrentLocation on API 30+, requestSingleUpdate below), waiting up to the configured
 * time (25 s by default, see [FixPolicy]) and falling back to last-known. The fused provider of Google Play
 * services is preferred when the device has it; without GMS it uses GPS, then network. Never throws; returns
 * null without a location permission, with location services off, or when no fix is available.
 */
@Singleton
class LocationCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modeStore: LocationModeStore,
) {
    /** @param forceFresh ask for a fresh fix even in passive mode (the periodic trail). */
    fun collect(forceFresh: Boolean = false): LocationDto? {
        if (!hasPermission()) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val active = forceFresh || modeStore.isActive()
        val known = lastKnown(lm)
        val now = System.currentTimeMillis()
        // A fix taken seconds ago (e.g. by the trail loop) is as good as a new one: do not wait again.
        val fresh = if (active && !FixPolicy.freshEnough(known?.time, now)) currentFix(lm, now) else null
        val loc = listOfNotNull(fresh, known).maxByOrNull { it.time }
        android.util.Log.d(TAG, "collect active=$active fresh=${fresh != null} loc=${loc?.time}")
        return loc?.let {
            LocationDto(
                lat = it.latitude,
                lon = it.longitude,
                accuracyM = if (it.hasAccuracy()) it.accuracy else null,
                provider = it.provider,
                capturedAt = it.time,
            )
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Freshest last-known location across all enabled providers (cheap, no active GPS). */
    @SuppressLint("MissingPermission") // gated by collect()'s hasPermission() check
    private fun lastKnown(lm: LocationManager): Location? = runCatching {
        lm.allProviders
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }.getOrNull()

    /**
     * A single fresh fix: the best provider is asked first and, if it gives nothing in its share of the wait, the
     * next one gets the rest (GPS indoors never answers; network usually does). null on timeout/failure.
     */
    private fun currentFix(lm: LocationManager, now: Long): Location? {
        val providers = FixPolicy.providers(runCatching { lm.allProviders }.getOrDefault(emptyList())) { lm.isProviderEnabled(it) }
        if (providers.isEmpty()) return null
        val total = FixPolicy.timeoutSec(modeStore.fixTimeoutSec(), now, lastTimeoutAt)
        val deadline = android.os.SystemClock.elapsedRealtime() + total * 1000L
        for ((i, provider) in providers.withIndex()) {
            val left = deadline - android.os.SystemClock.elapsedRealtime()
            if (left <= 0) break
            // The last provider may use everything that is left; earlier ones leave a share for the fallbacks.
            val wait = if (i == providers.lastIndex) left else left * 2 / 3
            val fix = singleFix(lm, provider, wait)
            if (fix != null) {
                lastTimeoutAt = 0
                return fix
            }
        }
        lastTimeoutAt = now
        return null
    }

    @SuppressLint("MissingPermission") // gated by collect()'s hasPermission() check
    private fun singleFix(lm: LocationManager, provider: String, waitMs: Long): Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return legacySingleFix(lm, provider, waitMs)
        // The callback runs on a dedicated thread, never the main looper: collect() blocks on the latch, and
        // when it ran on the main thread a main-executor callback could not be delivered until the wait
        // timed out, so active mode on API 30+ never produced a fix (and stalled that check-in).
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        return runCatching {
            val latch = CountDownLatch(1)
            val ref = AtomicReference<Location?>()
            val cancel = CancellationSignal()
            lm.getCurrentLocation(provider, cancel, executor) { loc ->
                ref.set(loc); latch.countDown()
            }
            if (!latch.await(waitMs, TimeUnit.MILLISECONDS)) {
                cancel.cancel()
                null
            } else {
                ref.get()
            }
        }.getOrNull().also { executor.shutdown() }
    }

    /**
     * API 23-29: no getCurrentLocation, so ask for one update on a background looper. Without this,
     * "active" mode on Android 6-10 only ever returned the last-known fix, which a freshly provisioned
     * device does not have, so no location ever reached the server.
     */
    @Suppress("DEPRECATION") // requestSingleUpdate is the pre-R single-fix API
    @SuppressLint("MissingPermission")
    private fun legacySingleFix(lm: LocationManager, provider: String, waitMs: Long): Location? = runCatching {
        val latch = CountDownLatch(1)
        val ref = AtomicReference<Location?>()
        val thread = android.os.HandlerThread("mdm-location-fix").apply { start() }
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: Location) {
                ref.set(location); latch.countDown()
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        try {
            lm.requestSingleUpdate(provider, listener, thread.looper)
            if (latch.await(waitMs, TimeUnit.MILLISECONDS)) ref.get() else null
        } finally {
            runCatching { lm.removeUpdates(listener) }
            thread.quitSafely()
        }
    }.getOrNull()

    /** When the last wait for a fresh fix ended with nothing (0 = the last one worked). */
    @Volatile private var lastTimeoutAt = 0L

    private companion object {
        const val TAG = "LocationCollector"
    }
}
