package com.llyytt.path.core.rules

import com.llyytt.path.data.db.TripStatus

/**
 * 活动时长计算。不缓存，每次按当前状态现算。
 *
 * 三种状态：
 * - RECORDING：end = now（endedAt 为空），只扣已闭合的暂停
 * - PAUSED   ：end = now，未闭合的暂停用 now 当 resumedAt，等于把「正在暂停」这段扣掉
 * - FINISHED ：end = endedAt，全部暂停都已闭合；万一有脏数据用 end 兜底
 */
object DurationCalc {

    fun activeMillis(
        startedAt: Long,
        endedAt: Long?,
        status: TripStatus,
        /** (pausedAt, resumedAt?)；resumedAt 为 null 表示还没恢复 */
        pauses: List<Pair<Long, Long?>>,
        now: Long,
    ): Long {
        val end = endedAt ?: now
        var total = end - startedAt
        if (total <= 0) return 0

        for ((pausedAt, resumedAt) in pauses) {
            val pauseEnd = resumedAt ?: when (status) {
                TripStatus.PAUSED -> now
                else -> end
            }
            val duration = pauseEnd - pausedAt
            if (duration > 0) total -= duration
        }
        return total.coerceAtLeast(0L)
    }

    /** 墙上时间（含暂停）。详情页展示「总共过了多久」用。 */
    fun wallMillis(startedAt: Long, endedAt: Long?, now: Long): Long {
        val end = endedAt ?: now
        return (end - startedAt).coerceAtLeast(0L)
    }
}
