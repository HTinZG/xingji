package com.llyytt.path.data.repo

import androidx.room.withTransaction
import com.llyytt.path.core.rules.SegmentDraft
import com.llyytt.path.core.rules.Segmenter
import com.llyytt.path.core.rules.StayDraft
import com.llyytt.path.core.rules.TripInvariants
import com.llyytt.path.data.db.AppDatabase
import com.llyytt.path.data.db.StayPointEntity
import com.llyytt.path.data.db.TrackPointEntity
import com.llyytt.path.data.db.TransportMode
import com.llyytt.path.data.db.TripSegmentEntity

/**
 * 分段的写操作。三个操作各自一个事务。
 *
 * 铁律：**原始 track_point 永远不修改**。分段只是「点的区间 + 方式」，
 * 所有编辑都只动 trip_segment / stay_point。
 */
class SegmentRepository(private val db: AppDatabase) {

    /** 跑一次自动分段并覆盖旧结果。返回段数。 */
    /** 这条行程是否被手动改过（改方式/拆分/合并）。自动分段会覆盖这些修改，调用前先问。 */
    suspend fun hasManualEdits(tripId: Long): Boolean =
        db.tripSegmentDao().getByTrip(tripId).any { it.manuallyEdited }

    suspend fun autoSegment(tripId: Long): Int = db.withTransaction {
        val points = db.trackPointDao().getByTrip(tripId)
        val result = Segmenter.run(points)

        db.tripSegmentDao().deleteByTrip(tripId)
        db.stayPointDao().deleteByTrip(tripId)

        db.tripSegmentDao().insertAll(
            result.segments.mapIndexed { index, draft ->
                TripSegmentEntity(
                    tripId = tripId,
                    seq = index,
                    mode = draft.mode,
                    startPointSeq = draft.startSeq,
                    endPointSeq = draft.endSeq,
                )
            },
        )
        db.stayPointDao().insertAll(
            result.stays.map {
                StayPointEntity(
                    tripId = tripId,
                    lat = it.lat,
                    lng = it.lng,
                    radiusMeters = it.radiusMeters,
                    arriveAt = it.arriveAt,
                    leaveAt = it.leaveAt,
                )
            },
        )

        validateOrThrow(tripId)
        result.segments.size
    }

    /** 只改方式：不动 seq、不重排、不跑全局校验。 */
    suspend fun setMode(segmentId: Long, mode: TransportMode) = db.withTransaction {
        val segment = db.tripSegmentDao().findById(segmentId) ?: error("分段不存在")
        db.tripSegmentDao().update(segment.copy(mode = mode, manuallyEdited = true))
    }

    /**
     * 拆分：在 atSeq 处切开，atSeq 属于后段。
     *
     * 步骤（同一事务）：
     * 1. 前段 endPointSeq = atSeq - 1
     * 2. 后续段 seq 顺移（从后往前，避免中间态冲突）
     * 3. 插入后段
     * 4. 跑不变量校验；不通过就抛异常，Room 回滚整个事务
     */
    suspend fun split(segmentId: Long, atSeq: Int) = db.withTransaction {
        val target = db.tripSegmentDao().findById(segmentId) ?: error("分段不存在")
        require(atSeq in (target.startPointSeq + 1)..target.endPointSeq) {
            "拆分点必须落在段内部（$atSeq 不在 ${target.startPointSeq + 1}..${target.endPointSeq}）"
        }
        val tripId = target.tripId
        val all = db.tripSegmentDao().getByTrip(tripId).sortedBy { it.seq }

        db.tripSegmentDao().update(target.copy(endPointSeq = atSeq - 1, manuallyEdited = true))

        all.filter { it.seq > target.seq }
            .sortedByDescending { it.seq }
            .forEach { db.tripSegmentDao().update(it.copy(seq = it.seq + 1)) }

        db.tripSegmentDao().insert(
            TripSegmentEntity(
                tripId = tripId,
                seq = target.seq + 1,
                mode = target.mode,
                startPointSeq = atSeq,
                endPointSeq = target.endPointSeq,
                manuallyEdited = true,
            ),
        )

        validateOrThrow(tripId)
    }

    /**
     * 合并相邻两段：保留前段、删掉后段、后续 seq 前移。
     */
    suspend fun merge(firstId: Long, secondId: Long) = db.withTransaction {
        val first = db.tripSegmentDao().findById(firstId) ?: error("分段不存在")
        val second = db.tripSegmentDao().findById(secondId) ?: error("分段不存在")
        require(first.tripId == second.tripId) { "不能跨行程合并" }
        require(first.endPointSeq + 1 == second.startPointSeq) { "只能合并相邻的两段" }

        val tripId = first.tripId
        db.tripSegmentDao().update(first.copy(endPointSeq = second.endPointSeq, manuallyEdited = true))
        db.tripSegmentDao().deleteById(second.id)

        db.tripSegmentDao().getByTrip(tripId)
            .filter { it.seq > second.seq }
            .sortedBy { it.seq }
            .forEach { db.tripSegmentDao().update(it.copy(seq = it.seq - 1)) }

        validateOrThrow(tripId)
    }

    /**
     * 校验失败就抛，让事务回滚。绝不静默吞掉。
     */
    private suspend fun validateOrThrow(tripId: Long) {
        val points = db.trackPointDao().getByTrip(tripId)
        val segments = db.tripSegmentDao().getByTrip(tripId).map {
            SegmentDraft(it.startPointSeq, it.endPointSeq, it.mode)
        }
        val stays = db.stayPointDao().getByTrip(tripId)
        val violations = TripInvariants.validateSegments(points, segments, toDrafts(stays, points))
        if (violations.isNotEmpty()) {
            throw IllegalStateException(
                "分段校验失败：" + violations.joinToString { "${it.code}(${it.message})" },
            )
        }
    }

    /**
     * stay_point 表里没有存 seq 区间（只存了中心点、半径、到达/离开时间）。
     * 校验时用时间戳把它映射回点的下标 —— 因为 arriveAt/leaveAt 本来就取自点的 recordedAt，
     * 能精确对上。这样避免了为校验再加两个字段和一次 schema 迁移。
     */
    private fun toDrafts(stays: List<StayPointEntity>, points: List<TrackPointEntity>): List<StayDraft> =
        stays.mapNotNull { stay ->
            val start = points.indexOfFirst { it.recordedAt >= stay.arriveAt }
            val end = points.indexOfLast { it.recordedAt <= (stay.leaveAt ?: Long.MAX_VALUE) }
            if (start < 0 || end < start) {
                null
            } else {
                StayDraft(
                    startSeq = points[start].seq,
                    endSeq = points[end].seq,
                    lat = stay.lat,
                    lng = stay.lng,
                    radiusMeters = stay.radiusMeters,
                    arriveAt = stay.arriveAt,
                    leaveAt = stay.leaveAt ?: stay.arriveAt,
                )
            }
        }
}
