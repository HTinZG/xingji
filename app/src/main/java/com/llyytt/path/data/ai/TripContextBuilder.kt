package com.llyytt.path.data.ai

import com.llyytt.path.core.format.Fmt
import com.llyytt.path.data.db.TrackPointEntity
import com.llyytt.path.data.db.TransportMode
import com.llyytt.path.data.repo.TripDetail
import com.llyytt.path.data.repo.TripListItem

/**
 * 给 AI 的上下文：**只发摘要，不发全量轨迹点**。
 *
 * 一条行程摘要约 60~90 个汉字，10 条也就几百字；
 * 全量点动辄几千个坐标，既费 token 又没必要——AI 回答"我昨天走了多久"不需要每个 GPS 点。
 */
object TripContextBuilder {

    fun systemPrompt(): String = """
        你是「行迹」App 里的出行助手。用户的数据全部存在他自己的手机里。
        你只能根据下面提供的行程摘要回答，不要编造不存在的数据；
        如果摘要里没有能回答的信息，直接说"记录里没有这项数据"。
        回答用简体中文，简短、具体，能引用数字就引用数字。
    """.trimIndent()

    /** 多条行程的摘要，一行一条。 */
    fun summarizeTrips(items: List<TripListItem>): String {
        if (items.isEmpty()) return "（用户还没有任何行程记录）"
        return buildString {
            appendLine("## 最近 ${items.size} 条行程")
            items.forEach { item ->
                val trip = item.trip
                val stats = item.stats
                appendLine(
                    "· ${Fmt.dateTime(trip.startedAt)} 开始，" +
                        "时长 ${Fmt.duration(stats.activeMillis)}，" +
                        "距离 ${Fmt.distance(stats.distanceMeters)}，" +
                        "均速 ${Fmt.speed(stats.avgSpeedMps)}，" +
                        "分段 ${item.segmentCount} 段，停留 ${item.stayCount} 个" +
                        (stats.gapCount.takeIf { it > 0 }?.let { "，信号中断 $it 次" } ?: ""),
                )
            }
        }
    }

    /** 单条行程的分段摘要，用户问到具体行程时用。 */
    fun summarizeDetail(detail: TripDetail): String = buildString {
        appendLine("## 最近一条行程的分段详情（${Fmt.dateTime(detail.trip.startedAt)}）")
        if (detail.segments.isEmpty()) {
            appendLine("（这条行程还没有分段）")
        } else {
            detail.segments.forEachIndexed { index, seg ->
                val start = detail.points.getOrNull(seg.startPointSeq)?.recordedAt
                val end = detail.points.getOrNull(seg.endPointSeq)?.recordedAt
                val duration = if (start != null && end != null) end - start else 0L
                val distance = distanceOf(detail.points, seg.startPointSeq, seg.endPointSeq)
                appendLine(
                    "· 第 ${index + 1} 段 ${modeLabel(seg.mode)}，" +
                        "从 ${start?.let { Fmt.clock(it) } ?: "?"} 到 ${end?.let { Fmt.clock(it) } ?: "?"}，" +
                        "持续 ${Fmt.duration(duration)}，距离 ${Fmt.distance(distance)}",
                )
            }
        }
        if (detail.stays.isNotEmpty()) {
            append("停留点：")
            append(detail.stays.joinToString("；") {
                "${Fmt.clock(it.arriveAt)}-${it.leaveAt?.let { end -> Fmt.clock(end) } ?: "至今"}"
            })
            appendLine()
        }
    }

    private fun distanceOf(points: List<TrackPointEntity>, startSeq: Int, endSeq: Int): Double {
        if (endSeq <= startSeq) return 0.0
        var sum = 0.0
        for (seq in startSeq until endSeq) {
            val a = points.getOrNull(seq) ?: continue
            val b = points.getOrNull(seq + 1) ?: continue
            sum += com.llyytt.path.core.geo.Wgs84(a.lat, a.lng)
                .distanceTo(com.llyytt.path.core.geo.Wgs84(b.lat, b.lng))
        }
        return sum
    }

    private fun modeLabel(mode: TransportMode): String = when (mode) {
        TransportMode.WALK -> "走路"
        TransportMode.BIKE -> "骑车"
        TransportMode.CAR -> "坐车"
        TransportMode.UNKNOWN -> "未分类"
    }
}
