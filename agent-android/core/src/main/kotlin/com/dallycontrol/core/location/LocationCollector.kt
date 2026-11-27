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
        val reuse = active && FixPolicy.freshEnough(known?.time, now)
        if (reuse) {
            val from = known?.provider ?: "?"
            val trace = FixPolicy.Trace(now, emptyList(), modeStore.fixTimeoutSec(), emptyList(), from)
            FixPolicy.lastTrace = trace
            android.util.Log.i(TAG, "fresh fix: ${trace.summary()}")
        }
        val fresh = if (active && !reuse) currentFix(lm, now) else null
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
        val available = runCatching { lm.allProviders }.getOrDefault(emptyList())
        // Only providers the device really has AND has enabled are asked: without Google Play services there is no
        // fused provider and GPS goes first, with no time spent waiting for something that cannot answer.
        // …and a fused provider that just stayed silent while GPS answered is left out for a while.
        val providers = FixPolicy.providers(available) { lm.isProviderEnabled(it) }
            .filterNot { it == FixPolicy.FUSED && FixPolicy.skipFused(now, fusedSilentAt) }
        val total = FixPolicy.timeoutSec(modeStore.fixTimeoutSec(), now, lastTimeoutAt)
        val attempts = ArrayList<FixPolicy.Attempt>()
        var fix: Location? = null
        val deadline = android.os.SystemClock.elapsedRealtime() + total * MS_PER_SEC
        var i = 0
        while (fix == null && i < providers.size) {
            val started = android.os.SystemClock.elapsedRealtime()
            val left = deadline - started
            if (left > 0) {
                val wait = FixPolicy.waitMs(providers[i], i == providers.lastIndex, left)
                fix = singleFix(lm, providers[i], wait)
                val took = android.os.SystemClock.elapsedRealtime() - started
                attempts += FixPolicy.Attempt(providers[i], wait, took, fix != null)
            }
            i++
        }
        val trace = FixPolicy.Trace(now, available, total, attempts)
        FixPolicy.lastTrace = trace
        android.util.Log.i(TAG, "fresh fix: ${trace.summary()} (disponibles=$available, espera=${total}s)")
        lastTimeoutAt = if (fix != null) 0 else now
        val fusedTried = attempts.firstOrNull { it.provider == FixPolicy.FUSED }
        if (fusedTried != null) fusedSilentAt = if (!fusedTried.answered && fix != null) now else 0
        return fix
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

    /** When fused last gave nothing while another provider did (0 = it answered, or was never the problem). */
    @Volatile private var fusedSilentAt = 0L

    private companion object {
        const val MS_PER_SEC = 1000L
        const val TAG = "LocationCollector"
    }
}
