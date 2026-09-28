package com.mdmesh.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.mdmesh.proto.LocationDto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the device location for telemetry. Passive mode (default) returns the OS's freshest
 * last-known fix across providers — near-zero battery, no active GPS. Active mode requests one fresh
 * fix per call (getCurrentLocation on API 30+, requestSingleUpdate below) with a short timeout,
 * falling back to last-known. Never throws; returns
 * null without a location permission, with location services off, or when no fix is available.
 */
@Singleton
class LocationCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modeStore: LocationModeStore,
) {
    fun collect(): LocationDto? {
        if (!hasPermission()) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val loc = if (modeStore.isActive()) (currentFix(lm) ?: lastKnown(lm)) else lastKnown(lm)
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

    /** A single fresh fix with a short timeout; null on timeout/failure. */
    @SuppressLint("MissingPermission") // gated by collect()'s hasPermission() check
    private fun currentFix(lm: LocationManager): Location? {
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return legacySingleFix(lm, provider)
        return runCatching {
            val latch = CountDownLatch(1)
            val ref = AtomicReference<Location?>()
            val cancel = CancellationSignal()
            lm.getCurrentLocation(provider, cancel, context.mainExecutor) { loc ->
                ref.set(loc); latch.countDown()
            }
            if (!latch.await(FIX_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                cancel.cancel()
                null
            } else {
                ref.get()
            }
        }.getOrNull()
    }

    /**
     * API 23-29: no getCurrentLocation, so ask for one update on a background looper. Without this,
     * "active" mode on Android 6-10 only ever returned the last-known fix, which a freshly provisioned
     * device does not have, so no location ever reached the server.
     */
    @Suppress("DEPRECATION") // requestSingleUpdate is the pre-R single-fix API
    @SuppressLint("MissingPermission")
    private fun legacySingleFix(lm: LocationManager, provider: String): Location? = runCatching {
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
            if (latch.await(FIX_TIMEOUT_SEC, TimeUnit.SECONDS)) ref.get() else null
        } finally {
            runCatching { lm.removeUpdates(listener) }
            thread.quitSafely()
        }
    }.getOrNull()

    private companion object { const val FIX_TIMEOUT_SEC = 5L }
}
