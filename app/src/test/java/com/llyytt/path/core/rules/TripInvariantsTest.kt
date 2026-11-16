package com.llyytt.path.core.rules

import com.llyytt.path.data.db.PointSource
import com.llyytt.path.data.db.TrackPointEntity
import com.llyytt.path.data.db.TransportMode
import com.llyytt.path.data.db.TripStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripInvariantsTest {

    private fun points(n: Int, gapAt: Int = -1): List<TrackPointEntity> =
        (0 until n).map { i ->
            TrackPointEntity(
                id = i.toLong(),
                tripId = 1,
                seq = i,
                lat = 39.9 + i * 0.0001,
                lng = 116.4,
                speedMps = 1.3f,
                accuracyM = 5f,
                recordedAt = i * 2000L,
                source = PointSource.GNSS,
                startsNewSegment = i == gapAt,
            )
        }

    private fun codes(violations: List<TripInvariants.Violation>) = violations.map { it.code }

    // ---------- 分段 ----------

    @Test
    fun 合法的分段没有任何违规() {
        val pts = points(10)
        val segs = listOf(SegmentDraft(0, 9, TransportMode.WALK))
        assertTrue(TripInvariants.validateSegments(pts, segs, emptyList()).isEmpty())
    }

    @Test
    fun 分段重叠会报错() {
        val pts = points(10)
        val segs = listOf(
            SegmentDraft(0, 5, TransportMode.WALK),
            SegmentDraft(4, 9, TransportMode.BIKE),
        )
        assertTrue(codes(TripInvariants.validateSegments(pts, segs, emptyList())).contains("SEGMENT_OVERLAP"))
    }

    @Test
    fun 有点没被覆盖会报错() {
        val pts = points(10)
        val segs = listOf(SegmentDraft(0, 4, TransportMode.WALK))
        val violations = TripInvariants.validateSegments(pts, segs, emptyList())
        assertEquals(5, violations.count { it.code == "POINT_NOT_COVERED" })
    }

    @Test
    fun 停留覆盖的点不算漏覆盖() {
        val pts = points(10)
        val segs = listOf(
            SegmentDraft(0, 3, TransportMode.WALK),
            SegmentDraft(7, 9, TransportMode.WALK),
        )
        val stays = listOf(StayDraft(4, 6, 39.9005, 116.4, 2.0, 8000, 12000))
        assertTrue(TripInvariants.validateSegments(pts, segs, stays).isEmpty())
    }

    @Test
    fun 分段跨过中断点不算违规() {
        // 第 5 个点带 startsNewSegment=true，但整段仍然覆盖 0..9
        val pts = points(10, gapAt = 5)
        val segs = listOf(SegmentDraft(0, 9, TransportMode.WALK))
        assertTrue(
            "startsNewSegment 只影响画线，不应让校验失败",
            TripInvariants.validateSegments(pts, segs, emptyList()).isEmpty(),
        )
    }

    @Test
    fun 分段与停留重叠会报错() {
        val pts = points(10)
        val segs = listOf(SegmentDraft(0, 9, TransportMode.WALK))
        val stays = listOf(StayDraft(4, 6, 39.9005, 116.4, 2.0, 8000, 12000))
        assertTrue(codes(TripInvariants.validateSegments(pts, segs, stays)).contains("SEGMENT_OVERLAPS_STAY"))
    }

    @Test
    fun seq不连续会报错() {
        val pts = points(5).toMutableList()
        pts[3] = pts[3].copy(seq = 7)
        assertTrue(codes(TripInvariants.validateSegments(pts, listOf(SegmentDraft(0, 7, TransportMode.WALK)), emptyList()))
            .contains("SEQ_NOT_DENSE"))
    }

    // ---------- 暂停状态不变量 ----------

    @Test
    fun 记录中不允许未闭合暂停() {
        val open = listOf(1000L to null)
        assertTrue(codes(TripInvariants.validatePauses(TripStatus.RECORDING, null, open))
            .contains("PAUSE_OPEN_WHILE_RECORDING"))

        val closed = listOf(1000L to 2000L)
        assertTrue(TripInvariants.validatePauses(TripStatus.RECORDING, null, closed).isEmpty())
    }

    @Test
    fun 暂停中必须恰好一条未闭合() {
        assertTrue(TripInvariants.validatePauses(TripStatus.PAUSED, null, listOf(1000L to null)).isEmpty())

        assertTrue(codes(TripInvariants.validatePauses(TripStatus.PAUSED, null, emptyList()))
            .contains("PAUSE_COUNT_INVALID"))

        val two = listOf(1000L to null, 3000L to null)
        assertTrue(codes(TripInvariants.validatePauses(TripStatus.PAUSED, null, two))
            .contains("PAUSE_COUNT_INVALID"))
    }

    @Test
    fun 已结束必须全部闭合且endedAt非空() {
        assertTrue(
            TripInvariants.validatePauses(
                TripStatus.FINISHED, 9000L, listOf(1000L to 2000L),
            ).isEmpty(),
        )

        assertTrue(codes(
            TripInvariants.validatePauses(TripStatus.FINISHED, 9000L, listOf(1000L to null)),
        ).contains("PAUSE_OPEN_WHEN_FINISHED"))

        assertTrue(codes(
            TripInvariants.validatePauses(TripStatus.FINISHED, null, listOf(1000L to 2000L)),
        ).contains("ENDED_AT_MISSING"))
    }
}
