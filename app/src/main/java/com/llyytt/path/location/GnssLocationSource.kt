package com.llyytt.path.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.llyytt.path.core.geo.Wgs84
import com.llyytt.path.core.rules.Tuning
import com.llyytt.path.data.db.PointSource

/**
 * 系统 GNSS 定位。MVP 唯一的定位实现。
 *
 * 为什么先用系统 GNSS 而不是高德定位 SDK：
 * 1. 系统 GNSS 输出的就是 WGS84，入库零转换、零误差。
 * 2. 不引入第二个厂商 SDK，体积和隐私面都小。
 * 3. 不依赖 GMS（国行机没有 FusedLocationProvider）。
 * 代价是冷启动首次定位慢、室内基本没点。
 */
class GnssLocationSource(context: Context) : LocationSource {

    private val appContext = context.applicationContext
    private val manager: LocationManager? =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val handler = Handler(Looper.getMainLooper())

    private var onFix: ((LocationFix) -> Unit)? = null
    private var onError: ((LocationError) -> Unit)? = null
    private var running = false
    private var gotAnyFix = false
    private var currentIntervalMs = 0L

    override val isRunning: Boolean
        get() = running

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            gotAnyFix = true
            val fix = location.toFix()
            adaptInterval(location)
            onFix?.invoke(fix)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) {
            onError?.invoke(LocationError.PROVIDER_DISABLED)
            stop()
        }
    }

    private val timeoutRunnable = Runnable {
        if (running && !gotAnyFix) {
            onError?.invoke(LocationError.TIMEOUT)
        }
    }

    override fun start(onFix: (LocationFix) -> Unit, onError: (LocationError) -> Unit) {
        if (running) return
        val m = manager
        if (m == null) {
            onError(LocationError.UNKNOWN)
            return
        }
        if (!hasPermission()) {
            onError(LocationError.PERMISSION_DENIED)
            return
        }
        if (!m.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            onError(LocationError.PROVIDER_DISABLED)
            return
        }

        this.onFix = onFix
        this.onError = onError
        this.gotAnyFix = false
        this.running = true

        // 先给一个历史位置（如果有），让 UI 立刻有东西显示
        runCatching { m.getLastKnownLocation(LocationManager.GPS_PROVIDER) }
            .getOrNull()
            ?.let { onFix(it.toFix()) }

        requestUpdates(Tuning.Sampling.NORMAL_INTERVAL_MS)
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
    }

    override fun stop() {
        running = false
        onFix = null
        onError = null
        handler.removeCallbacks(timeoutRunnable)
        runCatching { manager?.removeUpdates(listener) }
    }

    private fun adaptInterval(location: Location) {
        val speed = if (location.hasSpeed()) location.speed else null
        val want = intervalFor(speed)
        if (want != currentIntervalMs) requestUpdates(want)
    }

    private fun intervalFor(speedMps: Float?): Long = when {
        speedMps == null -> Tuning.Sampling.NORMAL_INTERVAL_MS
        speedMps >= Tuning.Sampling.FAST_SPEED_MPS -> Tuning.Sampling.FAST_INTERVAL_MS
        speedMps >= Tuning.Sampling.WALK_SPEED_MPS -> Tuning.Sampling.NORMAL_INTERVAL_MS
        else -> Tuning.Sampling.SLOW_INTERVAL_MS
    }

    private fun requestUpdates(intervalMs: Long) {
        val m = manager ?: return
        if (!hasPermission()) return
        currentIntervalMs = intervalMs
        runCatching {
            m.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                intervalMs,
                0f,
                listener,
                Looper.getMainLooper(),
            )
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun Location.toFix(): LocationFix = LocationFix(
        point = Wgs84(latitude, longitude),
        timeMillis = time,
        accuracyMeters = if (hasAccuracy()) accuracy else -1f,
        speedMps = if (hasSpeed()) speed else null,
        bearingDeg = if (hasBearing()) bearing else null,
        altitudeM = if (hasAltitude()) altitude else null,
        source = PointSource.GNSS,
    )

    companion object {
        /** 超过这个时间还没有第一个点，就报 TIMEOUT */
        const val TIMEOUT_MS = 30_000L
    }
}
