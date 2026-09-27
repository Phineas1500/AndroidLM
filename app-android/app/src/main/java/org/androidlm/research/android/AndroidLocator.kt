package org.androidlm.research.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.androidlm.research.Locator
import kotlin.coroutines.resume

/**
 * The phone's position for "near me" questions, from the platform LocationManager (no Google Play
 * Services). GPS works without any network, so this stays offline: a fix from the last
 * [MAX_AGE_MS] is used as it is, otherwise a fresh one is requested, GPS first, for up to
 * [FIX_TIMEOUT_MS] (a cold GPS start without assistance data can take that long, and longer
 * indoors). Null without location permission, with location off, or with no fix in time.
 */
class AndroidLocator(private val ctx: Context) : Locator {

    override suspend fun here(): Pair<Double, Double>? {
        if (!permitted(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val enabled = try {
            lm.getProviders(true)
        } catch (e: SecurityException) {
            return null
        }
        val now = SystemClock.elapsedRealtimeNanos()
        val recent = enabled.mapNotNull { p ->
            try {
                lm.getLastKnownLocation(p)
            } catch (e: SecurityException) {
                null
            }
        }.filter { now - it.elapsedRealtimeNanos < MAX_AGE_MS * 1_000_000 }.maxByOrNull { it.elapsedRealtimeNanos }
        if (recent != null) return recent.latitude to recent.longitude
        val provider = PROVIDERS.firstOrNull { it in enabled } ?: return null
        return withTimeoutOrNull(FIX_TIMEOUT_MS) { fix(lm, provider) }?.let { it.latitude to it.longitude }
    }

    private suspend fun fix(lm: LocationManager, provider: String): Location? = suspendCancellableCoroutine { cont ->
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                lm.getCurrentLocation(provider, cancel, ctx.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (cont.isActive) cont.resume(location)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            if (cont.isActive) cont.resume(null)
        }
    }

    companion object {
        const val MAX_AGE_MS = 30L * 60 * 1000
        const val FIX_TIMEOUT_MS = 45_000L
        // GPS first: it needs no network. The network provider only helps where it can work offline.
        private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, "fused", LocationManager.NETWORK_PROVIDER)

        val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        fun permitted(ctx: Context): Boolean = PERMISSIONS.any {
            ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
