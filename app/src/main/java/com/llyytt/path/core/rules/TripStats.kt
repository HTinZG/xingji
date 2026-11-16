package com.llyytt.path.core.rules

import com.llyytt.path.core.geo.Wgs84
import com.llyytt.path.data.db.TrackPointEntity
import com.llyytt.path.data.db.TripEntity

data class TripStats(
    val distanceMeters: Double,
    val activeMillis: Long,
    val maxSpeedMps: Double,
    val avgSpeedMps: Double,
    val pointCount: Int,
    /** 中断次数（暂停 / 丢信号） */
    val gapCount: Int,
)

/**
 * 行程统计。全部实时算，不读缓存字段。
 *
 * 距离：跳过 startsNewSegment 的点对——中断两端不能连成直线算距离。
 * 时长：走 DurationCalc，按暂停记录扣。
 * 速度：优先用系统给的点速度；没有就用相邻点算，但跳过中断处。
 */
object TripStatsCalculator {

    fun compute(
        trip: TripEntity,
        points: List<TrackPointEntity>,
        pauses: List<Pair<Long, Long?>>,
        now: Long,
    ): TripStats {
        var distance = 0.0
        var maxSpeed = 0.0
        var gapCount = 0

        var prev: TrackPointEntity? = null
        for (p in points) {
            if (p.startsNewSegment) gapCount++
            val last = prev
            if (last != null && !p.startsNewSegment) {
                val d = Wgs84(last.lat, last.lng).distanceTo(Wgs84(p.lat, p.lng))
                distance += d
                val dtSeconds = (p.recordedAt - last.recordedAt) / 1000.0
                if (dtSeconds > 0.5) {
                    val computed = d / dtSeconds
                    if (computed in 0.0..40.0 && computed > maxSpeed) maxSpeed = computed
                }
            }
            p.speedMps?.let { if (it > maxSpeed && it < 40f) maxSpeed = it.toDouble() }
            prev = p
        }

        val active = DurationCalc.activeMillis(
            startedAt = trip.startedAt,
            endedAt = trip.endedAt,
            status = trip.status,
            pauses = pauses,
            now = now,
        )

        val avg = if (active > 1000) distance / (active / 1000.0) else 0.0

        return TripStats(
            distanceMeters = distance,
            activeMillis = active,
            maxSpeedMps = maxSpeed,
            avgSpeedMps = avg,
            pointCount = points.size,
            gapCount = gapCount,
        )
    }
}
