package com.llyytt.path.core.rules

import com.llyytt.path.core.geo.Wgs84
import com.llyytt.path.data.db.TrackPointEntity

/**
 * 瞬时速度的取数、平滑、静止判定。
 *
 * 三个明确的边界：
 * 1. 精度差的点**不参与**速度计算（不用它们做样本，也不拿它们算距离/时间）。
 * 2. 显示用平滑值（最近 N 个样本的中位数），地图着色用原始值，两者不绑死。
 * 3. 内部一律 m/s，只有展示层转 km/h。
 */
object SpeedSampler {

    /**
     * 从一次定位里取速度样本。
     *
     * 优先系统 Speed；为 null 时回退到「上一点到当前点的距离 / 时间」。
     * 返回 null 表示这次不产生样本（精度不够、没有上一点、时间差太小）。
     */
    fun sample(
        previousPoint: Wgs84?,
        previousTimeMillis: Long?,
        currentPoint: Wgs84,
        currentTimeMillis: Long,
        systemSpeedMps: Float?,
        accuracyMeters: Float,
    ): Double? {
        if (accuracyMeters > Tuning.Filter.MAX_ACCURACY_M) return null

        if (systemSpeedMps != null && systemSpeedMps >= 0f) {
            return systemSpeedMps.toDouble()
        }

        if (previousPoint == null || previousTimeMillis == null) return null
        val seconds = (currentTimeMillis - previousTimeMillis) / 1000.0
        if (seconds <= 0.2) return null

        return previousPoint.distanceTo(currentPoint) / seconds
    }
}

/**
 * 速度平滑 + 静止判定。有状态，一个行程一个实例。
 *
 * 用中位数而不是平均数：单次 GPS 跳点（比如突然 30 m/s）不会把显示带偏。
 */
class SpeedSmoother(
    private val windowSize: Int = Tuning.SpeedDisplay.WINDOW_SIZE,
    private val stationarySpeedMps: Double = Tuning.SpeedDisplay.STATIONARY_SPEED_MPS,
    private val stationaryHoldMillis: Long = Tuning.SpeedDisplay.STATIONARY_HOLD_MS,
) {

    data class Snapshot(
        val smoothedMps: Double,
        val stationary: Boolean,
    )

    private val window = ArrayDeque<Double>()
    private var belowSinceMillis: Long? = null

    fun add(speedMps: Double, atMillis: Long): Snapshot {
        window.addLast(speedMps)
        while (window.size > windowSize) window.removeFirst()

        val smoothed = median(window)

        // 非对称判定：进入静止要谨慎（连续 5 秒低于阈值），离开静止要灵敏。
        // 只靠中位数退出会有明显延迟——窗口里旧的低速样本会把中位数压住好几十秒，
        // 用户明明已经走起来了，界面还显示「静止」。所以只要本次原始样本达到阈值就立即解除。
        val below = smoothed < stationarySpeedMps && speedMps < stationarySpeedMps
        if (!below) {
            belowSinceMillis = null
        } else if (belowSinceMillis == null) {
            belowSinceMillis = atMillis
        }

        val stationary = below && belowSinceMillis?.let { atMillis - it >= stationaryHoldMillis } == true
        return Snapshot(smoothed, stationary)
    }

    fun reset() {
        window.clear()
        belowSinceMillis = null
    }

    companion object {
        fun median(values: Collection<Double>): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            val n = sorted.size
            return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        }
    }
}

/**
 * 对已存库的行程离线跑一遍同样的平滑，供详情页回放使用。
 *
 * 与实时记录用的是同一个 [SpeedSmoother]，所以两处的「静止」语义一致。
 */
object SpeedSeries {

    data class Sample(
        val smoothedMps: Double?,
        val stationary: Boolean,
    )

    fun smoothed(points: List<TrackPointEntity>): List<Sample> {
        val smoother = SpeedSmoother()
        val out = ArrayList<Sample>(points.size)

        var prev: TrackPointEntity? = null
        for (p in points) {
            if (p.startsNewSegment) {
                // 中断处重新开窗，避免把断点两端的位移当成速度
                smoother.reset()
                prev = null
            }

            val sample = SpeedSampler.sample(
                previousPoint = prev?.let { Wgs84(it.lat, it.lng) },
                previousTimeMillis = prev?.recordedAt,
                currentPoint = Wgs84(p.lat, p.lng),
                currentTimeMillis = p.recordedAt,
                systemSpeedMps = p.speedMps,
                accuracyMeters = p.accuracyM ?: -1f,
            )

            if (sample == null) {
                out.add(Sample(null, false))
            } else {
                val snap = smoother.add(sample, p.recordedAt)
                out.add(Sample(snap.smoothedMps, snap.stationary))
            }
            prev = p
        }
        return out
    }
}
