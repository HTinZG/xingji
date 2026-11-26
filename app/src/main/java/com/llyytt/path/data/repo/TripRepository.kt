package com.llyytt.path.data.repo

import androidx.room.withTransaction
import com.llyytt.path.core.rules.TripStats
import com.llyytt.path.core.rules.TripStatsCalculator
import com.llyytt.path.data.db.AppDatabase
import com.llyytt.path.data.db.StayPointEntity
import com.llyytt.path.data.db.TrackPointEntity
import com.llyytt.path.data.db.TripEntity
import com.llyytt.path.data.db.TripSegmentEntity

data class TripListItem(
    val trip: TripEntity,
    val stats: TripStats,
    val segmentCount: Int = 0,
    val stayCount: Int = 0,
)

data class TripDetail(
    val trip: TripEntity,
    val points: List<TrackPointEntity>,
    val segments: List<TripSegmentEntity>,
    val stays: List<StayPointEntity>,
    val stats: TripStats,
)

/**
 * 只读查询。写入仍然走 RecordingRepository，职责分开。
 */
class TripRepository(private val db: AppDatabase) {

    /**
     * 列表页数据。
     *
     * 这里刻意用**批量查询**而不是逐条查：旧实现每条行程要跑 4 次查询
     * （点、暂停、分段数、停留数），50 条行程就是 200 次，而且每次都把
     * 该行程所有点读出来。现在固定 5 次查询，与行程条数无关。
     */
    suspend fun list(limit: Int = 100): List<TripListItem> {
        val trips = db.tripDao().listOnce(limit)
        if (trips.isEmpty()) return emptyList()

        val ids = trips.map { it.id }
        val pointsByTrip = db.trackPointDao().getByTrips(ids).groupBy { it.tripId }
        val pausesByTrip = db.tripPauseDao().getByTrips(ids).groupBy { it.tripId }
        val segmentCounts = db.tripSegmentDao().countByTrips(ids).associate { it.tripId to it.count }
        val stayCounts = db.stayPointDao().countByTrips(ids).associate { it.tripId to it.count }

        val now = System.currentTimeMillis()
        return trips.map { trip ->
            val points = pointsByTrip[trip.id].orEmpty()
            val pauses = pausesByTrip[trip.id].orEmpty().map { it.pausedAt to it.resumedAt }
            TripListItem(
                trip = trip,
                stats = TripStatsCalculator.compute(trip, points, pauses, now),
                segmentCount = segmentCounts[trip.id] ?: 0,
                stayCount = stayCounts[trip.id] ?: 0,
            )
        }
    }

    /**
     * 导出用：按时间正序取出**全部**行程的完整数据。
     * 与列表页不同，这里要的是每条行程的点和分段，所以确实会读全量——
     * 只由「导出全部」手动触发，不在界面自动跑。
     */
    suspend fun listAllForExport(): List<TripDetail> {
        val trips = db.tripDao().listOnce(Int.MAX_VALUE).sortedBy { it.startedAt }
        return trips.mapNotNull { detail(it.id) }
    }

    suspend fun detail(tripId: Long): TripDetail? {
        val trip = db.tripDao().findById(tripId) ?: return null
        val points = db.trackPointDao().getByTrip(tripId)
        val segments = db.tripSegmentDao().getByTrip(tripId)
        val stays = db.stayPointDao().getByTrip(tripId)
        val pauses = db.tripPauseDao().getByTrip(tripId).map { it.pausedAt to it.resumedAt }
        val stats = TripStatsCalculator.compute(trip, points, pauses, System.currentTimeMillis())
        return TripDetail(trip, points, segments, stays, stats)
    }

    /**
     * 删一条行程。
     *
     * 子表都是 CASCADE，删 trip 一行就够；这里额外把挂在这条行程上的 AI 消息
     * 也清掉（虽然目前不会写入 tripId，但删行程时留着无主引用没意义）。
     */
    suspend fun delete(tripId: Long) = db.withTransaction {
        db.aiChatDao().deleteByTrip(tripId)
        db.tripDao().deleteById(tripId)
    }
}
