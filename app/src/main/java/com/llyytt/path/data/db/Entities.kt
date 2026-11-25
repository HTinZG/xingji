package com.llyytt.path.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/** 行程状态。 */
enum class TripStatus { RECORDING, PAUSED, FINISHED }

/**
 * 交通方式。
 *
 * 刻意**没有 STAY**：停留不是一种「交通方式」，它由 stay_point 独立表达。
 * UNKNOWN 表示分段算法还没跑过。
 */
enum class TransportMode { UNKNOWN, WALK, BIKE, CAR }

/** 这个点是哪个定位源产出的。将来接入高德定位后，坐标变换可审计。 */
/**
 * 点的来源。
 *
 * NETWORK：系统网络定位（WiFi/基站）。只在还没拿到 GPS 点之前用来兜底——
 * 室内 GPS 完全没信号，但网络定位能找到几十米量级的位置，
 * 总比一个点都没有（用户以为"搜不到星"）强。
 * 一旦拿到第一个 GPS 点就不再收网络点，避免精度污染轨迹。
 */
enum class PointSource { GNSS, NETWORK, AMAP_LOCATION }

/**
 * 行程。
 *
 * 不缓存距离和时长：距离从 track_point 实时算，时长按 DurationCalc 从
 * trip 的起止时间 + trip_pause 算。理由：手动修正分段后数字必须自动正确，
 * 缓存字段早晚会和真值漂移。
 */
@Entity(tableName = "trip")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val status: TripStatus = TripStatus.RECORDING,
    val title: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 轨迹点。
 *
 * 不变量：同一 trip 内 seq 必须 0..N-1 连续，入库后不修改、不删除。
 * 坐标永远是 WGS84。
 */
@Entity(
    tableName = "track_point",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tripId", "seq"], unique = true)],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val seq: Int,
    val lat: Double,
    val lng: Double,
    val altitudeM: Double? = null,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val accuracyM: Float? = null,
    val recordedAt: Long,
    val source: PointSource = PointSource.GNSS,
    /**
     * 这个点之前有中断（暂停或丢信号）。**只影响画线断开**，
     * 不强制这里必须切分段。
     */
    val startsNewSegment: Boolean = false,
)

/**
 * 分段结果。用「点索引范围」关联，不用外键列表。
 *
 * 拆分 = 改两行数字；合并 = 删一行改一行数字。track_point 永远不动。
 * 唯一约束放在 (tripId, startPointSeq)：同一行程里不允许两段起点相同。
 * 不对 (tripId, seq) 设唯一——拆分合并时 seq 要重排，唯一约束会很别扭。
 */
@Entity(
    tableName = "trip_segment",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["tripId", "startPointSeq"], unique = true),
        Index(value = ["tripId", "seq"]),
    ],
)
data class TripSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val seq: Int,
    val mode: TransportMode,
    /** 起点 seq（含） */
    val startPointSeq: Int,
    /** 终点 seq（含） */
    val endPointSeq: Int,
    val manuallyEdited: Boolean = false,
)

/** 停留点。segment 里没有 STAY，停留只在这里。 */
@Entity(
    tableName = "stay_point",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tripId", "arriveAt"])],
)
data class StayPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Double,
    val arriveAt: Long,
    val leaveAt: Long? = null,
)

/**
 * 暂停记录。
 *
 * 状态不变量（第 5 步校验函数会检查）：
 * - PAUSED 时：有且仅有一条 resumedAt = null 的记录
 * - RECORDING 时：不允许存在 resumedAt = null 的记录
 * - FINISHED 时：所有记录都已闭合，且 trip.endedAt 非空
 */
@Entity(
    tableName = "trip_pause",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tripId", "pausedAt"])],
)
data class TripPauseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val pausedAt: Long,
    /** null = 还在暂停中 */
    val resumedAt: Long? = null,
)

class Converters {
    @TypeConverter
    fun tripStatusToString(value: TripStatus): String = value.name

    @TypeConverter
    fun stringToTripStatus(value: String): TripStatus =
        runCatching { TripStatus.valueOf(value) }.getOrDefault(TripStatus.FINISHED)

    @TypeConverter
    fun transportModeToString(value: TransportMode): String = value.name

    @TypeConverter
    fun stringToTransportMode(value: String): TransportMode =
        runCatching { TransportMode.valueOf(value) }.getOrDefault(TransportMode.UNKNOWN)

    @TypeConverter
    fun pointSourceToString(value: PointSource): String = value.name

    @TypeConverter
    fun stringToPointSource(value: String): PointSource =
        runCatching { PointSource.valueOf(value) }.getOrDefault(PointSource.GNSS)
}
