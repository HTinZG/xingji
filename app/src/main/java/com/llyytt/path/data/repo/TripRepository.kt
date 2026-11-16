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

    suspend fun list(limit: Int = 100): List<TripListItem> {
        val trips = db.tripDao().listOnce(limit)
        val now = System.currentTimeMillis()
        return trips.map { trip ->
            val points = db.trackPointDao().getByTrip(trip.id)
            val pauses = db.tripPauseDao().getByTrip(trip.id).map { it.pausedAt to it.resumedAt }
            TripListItem(
                trip = trip,
                stats = TripStatsCalculator.compute(trip, points, pauses, now),
                segmentCount = db.tripSegmentDao().countByTrip(trip.id),
                stayCount = db.stayPointDao().countByTrip(trip.id),
            )
        }
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
