package com.llyytt.path.data.repo

import androidx.room.withTransaction
import com.llyytt.path.core.geo.Wgs84
import com.llyytt.path.data.db.AppDatabase
import com.llyytt.path.data.db.TrackPointEntity
import com.llyytt.path.data.db.TripEntity
import com.llyytt.path.data.db.TripPauseEntity
import com.llyytt.path.data.db.TripStatus
import com.llyytt.path.location.LocationFix
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 开始记录的结果。已有未结束行程时不能悄悄再开一条。 */
sealed interface StartOutcome {
    data class Started(val trip: TripEntity) : StartOutcome
    data class AlreadyRunning(val trip: TripEntity) : StartOutcome
}

/**
 * 行程的生命周期操作。
 *
 * 所有写操作都走 Mutex + 事务，保证「同一时刻最多一条未结束行程」。
 * 暂停/恢复/结束都在事务里顺手收敛未闭合的 pause，保证状态不变量：
 * - PAUSED    -> 有且仅有一条 resumedAt = null
 * - RECORDING -> 没有 resumedAt = null
 * - FINISHED  -> 全部闭合且 endedAt 非空
 */
class RecordingRepository(private val db: AppDatabase) {

    private val mutex = Mutex()

    suspend fun unfinished(): TripEntity? = db.tripDao().findUnfinished()

    suspend fun findTrip(id: Long): TripEntity? = db.tripDao().findById(id)

    suspend fun startNewTrip(): StartOutcome = mutex.withLock {
        db.withTransaction {
            val existing = db.tripDao().findUnfinished()
            if (existing != null) {
                StartOutcome.AlreadyRunning(existing)
            } else {
                val now = System.currentTimeMillis()
                val id = db.tripDao().insert(TripEntity(startedAt = now))
                StartOutcome.Started(db.tripDao().findById(id)!!)
            }
        }
    }

    suspend fun pause(tripId: Long) = mutex.withLock {
        db.withTransaction {
            val now = System.currentTimeMillis()
            // 先收敛历史脏数据，再插新的，保证最多一条未闭合
            db.tripPauseDao().closeAllOpen(tripId, now)
            db.tripPauseDao().insert(TripPauseEntity(tripId = tripId, pausedAt = now))
            db.tripDao().findById(tripId)?.let {
                db.tripDao().update(it.copy(status = TripStatus.PAUSED, updatedAt = now))
            }
        }
    }

    suspend fun resume(tripId: Long) = mutex.withLock {
        db.withTransaction {
            val now = System.currentTimeMillis()
            db.tripPauseDao().closeAllOpen(tripId, now)
            db.tripDao().findById(tripId)?.let {
                db.tripDao().update(it.copy(status = TripStatus.RECORDING, updatedAt = now))
            }
        }
    }

    /**
     * 结束行程。
     *
     * 关键：**结束时间取「最后一个轨迹点的时间」，而不是当前时间**（相差超过 2 分钟时）。
     * 因为 App 可能在中途被系统冻结/强停，而行程一直挂着「记录中」，
     * 用户过半小时才回来点结束——按当前时间收尾就会凭空多出半小时的"时长"，
     * 而这段时间一个点都没有。宁可时长短一点，也不要假数据。
     */
    suspend fun finish(tripId: Long) = mutex.withLock {
        db.withTransaction {
            val now = System.currentTimeMillis()
            val lastPointAt = db.trackPointDao().lastPoint(tripId)?.recordedAt
            val endedAt = if (lastPointAt != null && lastPointAt > 0 && now - lastPointAt > SILENT_GAP_MS) {
                lastPointAt
            } else {
                now
            }
            db.tripPauseDao().closeAllOpen(tripId, endedAt)
            db.tripDao().findById(tripId)?.let {
                db.tripDao().update(
                    it.copy(status = TripStatus.FINISHED, endedAt = endedAt, updatedAt = now),
                )
            }
        }
    }

    /** 最后一次定位的时间；没有点返回 null。 */
    suspend fun lastPointAt(tripId: Long): Long? =
        db.trackPointDao().lastPoint(tripId)?.recordedAt

    /** 记一个点。seq 由调用方保证递增（服务里是单协程串行写入）。 */
    suspend fun insertPoint(
        tripId: Long,
        seq: Int,
        fix: LocationFix,
        startsNewSegment: Boolean,
    ): TrackPointEntity {
        val entity = TrackPointEntity(
            tripId = tripId,
            seq = seq,
            lat = fix.point.lat,
            lng = fix.point.lng,
            altitudeM = fix.altitudeM,
            speedMps = fix.speedMps,
            bearingDeg = fix.bearingDeg,
            accuracyM = fix.accuracyMeters,
            recordedAt = fix.timeMillis,
            source = fix.source,
            startsNewSegment = startsNewSegment,
        )
        db.trackPointDao().insert(entity)
        return entity
    }

    suspend fun maxSeq(tripId: Long): Int = (db.trackPointDao().maxSeq(tripId) ?: -1)

    suspend fun lastPoint(tripId: Long): TrackPointEntity? = db.trackPointDao().lastPoint(tripId)

    suspend fun pointCount(tripId: Long): Int = db.trackPointDao().count(tripId)

    suspend fun pauses(tripId: Long): List<Pair<Long, Long?>> =
        db.tripPauseDao().getByTrip(tripId).map { it.pausedAt to it.resumedAt }

    /** 距离不缓存：每次从点重算。断点处不加线段。 */
    suspend fun distanceMeters(tripId: Long): Double {
        val points = db.trackPointDao().getByTrip(tripId)
        if (points.size < 2) return 0.0
        var sum = 0.0
        var prev: TrackPointEntity? = null
        for (p in points) {
            val last = prev
            if (last != null && !p.startsNewSegment) {
                sum += Wgs84(last.lat, last.lng).distanceTo(Wgs84(p.lat, p.lng))
            }
            prev = p
        }
        return sum
    }

    suspend fun deleteAll() = mutex.withLock {
        db.withTransaction {
            db.tripDao().deleteAll()
        }
    }

    private companion object {
        /** 最后一个点与当前时间差超过这个值，就认为中途已经断了 */
        const val SILENT_GAP_MS = 120_000L
    }
}
