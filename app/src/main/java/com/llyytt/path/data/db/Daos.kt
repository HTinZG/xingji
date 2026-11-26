package com.llyytt.path.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {

    @Insert
    suspend fun insert(trip: TripEntity): Long

    @Update
    suspend fun update(trip: TripEntity)

    @Query("SELECT * FROM trip WHERE id = :id")
    suspend fun findById(id: Long): TripEntity?

    @Query("SELECT * FROM trip ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trip ORDER BY startedAt DESC LIMIT :limit")
    suspend fun listOnce(limit: Int): List<TripEntity>

    /** 进程被杀后重启时用它找回「没结束的行程」。最多一条（由业务层保证）。 */
    @Query("SELECT * FROM trip WHERE status != 'FINISHED' ORDER BY startedAt DESC LIMIT 1")
    suspend fun findUnfinished(): TripEntity?

    @Query("SELECT * FROM trip WHERE status != 'FINISHED' ORDER BY startedAt DESC LIMIT 1")
    fun observeUnfinished(): Flow<TripEntity?>

    @Query("SELECT COUNT(*) FROM trip WHERE status != 'FINISHED'")
    suspend fun countUnfinished(): Int

    /**
     * 删行程。子表（track_point / trip_segment / stay_point / trip_pause）
     * 都声明了 onDelete = CASCADE，所以这一条 SQL 会连带删干净。
     */
    @Query("DELETE FROM trip WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE trip SET status = 'FINISHED', endedAt = :endedAt, updatedAt = :endedAt WHERE status != 'FINISHED'")
    suspend fun finishAllUnfinished(endedAt: Long)

    @Query("DELETE FROM trip")
    suspend fun deleteAll()
}

@Dao
interface TrackPointDao {

    @Insert
    suspend fun insert(point: TrackPointEntity): Long

    @Insert
    suspend fun insertAll(points: List<TrackPointEntity>)

    @Query("SELECT * FROM track_point WHERE tripId = :tripId ORDER BY seq ASC")
    suspend fun getByTrip(tripId: Long): List<TrackPointEntity>

    /**
     * 一次取出多条行程的所有点。
     * 列表页以前是「每条行程查一次」，50 条行程就是 50 次查询，
     * 而且每次都把全部点读进内存——行程一多就明显卡。
     */
    @Query("SELECT * FROM track_point WHERE tripId IN (:tripIds) ORDER BY tripId, seq")
    suspend fun getByTrips(tripIds: List<Long>): List<TrackPointEntity>

    @Query("SELECT * FROM track_point WHERE tripId = :tripId ORDER BY seq ASC")
    fun observeByTrip(tripId: Long): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM track_point WHERE tripId = :tripId ORDER BY seq DESC LIMIT 1")
    suspend fun lastPoint(tripId: Long): TrackPointEntity?

    @Query("SELECT COUNT(*) FROM track_point WHERE tripId = :tripId")
    suspend fun count(tripId: Long): Int

    @Query("SELECT MAX(seq) FROM track_point WHERE tripId = :tripId")
    suspend fun maxSeq(tripId: Long): Int?

    @Query("DELETE FROM track_point WHERE tripId = :tripId")
    suspend fun deleteByTrip(tripId: Long)
}

@Dao
interface TripSegmentDao {

    @Insert
    suspend fun insertAll(segments: List<TripSegmentEntity>)

    @Insert
    suspend fun insert(segment: TripSegmentEntity): Long

    @Update
    suspend fun update(segment: TripSegmentEntity)

    @Query("DELETE FROM trip_segment WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM trip_segment WHERE id = :id")
    suspend fun findById(id: Long): TripSegmentEntity?

    @Query("SELECT * FROM trip_segment WHERE tripId = :tripId ORDER BY seq ASC")
    suspend fun getByTrip(tripId: Long): List<TripSegmentEntity>

    @Query("SELECT COUNT(*) FROM trip_segment WHERE tripId = :tripId")
    suspend fun countByTrip(tripId: Long): Int

    @Query("SELECT tripId, COUNT(*) AS count FROM trip_segment WHERE tripId IN (:tripIds) GROUP BY tripId")
    suspend fun countByTrips(tripIds: List<Long>): List<TripIdCount>

    @Query("SELECT * FROM trip_segment WHERE tripId = :tripId ORDER BY seq ASC")
    fun observeByTrip(tripId: Long): Flow<List<TripSegmentEntity>>

    @Query("DELETE FROM trip_segment WHERE tripId = :tripId")
    suspend fun deleteByTrip(tripId: Long)
}

@Dao
interface StayPointDao {

    @Insert
    suspend fun insert(point: StayPointEntity): Long

    @Insert
    suspend fun insertAll(points: List<StayPointEntity>)

    @Query("SELECT * FROM stay_point WHERE tripId = :tripId ORDER BY arriveAt ASC")
    suspend fun getByTrip(tripId: Long): List<StayPointEntity>

    @Query("SELECT COUNT(*) FROM stay_point WHERE tripId = :tripId")
    suspend fun countByTrip(tripId: Long): Int

    @Query("SELECT tripId, COUNT(*) AS count FROM stay_point WHERE tripId IN (:tripIds) GROUP BY tripId")
    suspend fun countByTrips(tripIds: List<Long>): List<TripIdCount>

    @Query("SELECT * FROM stay_point WHERE tripId = :tripId ORDER BY arriveAt ASC")
    fun observeByTrip(tripId: Long): Flow<List<StayPointEntity>>

    @Query("DELETE FROM stay_point WHERE tripId = :tripId")
    suspend fun deleteByTrip(tripId: Long)
}

@Dao
interface TripPauseDao {

    @Insert
    suspend fun insert(pause: TripPauseEntity): Long

    /** 暂停中那条（resumedAt = null）。正常情况最多一条。 */
    @Query("SELECT * FROM trip_pause WHERE tripId = :tripId AND resumedAt IS NULL ORDER BY pausedAt DESC LIMIT 1")
    suspend fun findOpen(tripId: Long): TripPauseEntity?

    @Query("SELECT * FROM trip_pause WHERE tripId = :tripId ORDER BY pausedAt ASC")
    suspend fun getByTrip(tripId: Long): List<TripPauseEntity>

    @Query("SELECT * FROM trip_pause WHERE tripId IN (:tripIds)")
    suspend fun getByTrips(tripIds: List<Long>): List<TripPauseEntity>

    /** 把所有未闭合的暂停一起闭合（修复脏状态用） */
    @Query("UPDATE trip_pause SET resumedAt = :resumedAt WHERE tripId = :tripId AND resumedAt IS NULL")
    suspend fun closeAllOpen(tripId: Long, resumedAt: Long)

    @Query("DELETE FROM trip_pause WHERE tripId = :tripId")
    suspend fun deleteByTrip(tripId: Long)
}

/** 批量统计用的投影：某条行程有多少行。 */
data class TripIdCount(
    val tripId: Long,
    val count: Int,
)
