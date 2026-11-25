package com.llyytt.path.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
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
    private var gotGpsFix = false
    private var currentIntervalMs = 0L

    override val isRunning: Boolean
        get() = running

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            gotAnyFix = true
            if (location.provider == LocationManager.GPS_PROVIDER) gotGpsFix = true
            val fix = location.toFix()
            adaptInterval(location)
            onFix?.invoke(fix)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) {
            // 只有 GPS 被关掉才算致命；网络定位被关不影响继续等 GPS。
            if (provider == LocationManager.GPS_PROVIDER) {
                onError?.invoke(LocationError.PROVIDER_DISABLED)
                stop()
            }
        }
    }

    /**
     * 网络定位兜底。只在还没拿到任何 GPS 点之前转发。
     *
     * 为什么需要：室内 GPS 完全定不上，但系统网络定位通常能给出几十米精度的位置。
     * 之前只监听 GPS，室内就是"永远 0 个点"，用户看到的是"搜不到星"。
     * 为什么不一直用：网络定位精度 30~2000 米，一直混进来会把轨迹和距离算歪。
     */
    private val networkListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (gotGpsFix) return
            if (location.accuracy > Tuning.Filter.MAX_ACCURACY_M) return
            gotAnyFix = true
            onFix?.invoke(location.toFix(source = PointSource.NETWORK))
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    private val timeoutRunnable = Runnable {
        if (running && !gotAnyFix) {
            // 超时的真实原因可能是两种，必须区分：
            // - 系统定位总开关关着 → 用户需要去打开
            // - 开着但没信号 → 用户需要去室外
            // 报错说错了，用户会一直以为是"室内没信号"（实际踩过这个坑）。
            onError?.invoke(
                if (!isSystemLocationEnabled()) LocationError.PROVIDER_DISABLED
                else LocationError.TIMEOUT,
            )
        }
    }

    /**
     * 系统定位总开关是否打开。
     *
     * 注意：不能用 isProviderEnabled(GPS_PROVIDER) 判断。
     * 在部分机型（实测小米/HyperOS）上，即使定位总开关关闭，
     * isProviderEnabled 仍然返回 true —— 那样永远查不出"定位没开"。
     */
    private fun isSystemLocationEnabled(): Boolean {
        val m = manager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { m.isLocationEnabled }.getOrDefault(false)
        } else {
            @Suppress("DEPRECATION")
            runCatching { m.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        }
    }

    override fun start(onFix: (LocationFix) -> Unit, onError: (LocationError) -> Unit) {
        if (running) return
        val m = manager
        if (m == null) {
            onError(LocationError.UNKNOWN)
            return
        }

        this.onFix = onFix
        this.onError = onError
        this.gotAnyFix = false
        this.gotGpsFix = false
        this.running = true

        if (!begin(m)) {
            // 定位没开 / 没权限：先报错，同时每 3 秒重试一次。
            // 用户去设置里打开定位后回到 App，不用手动"结束再开始"，会自动接上。
            handler.postDelayed(retryRunnable, RETRY_MS)
            return
        }
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
    }

    /** 真正开始采样。前置条件不满足时返回 false，并已通过 onError 报出原因。 */
    private fun begin(m: LocationManager): Boolean {
        if (!hasPermission()) {
            onError?.invoke(LocationError.PERMISSION_DENIED)
            return false
        }
        if (!isSystemLocationEnabled()) {
            onError?.invoke(LocationError.PROVIDER_DISABLED)
            return false
        }
        if (!m.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            onError?.invoke(LocationError.PROVIDER_DISABLED)
            return false
        }

        // 先给一个历史位置（如果有），让 UI 立刻有东西显示
        runCatching { m.getLastKnownLocation(LocationManager.GPS_PROVIDER) }
            .getOrNull()
            ?.let { onFix?.invoke(it.toFix()) }

        requestUpdates(Tuning.Sampling.NORMAL_INTERVAL_MS)
        requestNetworkUpdates(m)
        return true
    }

    /** 网络定位兜底：拿不到 GPS 时至少有个粗略位置。不可用就静默跳过。 */
    private fun requestNetworkUpdates(m: LocationManager) {
        val provider = when {
            runCatching { m.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) ->
                LocationManager.NETWORK_PROVIDER
            else -> return
        }
        runCatching {
            m.requestLocationUpdates(provider, NETWORK_INTERVAL_MS, 0f, networkListener, Looper.getMainLooper())
        }
    }

    private val retryRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val m = manager
            if (m != null && begin(m)) {
                gotAnyFix = false
                handler.removeCallbacks(timeoutRunnable)
                handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
            } else {
                handler.postDelayed(this, RETRY_MS)
            }
        }
    }

    override fun stop() {
        running = false
        onFix = null
        onError = null
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(retryRunnable)
        runCatching { manager?.removeUpdates(listener) }
        runCatching { manager?.removeUpdates(networkListener) }
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

    private fun Location.toFix(source: PointSource = PointSource.GNSS): LocationFix = LocationFix(
        point = Wgs84(latitude, longitude),
        timeMillis = time,
        accuracyMeters = if (hasAccuracy()) accuracy else -1f,
        speedMps = if (hasSpeed()) speed else null,
        bearingDeg = if (hasBearing()) bearing else null,
        altitudeM = if (hasAltitude()) altitude else null,
        source = source,
    )

    companion object {
        /** 超过这个时间还没有第一个点，就报 TIMEOUT */
        const val TIMEOUT_MS = 30_000L

        /** 定位没开时的重试间隔 */
        const val RETRY_MS = 3_000L

        /** 网络定位的刷新间隔（粗定位，不需要太频繁） */
        const val NETWORK_INTERVAL_MS = 10_000L
    }
}
