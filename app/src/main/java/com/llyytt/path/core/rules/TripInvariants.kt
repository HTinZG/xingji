package com.llyytt.path.core.rules

import com.llyytt.path.data.db.TrackPointEntity
import com.llyytt.path.data.db.TripStatus

/**
 * 数据一致性校验。
 *
 * 分段规则：
 * - seq 必须 0..N-1 连续
 * - 分段按 seq 严格递增、互不重叠、区间内连续
 * - 每个点要么属于某个 segment，要么属于某个停留（停留是空洞）
 * - **允许** segment 跨过 startsNewSegment=true 的点：那个标记只影响画线断开，
 *   不强制分段在这里切开
 *
 * 暂停规则：
 * - PAUSED    : 有且仅有一条未闭合
 * - RECORDING : 不允许未闭合
 * - FINISHED  : 全部闭合，且 endedAt 非空
 */
object TripInvariants {

    data class Violation(val code: String, val message: String)

    fun validateSegments(
        points: List<TrackPointEntity>,
        segments: List<SegmentDraft>,
        stays: List<StayDraft>,
    ): List<Violation> {
        val out = ArrayList<Violation>()
        if (points.isEmpty()) return out

        // seq 必须从 0 开始、逐 1 递增
        points.forEachIndexed { index, p ->
            if (p.seq != index) {
                out.add(Violation("SEQ_NOT_DENSE", "第 $index 个点的 seq 是 ${p.seq}，应为 $index"))
            }
        }
        val maxSeq = points.size - 1

        // 分段自身：范围合法 + 严格递增 + 不重叠
        var prevEnd = -1
        for (seg in segments) {
            if (seg.startSeq > seg.endSeq) {
                out.add(Violation("SEGMENT_RANGE", "分段 ${seg.startSeq}..${seg.endSeq} 起点大于终点"))
            }
            if (seg.startSeq < 0 || seg.endSeq > maxSeq) {
                out.add(Violation("SEGMENT_RANGE", "分段 ${seg.startSeq}..${seg.endSeq} 越界（0..$maxSeq）"))
            }
            if (seg.startSeq <= prevEnd) {
                out.add(Violation("SEGMENT_OVERLAP", "分段 ${seg.startSeq}..${seg.endSeq} 与前一段重叠"))
            }
            prevEnd = maxOf(prevEnd, seg.endSeq)
        }

        // 分段与停留不能重叠
        for (seg in segments) {
            for (stay in stays) {
                val overlap = seg.startSeq <= stay.endSeq && stay.startSeq <= seg.endSeq
                if (overlap) {
                    out.add(
                        Violation(
                            "SEGMENT_OVERLAPS_STAY",
                            "分段 ${seg.startSeq}..${seg.endSeq} 与停留 ${stay.startSeq}..${stay.endSeq} 重叠",
                        ),
                    )
                }
            }
        }

        // 全覆盖：每个点要么在分段里，要么在停留里
        val covered = BooleanArray(points.size)
        for (seg in segments) {
            for (k in seg.startSeq..seg.endSeq) {
                if (k in covered.indices) covered[k] = true
            }
        }
        for (stay in stays) {
            for (k in stay.startSeq..stay.endSeq) {
                if (k in covered.indices) covered[k] = true
            }
        }
        points.indices.forEach { k ->
            if (!covered[k]) out.add(Violation("POINT_NOT_COVERED", "点 seq=$k 既不在分段也不在停留里"))
        }

        return out
    }

    fun validatePauses(
        status: TripStatus,
        endedAt: Long?,
        /** (pausedAt, resumedAt?)；resumedAt 为 null 即未闭合 */
        pauses: List<Pair<Long, Long?>>,
    ): List<Violation> {
        val out = ArrayList<Violation>()
        val open = pauses.count { it.second == null }

        when (status) {
            TripStatus.RECORDING -> if (open != 0) {
                out.add(Violation("PAUSE_OPEN_WHILE_RECORDING", "记录中却有 $open 条未闭合的暂停"))
            }

            TripStatus.PAUSED -> if (open != 1) {
                out.add(Violation("PAUSE_COUNT_INVALID", "暂停中应恰好 1 条未闭合暂停，实际 $open 条"))
            }

            TripStatus.FINISHED -> {
                if (open != 0) {
                    out.add(Violation("PAUSE_OPEN_WHEN_FINISHED", "已结束仍有 $open 条未闭合的暂停"))
                }
                if (endedAt == null) {
                    out.add(Violation("ENDED_AT_MISSING", "已结束但 endedAt 为空"))
                }
            }
        }
        return out
    }
}
