package com.llyytt.path.core.rules

import com.llyytt.path.core.geo.Wgs84
import com.llyytt.path.data.db.TrackPointEntity
import com.llyytt.path.data.db.TransportMode

/** 分段草稿：只描述 seq 区间和方式，落库时才生成实体。 */
data class SegmentDraft(
    val startSeq: Int,
    val endSeq: Int,
    val mode: TransportMode,
)

/** 停留草稿。segment 里不出现 STAY，停留只在这里。 */
data class StayDraft(
    val startSeq: Int,
    val endSeq: Int,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Double,
    val arriveAt: Long,
    val leaveAt: Long,
)

data class SegmentationResult(
    val segments: List<SegmentDraft>,
    val stays: List<StayDraft>,
)

/**
 * 端上自动分段。
 *
 * 规则（全部参数在 Tuning.Segment，与地图「按速度着色」共用同一套阈值）：
 * 1. 先按 `startsNewSegment` 切「连续片」，断点两边不参与同一段。
 * 2. 片内用中位数平滑后的速度找停留：连续低于 STOP_SPEED 且时长 ≥ STOP_MIN 记为停留。
 * 3. 剩下的点在各自连续片内按速度分档（走路/骑车/车）合并成段。
 * 4. 短于 MIN_SEGMENT 的段并入相邻段，避免切得太碎。
 *
 * 注意：停留点是「空洞」，不会出现在任何 segment 的区间里。
 * startsNewSegment 只在这里被用作切分依据；校验层不会因此报错（见 TripInvariants）。
 */
object Segmenter {

    private data class RangeSeg(val start: Int, val end: Int, val mode: TransportMode)

    fun run(points: List<TrackPointEntity>): SegmentationResult {
        if (points.size < 2) return SegmentationResult(emptyList(), emptyList())

        val speeds = smoothedSpeeds(points)
        val stayFlag = BooleanArray(points.size)
        val stays = ArrayList<StayDraft>()
        val segments = ArrayList<RangeSeg>()

        for (run in contiguousRuns(points)) {
            // 1) 停留
            var i = run.first
            while (i <= run.last) {
                if (speeds[i] >= Tuning.Segment.STOP_SPEED_MPS) {
                    i++
                    continue
                }
                var j = i
                while (j < run.last && speeds[j + 1] < Tuning.Segment.STOP_SPEED_MPS) j++
                val duration = points[j].recordedAt - points[i].recordedAt
                if (duration >= Tuning.Segment.STOP_MIN_DURATION_MS) {
                    for (k in i..j) stayFlag[k] = true
                    stays.add(buildStay(points, i, j))
                }
                i = j + 1
            }

            // 2) 分段（跳过停留点）
            var segStart = -1
            var segMode: TransportMode? = null
            for (k in run) {
                if (stayFlag[k]) {
                    if (segStart >= 0) {
                        segments.add(RangeSeg(segStart, k - 1, segMode!!))
                        segStart = -1
                        segMode = null
                    }
                    continue
                }
                val mode = classify(speeds[k])
                if (segStart < 0) {
                    segStart = k
                    segMode = mode
                } else if (mode != segMode) {
                    segments.add(RangeSeg(segStart, k - 1, segMode!!))
                    segStart = k
                    segMode = mode
                }
            }
            if (segStart >= 0) segments.add(RangeSeg(segStart, run.last, segMode!!))
        }

        // 3) 合并过短的段
        mergeShort(points, segments)

        return SegmentationResult(
            segments = segments.map {
                SegmentDraft(
                    startSeq = points[it.start].seq,
                    endSeq = points[it.end].seq,
                    mode = it.mode,
                )
            }.sortedBy { it.startSeq },
            stays = stays.sortedBy { it.arriveAt },
        )
    }

    /** 速度分档。与 TrackPainter.speedBucket 用同一组阈值。 */
    fun classify(speedMps: Double): TransportMode = when {
        speedMps < Tuning.Segment.WALK_MAX_MPS -> TransportMode.WALK
        speedMps < Tuning.Segment.BIKE_MAX_MPS -> TransportMode.BIKE
        else -> TransportMode.CAR
    }

    // ---------- 内部 ----------

    private fun contiguousRuns(points: List<TrackPointEntity>): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = 0
        for (i in 1 until points.size) {
            if (points[i].startsNewSegment) {
                out.add(start until i)
                start = i
            }
        }
        out.add(start until points.size)
        return out
    }

    /** 逐点速度：优先系统值，否则用上一点算；断点后重新开始。 */
    private fun rawSpeeds(points: List<TrackPointEntity>): DoubleArray {
        val out = DoubleArray(points.size)
        var prev: TrackPointEntity? = null
        for (i in points.indices) {
            val p = points[i]
            if (p.startsNewSegment) prev = null
            val system = p.speedMps
            out[i] = when {
                system != null && system >= 0f -> system.toDouble()
                prev != null -> {
                    val last = prev!!
                    val seconds = (p.recordedAt - last.recordedAt) / 1000.0
                    if (seconds > 0.2) {
                        Wgs84(last.lat, last.lng).distanceTo(Wgs84(p.lat, p.lng)) / seconds
                    } else {
                        0.0
                    }
                }
                else -> 0.0
            }
            prev = p
        }
        return out
    }

    private fun smoothedSpeeds(points: List<TrackPointEntity>): DoubleArray {
        val raw = rawSpeeds(points)
        val out = DoubleArray(points.size)
        val window = ArrayDeque<Double>()
        for (i in points.indices) {
            if (points[i].startsNewSegment) window.clear()
            window.addLast(raw[i])
            while (window.size > Tuning.Segment.SMOOTH_WINDOW) window.removeFirst()
            out[i] = SpeedSmoother.median(window)
        }
        return out
    }

    private fun buildStay(points: List<TrackPointEntity>, start: Int, end: Int): StayDraft {
        var latSum = 0.0
        var lngSum = 0.0
        for (k in start..end) {
            latSum += points[k].lat
            lngSum += points[k].lng
        }
        val count = end - start + 1
        val centerLat = latSum / count
        val centerLng = lngSum / count
        var radius = 0.0
        for (k in start..end) {
            val d = Wgs84(centerLat, centerLng).distanceTo(Wgs84(points[k].lat, points[k].lng))
            if (d > radius) radius = d
        }
        return StayDraft(
            startSeq = points[start].seq,
            endSeq = points[end].seq,
            lat = centerLat,
            lng = centerLng,
            radiusMeters = radius,
            arriveAt = points[start].recordedAt,
            leaveAt = points[end].recordedAt,
        )
    }

    private fun duration(points: List<TrackPointEntity>, seg: RangeSeg): Long =
        points[seg.end].recordedAt - points[seg.start].recordedAt

    /**
     * 过短的段并入相邻段。
     * 只在「索引相邻」时合并——中间隔着停留的两段不能合，否则停留点会被包进 segment。
     */
    private fun mergeShort(points: List<TrackPointEntity>, segs: MutableList<RangeSeg>) {
        var changed = true
        while (changed && segs.size > 1) {
            changed = false
            for (i in segs.indices) {
                val seg = segs[i]
                if (duration(points, seg) >= Tuning.Segment.MIN_SEGMENT_MS) continue

                val prev = segs.getOrNull(i - 1)
                val next = segs.getOrNull(i + 1)
                val prevAdjacent = prev != null && prev.end + 1 == seg.start
                val nextAdjacent = next != null && seg.end + 1 == next.start

                val mergePrev = prevAdjacent &&
                    (!nextAdjacent || duration(points, prev!!) >= duration(points, next!!))

                when {
                    mergePrev -> {
                        segs[i - 1] = prev!!.copy(end = seg.end)
                        segs.removeAt(i)
                        changed = true
                    }
                    nextAdjacent -> {
                        segs[i + 1] = next!!.copy(start = seg.start)
                        segs.removeAt(i)
                        changed = true
                    }
                    else -> Unit // 两边都是停留，保持原样
                }
                if (changed) break
            }
        }
    }
}
