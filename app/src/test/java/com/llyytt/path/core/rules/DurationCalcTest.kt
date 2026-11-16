package com.llyytt.path.core.rules

import com.llyytt.path.data.db.TripStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DurationCalcTest {

    private val t0 = 1_000_000L

    @Test
    fun 进行中_没有暂停时等于墙上时间() {
        val got = DurationCalc.activeMillis(
            startedAt = t0,
            endedAt = null,
            status = TripStatus.RECORDING,
            pauses = emptyList(),
            now = t0 + 60_000,
        )
        assertEquals(60_000L, got)
    }

    @Test
    fun 进行中_扣掉已闭合的暂停() {
        // 走了 60s，中间暂停 10s
        val got = DurationCalc.activeMillis(
            startedAt = t0,
            endedAt = null,
            status = TripStatus.RECORDING,
            pauses = listOf(t0 + 20_000 to t0 + 30_000),
            now = t0 + 60_000,
        )
        assertEquals(50_000L, got)
    }

    @Test
    fun 暂停中_未闭合的暂停用now扣() {
        // 走了 20s 后暂停，现在暂停了 30s
        val got = DurationCalc.activeMillis(
            startedAt = t0,
            endedAt = null,
            status = TripStatus.PAUSED,
            pauses = listOf(t0 + 20_000 to null),
            now = t0 + 50_000,
        )
        assertEquals(20_000L, got)
    }

    @Test
    fun 已结束_用endedAt而不是now() {
        val got = DurationCalc.activeMillis(
            startedAt = t0,
            endedAt = t0 + 100_000,
            status = TripStatus.FINISHED,
            pauses = listOf(t0 + 40_000 to t0 + 50_000),
            now = t0 + 9_999_999, // now 再大也不该影响结果
        )
        assertEquals(90_000L, got)
    }

    @Test
    fun 多次暂停全部扣除() {
        val got = DurationCalc.activeMillis(
            startedAt = t0,
            endedAt = t0 + 100_000,
            status = TripStatus.FINISHED,
            pauses = listOf(
                t0 + 10_000 to t0 + 20_000,
                t0 + 50_000 to t0 + 55_000,
            ),
            now = t0 + 100_000,
        )
        assertEquals(85_000L, got)
    }

    @Test
    fun 脏数据不会算出负数() {
        val got = DurationCalc.activeMillis(
            startedAt = t0,
            endedAt = t0 + 10_000,
            status = TripStatus.FINISHED,
            pauses = listOf(t0 - 5_000 to t0 + 60_000),
            now = t0 + 10_000,
        )
        assertEquals(0L, got)
    }
}
