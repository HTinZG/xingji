package com.llyytt.path.location

import com.llyytt.path.core.geo.Wgs84
import com.llyytt.path.data.db.PointSource

/** 定位失败原因。冷启动实测出问题时，靠它区分「没信号」还是「provider 没开」。 */
enum class LocationError {
    /** 没给定位权限 */
    PERMISSION_DENIED,

    /** 系统定位开关关了，或 GPS provider 被禁用 */
    PROVIDER_DISABLED,

    /** 已经开始采样但迟迟拿不到点（默认 30 秒） */
    TIMEOUT,

    UNKNOWN,
}

/**
 * 一次定位结果。坐标永远是 WGS84。
 *
 * 将来接入高德定位 SDK（输出 GCJ-02）时，必须在那个实现内部做
 * GCJ-02 -> WGS84 逆变换，并把 source 标成 AMAP_LOCATION。
 */
data class LocationFix(
    val point: Wgs84,
    val timeMillis: Long,
    val accuracyMeters: Float,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val altitudeM: Double?,
    val source: PointSource = PointSource.GNSS,
)

/**
 * 定位来源抽象，和 MapEngine 平级。
 *
 * 用回调而不是 Flow：这个接口的生命周期由前台服务持有，
 * 用 Flow 就要么每次 collect 重复注册监听、要么自己持 CoroutineScope 并负责取消。
 * 回调与系统的 requestLocationUpdates 一一对应，start/stop 显式可控。
 * UI 需要 Flow 时在服务层用 callbackFlow 包一层。
 */
interface LocationSource {

    val isRunning: Boolean

    fun start(onFix: (LocationFix) -> Unit, onError: (LocationError) -> Unit)

    fun stop()
}
