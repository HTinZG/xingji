package com.llyytt.path.feature.trips

import com.llyytt.path.core.geo.PolylineSimplifier
import com.llyytt.path.core.geo.Wgs84
import com.llyytt.path.core.rules.Tuning
import com.llyytt.path.data.db.TrackPointEntity
import com.llyytt.path.data.db.TransportMode
import com.llyytt.path.data.db.TripSegmentEntity
import com.llyytt.path.map.MapPolyline

enum class TrackColorMode { SPEED, MODE }

/**
 * 把轨迹点转成地图能画的折线。
 *
 * 两个必须做对的点：
 * 1. startsNewSegment = true 的点之前必须断开，不能连成直线（那等于伪造轨迹）。
 * 2. 画之前抽稀，否则长轨迹会卡；但**只影响呈现，不动原始数据**。
 */
object TrackPainter {

    const val COLOR_WALK = 0xFF2F6B57.toInt()
    const val COLOR_BIKE = 0xFFE08A2E.toInt()
    const val COLOR_CAR = 0xFFC0392B.toInt()
    const val COLOR_SLOW = 0xFF7F8C8D.toInt()
    const val COLOR_UNKNOWN = 0xFF9AA0A6.toInt()

    private const val WIDTH_DP = 6f
    private const val EPSILON_METERS = 2.0

    fun build(
        points: List<TrackPointEntity>,
        segments: List<TripSegmentEntity>,
        mode: TrackColorMode,
    ): List<MapPolyline> {
        if (points.size < 2) return emptyList()
        val runs = splitRuns(points)
        val out = ArrayList<MapPolyline>()

        runs.forEachIndexed { runIndex, run ->
            when (mode) {
                TrackColorMode.MODE -> out += buildBySegment(run, segments, runIndex)
                TrackColorMode.SPEED -> out += buildBySpeed(run, runIndex)
            }
        }
        return out
    }

    /** 按 startsNewSegment 切段。 */
    fun splitRuns(points: List<TrackPointEntity>): List<List<TrackPointEntity>> {
        val runs = ArrayList<List<TrackPointEntity>>()
        var current = ArrayList<TrackPointEntity>()
        for (p in points) {
            if (p.startsNewSegment && current.isNotEmpty()) {
                runs.add(current)
                current = ArrayList()
            }
            current.add(p)
        }
        if (current.isNotEmpty()) runs.add(current)
        return runs
    }

    // ---------- 按交通方式 ----------

    private fun buildBySegment(
        run: List<TrackPointEntity>,
        segments: List<TripSegmentEntity>,
        runIndex: Int,
    ): List<MapPolyline> {
        if (run.size < 2) return emptyList()

        if (segments.isEmpty()) {
            // 第 5 步之前没有分段数据：整段用中性色
            return listOf(polyline("m-$runIndex", run, COLOR_UNKNOWN))
        }

        // 按 segment 边界把 run 切开；跨段时各自上色
        val out = ArrayList<MapPolyline>()
        var start = 0
        var currentMode = modeAt(run[0].seq, segments)
        for (i in 1 until run.size) {
            val m = modeAt(run[i].seq, segments)
            if (m != currentMode) {
                // 边界点两边都要有，避免出现视觉断口
                out += polyline("m-$runIndex-$start", run.subList(start, i + 1), colorOf(currentMode))
                start = i
                currentMode = m
            }
        }
        out += polyline("m-$runIndex-$start", run.subList(start, run.size), colorOf(currentMode))
        return out
    }

    private fun modeAt(seq: Int, segments: List<TripSegmentEntity>): TransportMode =
        segments.firstOrNull { seq in it.startPointSeq..it.endPointSeq }?.mode ?: TransportMode.UNKNOWN

    private fun colorOf(mode: TransportMode): Int = when (mode) {
        TransportMode.WALK -> COLOR_WALK
        TransportMode.BIKE -> COLOR_BIKE
        TransportMode.CAR -> COLOR_CAR
        TransportMode.UNKNOWN -> COLOR_UNKNOWN
    }

    // ---------- 按速度 ----------

    private fun buildBySpeed(run: List<TrackPointEntity>, runIndex: Int): List<MapPolyline> {
        if (run.size < 2) return emptyList()

        val buckets = IntArray(run.size) { speedBucket(speedAt(run, it)) }
        val out = ArrayList<MapPolyline>()
        var start = 0
        for (i in 1 until run.size) {
            if (buckets[i] != buckets[start]) {
                out += polyline("s-$runIndex-$start", run.subList(start, i + 1), colorOfBucket(buckets[start]))
                start = i
            }
        }
        out += polyline("s-$runIndex-$start", run.subList(start, run.size), colorOfBucket(buckets[start]))
        return out
    }

    private fun speedAt(run: List<TrackPointEntity>, index: Int): Double {
        val p = run[index]
        p.speedMps?.let { if (it > 0f) return it.toDouble() }
        // 系统没给速度就用相邻点算
        val a = run.getOrNull(index - 1) ?: return 0.0
        val b = run.getOrNull(index + 1) ?: p
        val dt = (p.recordedAt - a.recordedAt) / 1000.0
        if (dt <= 0.2) return 0.0
        return Wgs84(a.lat, a.lng).distanceTo(Wgs84(p.lat, p.lng)) / dt
    }

    private fun speedBucket(mps: Double): Int = when {
        mps < Tuning.Segment.STOP_SPEED_MPS -> 0 // 基本没动
        mps < Tuning.Segment.WALK_MAX_MPS -> 1   // 走路
        mps < Tuning.Segment.BIKE_MAX_MPS -> 2   // 骑车
        else -> 3                                // 车
    }

    private fun colorOfBucket(bucket: Int): Int = when (bucket) {
        0 -> COLOR_SLOW
        1 -> COLOR_WALK
        2 -> COLOR_BIKE
        else -> COLOR_CAR
    }

    private fun polyline(id: String, run: List<TrackPointEntity>, color: Int): MapPolyline {
        val raw = run.map { Wgs84(it.lat, it.lng) }
        val simplified = PolylineSimplifier.simplify(raw, EPSILON_METERS)
        return MapPolyline(
            id = id,
            points = if (simplified.size >= 2) simplified else raw,
            colorArgb = color,
            widthDp = WIDTH_DP,
        )
    }
}
