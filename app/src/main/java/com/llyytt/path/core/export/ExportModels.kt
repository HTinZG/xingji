package com.llyytt.path.core.export

import com.llyytt.path.data.db.TransportMode
import com.llyytt.path.data.repo.TripDetail

/**
 * 导出用的扁平模型。
 *
 * 存在的理由：让 [TrackExporter] 不依赖 Room 实体和 Android，
 * 这样导出格式可以纯 JVM 单测（格式错了比功能错了更丢人）。
 */
data class ExportPoint(
    val seq: Int,
    val lat: Double,
    val lng: Double,
    val recordedAt: Long,
)

data class ExportSegment(
    val startSeq: Int,
    val endSeq: Int,
    val mode: TransportMode,
)

data class ExportStay(
    val lat: Double,
    val lng: Double,
    val arriveAt: Long,
    val leaveAt: Long?,
) {
    val durationMillis: Long
        get() = ((leaveAt ?: arriveAt) - arriveAt).coerceAtLeast(0)
}

data class TripDetailForExport(
    val title: String,
    val startedAt: Long,
    val exportedAt: Long,
    val points: List<ExportPoint>,
    val segments: List<ExportSegment>,
    val stays: List<ExportStay>,
) {
    private val bySeq: Map<Int, ExportPoint> by lazy { points.associateBy { it.seq } }

    fun pointAt(seq: Int): ExportPoint? = bySeq[seq]
}

/** 把数据库里的行程转成导出模型。坐标原样带过去——**不做任何坐标系转换**。 */
fun TripDetail.toExportModel(title: String, exportedAt: Long = System.currentTimeMillis()): TripDetailForExport =
    TripDetailForExport(
        title = title,
        startedAt = trip.startedAt,
        exportedAt = exportedAt,
        points = points.map { ExportPoint(it.seq, it.lat, it.lng, it.recordedAt) },
        segments = segments.map { ExportSegment(it.startPointSeq, it.endPointSeq, it.mode) },
        stays = stays.map { ExportStay(it.lat, it.lng, it.arriveAt, it.leaveAt) },
    )
