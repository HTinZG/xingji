package com.llyytt.path.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PolylineHitTest 的单元测试。
 *
 * 这段几何逻辑在真机上错过两次（先用 SDK 回调失效，后只算顶点距离导致边中点选不中），
 * 所以用纯 JVM 测试锁死，避免第 4/5 步再踩。
 */
class PolylineHitTestTest {

    @Test
    fun 垂直距离等于线段到点的垂距() {
        val d = PolylineHitTest.distanceToSegment(
            p = ScreenPoint(5f, 5f),
            a = ScreenPoint(0f, 0f),
            b = ScreenPoint(10f, 0f),
        )
        assertEquals(5f, d, 0.001f)
    }

    @Test
    fun 点落在端点上时距离为零() {
        val d = PolylineHitTest.distanceToSegment(
            p = ScreenPoint(10f, 0f),
            a = ScreenPoint(0f, 0f),
            b = ScreenPoint(10f, 0f),
        )
        assertEquals(0f, d, 0.001f)
    }

    @Test
    fun 超出线段两端时退化为端点距离() {
        val d = PolylineHitTest.distanceToSegment(
            p = ScreenPoint(20f, 0f),
            a = ScreenPoint(0f, 0f),
            b = ScreenPoint(10f, 0f),
        )
        assertEquals(10f, d, 0.001f)
    }

    @Test
    fun 退化线段不会除零() {
        val d = PolylineHitTest.distanceToSegment(
            p = ScreenPoint(3f, 4f),
            a = ScreenPoint(0f, 0f),
            b = ScreenPoint(0f, 0f),
        )
        assertEquals(5f, d, 0.001f)
    }

    @Test
    fun 点在边中点也能命中折线() {
        // 这正是第 2 步真机上的失败场景：点在边的中间，不是顶点。
        // 取边 (0,0)-(100,100) 的中点 (50,50) 附近，偏 2px。
        val polyline = listOf(
            ScreenPoint(0f, 0f),
            ScreenPoint(100f, 100f),
            ScreenPoint(200f, 0f),
        )
        val d = PolylineHitTest.distanceToPolyline(ScreenPoint(50f, 52f), polyline)
        assertTrue("边中点应判定为在线附近，实际距离 $d", d < 5f)
        assertTrue("且必须落在默认阈值内", d < PolylineHitTest.DEFAULT_TOLERANCE_PX)
    }

    @Test
    fun V形折线开口外的点不应命中() {
        // 同样一条 V 形，取开口下方（顶点正下方）的点，它离两段边都很远
        val polyline = listOf(
            ScreenPoint(0f, 0f),
            ScreenPoint(100f, 100f),
            ScreenPoint(200f, 0f),
        )
        val d = PolylineHitTest.distanceToPolyline(ScreenPoint(100f, 2f), polyline)
        assertTrue("开口外的点不该算命中，实际距离 $d", d > PolylineHitTest.DEFAULT_TOLERANCE_PX)
    }

    @Test
    fun 在多条折线里选中最近的那条() {
        val a = listOf(ScreenPoint(0f, 0f), ScreenPoint(100f, 0f))
        val b = listOf(ScreenPoint(0f, 300f), ScreenPoint(100f, 300f))
        val hit = PolylineHitTest.nearestPolyline(ScreenPoint(50f, 310f), listOf(a, b))
        assertEquals(1, hit)
    }

    @Test
    fun 离所有折线都远时返回负一() {
        val a = listOf(ScreenPoint(0f, 0f), ScreenPoint(100f, 0f))
        val hit = PolylineHitTest.nearestPolyline(ScreenPoint(50f, 500f), listOf(a))
        assertEquals(-1, hit)
    }
}
