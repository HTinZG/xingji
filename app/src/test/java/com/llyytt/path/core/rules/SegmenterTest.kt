package com.llyytt.path.core.rules

import com.llyytt.path.data.db.PointSource
import com.llyytt.path.data.db.TrackPointEntity
import com.llyytt.path.data.db.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/**
 * 合成轨迹：走 2 分钟 → 停 30 秒 → 快走 1 分钟 → 骑车 2 分钟。
 * 每 2 秒一个点，速度由构造直接给出（不依赖真实 GPS）。
 */
class SegmenterTest {

    private fun buildTrack(): List<TrackPointEntity> {
        val out = ArrayList<TrackPointEntity>()
        var time = 0L
        var lng = 116.40
        var seq = 0
        val metersPerDegLng = 111_320.0 * cos(Math.toRadians(39.9))

        fun advance(seconds: Int, speedMps: Double) {
            repeat(seconds / 2) {
                time += 2_000
                lng += speedMps * 2.0 / metersPerDegLng
                out.add(
                    TrackPointEntity(
                        id = seq.toLong(),
                        tripId = 1,
                        seq = seq,
                        lat = 39.9,
                        lng = lng,
                        speedMps = speedMps.toFloat(),
                        accuracyM = 5f,
                        recordedAt = time,
                        source = PointSource.GNSS,
                    ),
                )
                seq++
            }
        }

        advance(120, 1.3)  // 走路
        advance(30, 0.0)   // 停
        advance(60, 2.0)   // 快走
        advance(120, 5.5)  // 骑车
        return out
    }

    @Test
    fun 合成轨迹切出三段一个停留() {
        val points = buildTrack()
        val result = Segmenter.run(points)

        printReport(points, result)

        assertEquals("应识别出 1 个停留", 1, result.stays.size)
        assertEquals("应切出 3 段", 3, result.segments.size)

        assertEquals(TransportMode.WALK, result.segments[0].mode)
        assertEquals(TransportMode.WALK, result.segments[1].mode)
        assertEquals(TransportMode.BIKE, result.segments[2].mode)

        // 覆盖：第一段从 0 开始，最后一段到末尾
        assertEquals(0, result.segments.first().startSeq)
        assertEquals(points.last().seq, result.segments.last().endSeq)

        // 分段严格递增不重叠；停留落在两段之间
        for (i in 1 until result.segments.size) {
            assertTrue(result.segments[i].startSeq > result.segments[i - 1].endSeq)
        }
        val stay = result.stays.first()
        assertTrue(stay.startSeq > result.segments[0].endSeq)
        assertTrue(stay.endSeq < result.segments[1].startSeq)
    }

    @Test
    fun 合成轨迹通过校验() {
        val points = buildTrack()
        val result = Segmenter.run(points)
        val violations = TripInvariants.validateSegments(points, result.segments, result.stays)
        assertEquals("不应有任何违规：$violations", 0, violations.size)
    }

    @Test
    fun 十秒的短暂停顿不算停留() {
        val points = ArrayList<TrackPointEntity>()
        var time = 0L
        var lng = 116.40
        var seq = 0
        val metersPerDegLng = 111_320.0 * cos(Math.toRadians(39.9))

        fun advance(seconds: Int, speedMps: Double) {
            repeat(seconds / 2) {
                time += 2_000
                lng += speedMps * 2.0 / metersPerDegLng
                points.add(
                    TrackPointEntity(
                        id = seq.toLong(), tripId = 1, seq = seq, lat = 39.9, lng = lng,
                        speedMps = speedMps.toFloat(), accuracyM = 5f, recordedAt = time,
                        source = PointSource.GNSS,
                    ),
                )
                seq++
            }
        }

        advance(60, 1.3)
        advance(10, 0.0)   // 只有 10 秒
        advance(60, 1.3)

        val result = Segmenter.run(points)
        assertEquals("10 秒停顿不该算停留", 0, result.stays.size)
        assertEquals("应合成一整段", 1, result.segments.size)
    }

    @Test
    fun 断点处不会把两段合成一段() {
        val points = buildTrack().toMutableList()
        // 断点放在骑车段的正中间（seq 130 左右），切出来的两半都足够长，不会被合并掉
        points[130] = points[130].copy(startsNewSegment = true)

        val result = Segmenter.run(points)
        assertEquals(TransportMode.BIKE, result.segments.last().mode)
        assertEquals("断点应切出额外一段", 4, result.segments.size)
        assertTrue(result.segments[2].endSeq < result.segments[3].startSeq)

        val violations = TripInvariants.validateSegments(points, result.segments, result.stays)
        assertEquals("断点不应导致校验失败：$violations", 0, violations.size)
    }

    @Test
    fun 点数太少直接返回空() {
        assertEquals(0, Segmenter.run(emptyList()).segments.size)
        val one = buildTrack().take(1)
        assertEquals(0, Segmenter.run(one).segments.size)
    }

    private fun printReport(points: List<TrackPointEntity>, result: SegmentationResult) {
        val totalSeconds = (points.last().recordedAt - points.first().recordedAt) / 1000.0
        println("=== 合成轨迹：走 2 分钟 → 停 30 秒 → 快走 1 分钟 → 骑车 2 分钟 ===")
        println("点位数=${points.size}  总时长=${totalSeconds}s")

        println("停留 ${result.stays.size} 个：")
        result.stays.forEach { stay ->
            val seconds = (stay.leaveAt - stay.arriveAt) / 1000.0
            println("  seq ${stay.startSeq}..${stay.endSeq}  ${seconds}s  半径 ${"%.1f".format(stay.radiusMeters)}m")
        }

        println("分段 ${result.segments.size} 段：")
        result.segments.forEachIndexed { index, seg ->
            val startTime = points.first { it.seq == seg.startSeq }.recordedAt
            val endTime = points.first { it.seq == seg.endSeq }.recordedAt
            val seconds = (endTime - startTime) / 1000.0
            var meters = 0.0
            for (k in seg.startSeq until seg.endSeq) {
                val a = points[k]
                val b = points[k + 1]
                meters += com.llyytt.path.core.geo.Wgs84(a.lat, a.lng)
                    .distanceTo(com.llyytt.path.core.geo.Wgs84(b.lat, b.lng))
            }
            println(
                "  [${index + 1}] ${seg.mode}  seq ${seg.startSeq}..${seg.endSeq}  " +
                    "${seconds.toInt()}s  ${"%.0f".format(meters)}m",
            )
        }

        val violations = TripInvariants.validateSegments(points, result.segments, result.stays)
        println("校验：${if (violations.isEmpty()) "通过（0 条违规）" else violations.joinToString()}")
    }
}
