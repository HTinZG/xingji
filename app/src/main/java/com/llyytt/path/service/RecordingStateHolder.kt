package com.llyytt.path.service

import com.llyytt.path.core.geo.Wgs84
import com.llyytt.path.data.db.TripStatus
import com.llyytt.path.location.LocationError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 记录页的实时状态。服务写，UI 读。
 *
 * 只放「显示用」的派生数据；真值永远在数据库里。
 * 服务被杀后重启时，这些值会从数据库重新算出来，不会丢。
 */
data class RecordingUiState(
    val tripId: Long = 0L,
    val status: TripStatus? = null,
    val startedAt: Long = 0L,
    val distanceMeters: Double = 0.0,
    val activeMillis: Long = 0L,
    val pointCount: Int = 0,
    val lastAccuracyM: Float? = null,
    val lastError: LocationError? = null,
    /** 冷启动首次定位是否已拿到（用于 UI 提示「正在搜星」） */
    val hasFirstFix: Boolean = false,
    /**
     * 最后一次成功入库的定位时间（毫秒）。
     *
     * 用来判断「记录中但定位已经停了」——这是后台服务被系统冻结时唯一的线索：
     * 没有报错、没有回调，只是再也没有点进来。
     */
    val lastFixAt: Long = 0L,
    /** 平滑后的瞬时速度（m/s）。null 表示还没有足够样本。 */
    val speedMps: Double? = null,
    /** 速度持续低于阈值超过 5 秒 */
    val isStationary: Boolean = false,
    /**
     * 本次行程已经记录的点，用于记录页画实时轨迹。
     * 只保留最近若干点（服务层截断），够画图即可，不是全量。
     */
    val liveTrack: List<Wgs84> = emptyList(),
) {
    val isActive: Boolean
        get() = status == TripStatus.RECORDING || status == TripStatus.PAUSED
}

object RecordingStateHolder {

    private val _state = MutableStateFlow(RecordingUiState())

    val state: StateFlow<RecordingUiState> = _state

    fun set(value: RecordingUiState) {
        _state.value = value
    }

    fun reset() {
        _state.value = RecordingUiState()
    }
}
