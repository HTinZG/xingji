package com.llyytt.path.core.rules

import com.llyytt.path.core.geo.Wgs84
import com.llyytt.path.data.db.PointSource
import com.llyytt.path.data.db.TrackPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedDisplayTest {

    // ---------- 中位数 ----------

    @Test
    fun 奇数个取中间值() {
        assertEquals(2.0, SpeedSmoother.median(listOf(1.0, 2.0, 3.0)), 0.0001)
    }

    @Test
    fun 偶数个取中间两个的平均() {
        assertEquals(2.5, SpeedSmoother.median(listOf(1.0, 2.0, 3.0, 4.0)), 0.0001)
    }

    @Test
    fun 空集合返回零() {
        assertEquals(0.0, SpeedSmoother.median(emptyList()), 0.0001)
    }

    // ---------- 平滑 ----------

    @Test
    fun 单次跳点不会带偏显示() {
        val smoother = SpeedSmoother(windowSize = 5)
        var last = smoother.add(1.0, 0)
        last = smoother.add(1.0, 1000)
        last = smoother.add(1.0, 2000)
        last = smoother.add(1.0, 3000)
        last = smoother.add(40.0, 4000) // GPS 跳点
        // 5 个样本的中位数仍然是 1.0
        assertEquals(1.0, last.smoothedMps, 0.0001)
    }

    @Test
    fun 窗口只保留最近几个样本() {
        val smoother = SpeedSmoother(windowSize = 3)
        smoother.add(10.0, 0)
        smoother.add(10.0, 1000)
        smoother.add(2.0, 2000)
        val snap = smoother.add(2.0, 3000)
        // 最近 3 个是 10, 2, 2 -> 中位数 2
        assertEquals(2.0, snap.smoothedMps, 0.0001)
    }

    // ---------- 静止判定 ----------

    @Test
    fun 低于阈值但不满五秒不算静止() {
        val smoother = SpeedSmoother()
        smoother.add(0.1, 0)
        smoother.add(0.1, 2000)
        val snap = smoother.add(0.1, 4000)
        assertFalse(snap.stationary)
    }

    @Test
    fun 持续低于阈值超过五秒判定为静止() {
        val smoother = SpeedSmoother()
        smoother.add(0.1, 0)
        smoother.add(0.1, 2000)
        smoother.add(0.1, 4000)
        val snap = smoother.add(0.1, 5000)
        assertTrue(snap.stationary)
    }

    @Test
    fun 速度回升后静止状态立即解除() {
        // 非对称判定：中位数可能还是低的，但只要本次原始样本达到阈值就立即解除。
        val smoother = SpeedSmoother()
        smoother.add(0.1, 0)
        smoother.add(0.1, 3000)
        smoother.add(0.1, 6000) // 此时已静止
        val moving = smoother.add(3.0, 7000)
        assertFalse("走上来了就该立刻解除静止", moving.stationary)
    }

    @Test
    fun 解除后需要重新累计五秒才会再次静止() {
        val smoother = SpeedSmoother()
        smoother.add(0.1, 0)
        smoother.add(0.1, 6000) // 已静止
        smoother.add(3.0, 7000) // 解除
        val snap = smoother.add(0.1, 8000)
        assertFalse("计时重置后 1 秒不算静止", snap.stationary)
    }

    @Test
    fun 重置后窗口与静止计时都清空() {
        val smoother = SpeedSmoother()
        smoother.add(0.1, 0)
        smoother.add(0.1, 6000)
        smoother.reset()
        val snap = smoother.add(0.1, 6500)
        assertFalse("reset 后应重新计时", snap.stationary)
    }

    // ---------- 取数 ----------

    @Test
    fun 优先使用系统速度() {
        val s = SpeedSampler.sample(
            previousPoint = Wgs84(39.9, 116.4),
            previousTimeMillis = 0,
            currentPoint = Wgs84(39.9, 116.5),
            currentTimeMillis = 10_000,
            systemSpeedMps = 2.5f,
            accuracyMeters = 5f,
        )
        assertEquals(2.5, s!!, 0.0001)
    }

    @Test
    fun 系统没给速度时用距离除时间() {
        // 赤道附近 0.001 度经度约 111.3m，10 秒 -> 约 11.1 m/s
        val s = SpeedSampler.sample(
            previousPoint = Wgs84(0.0, 0.0),
            previousTimeMillis = 0,
            currentPoint = Wgs84(0.0, 0.001),
            currentTimeMillis = 10_000,
            systemSpeedMps = null,
            accuracyMeters = 5f,
        )
        assertEquals(11.1, s!!, 0.3)
    }

    @Test
    fun 精度太差的点不参与速度计算() {
        val s = SpeedSampler.sample(
            previousPoint = Wgs84(0.0, 0.0),
            previousTimeMillis = 0,
            currentPoint = Wgs84(0.0, 0.001),
            currentTimeMillis = 10_000,
            systemSpeedMps = 5f,
            accuracyMeters = Tuning.Filter.MAX_ACCURACY_M + 1f,
        )
        assertNull(s)
    }

    @Test
    fun 没有上一点且系统没给速度时返回空() {
        val s = SpeedSampler.sample(
            previousPoint = null,
            previousTimeMillis = null,
            currentPoint = Wgs84(0.0, 0.001),
            currentTimeMillis = 10_000,
            systemSpeedMps = null,
            accuracyMeters = 5f,
        )
        assertNull(s)
    }

    @Test
    fun 时间差太小不算速度() {
        val s = SpeedSampler.sample(
            previousPoint = Wgs84(0.0, 0.0),
            previousTimeMillis = 1000,
            currentPoint = Wgs84(0.0, 0.001),
            currentTimeMillis = 1100,
            systemSpeedMps = null,
            accuracyMeters = 5f,
        )
        assertNull(s)
    }

    // ---------- 离线序列（详情页回放用） ----------

    @Test
    fun 离线序列在中断处重新开窗() {
        val points = listOf(
            point(seq = 0, t = 0, speed = 2.0f, newSegment = true),
            point(seq = 1, t = 1000, speed = 2.0f),
            point(seq = 2, t = 2000, speed = 2.0f, newSegment = true),
        )
        val series = SpeedSeries.smoothed(points)
        assertEquals(3, series.size)
        assertEquals(2.0, series[0].smoothedMps!!, 0.0001)
        assertEquals(2.0, series[1].smoothedMps!!, 0.0001)
        // 中断后重新开窗，仍按自己的样本算
        assertEquals(2.0, series[2].smoothedMps!!, 0.0001)
    }

    @Test
    fun 离线序列里精度差的点输出空() {
        val points = listOf(
            point(seq = 0, t = 0, speed = 2.0f, newSegment = true, accuracy = 5f),
            point(seq = 1, t = 1000, speed = 2.0f, accuracy = 500f),
        )
        val series = SpeedSeries.smoothed(points)
        assertNull(series[1].smoothedMps)
    }

    private fun point(
        seq: Int,
        t: Long,
        speed: Float?,
        newSegment: Boolean = false,
        accuracy: Float = 5f,
    ) = TrackPointEntity(
        id = seq.toLong(),
        tripId = 1,
        seq = seq,
        lat = 39.9 + seq * 0.0001,
        lng = 116.4,
        speedMps = speed,
        accuracyM = accuracy,
        recordedAt = t,
        source = PointSource.GNSS,
        startsNewSegment = newSegment,
    )
}
