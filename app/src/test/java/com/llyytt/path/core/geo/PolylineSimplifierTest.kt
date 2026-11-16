package com.llyytt.path.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineSimplifierTest {

    private fun p(lat: Double, lng: Double) = Wgs84(lat, lng)

    @Test
    fun 直线上的中间点会被全部去掉() {
        // 一条向北的直线，10 个点共线
        val line = (0..9).map { p(39.9 + it * 0.0001, 116.4) }
        val out = PolylineSimplifier.simplify(line, 2.0)
        assertEquals(2, out.size)
        assertEquals(line.first(), out.first())
        assertEquals(line.last(), out.last())
    }

    @Test
    fun 拐点会被保留() {
        // 先向北 100m，再向东 100m
        val pts = listOf(
            p(39.9000, 116.4000),
            p(39.9009, 116.4000), // 拐点
            p(39.9009, 116.4012),
        )
        val out = PolylineSimplifier.simplify(pts, 2.0)
        assertEquals(3, out.size)
    }

    @Test
    fun 轻微抖动会被抹平() {
        // 直线 + 1m 抖动，容差 2m 应该全去掉
        val pts = listOf(
            p(39.9000, 116.4000),
            p(39.9002, 116.400009),
            p(39.9004, 116.4000),
            p(39.9006, 116.4000),
        )
        val out = PolylineSimplifier.simplify(pts, 2.0)
        assertEquals(2, out.size)
    }

    @Test
    fun 少于三个点原样返回() {
        val pts = listOf(p(39.9, 116.4))
        assertEquals(pts, PolylineSimplifier.simplify(pts))
        val two = listOf(p(39.9, 116.4), p(39.91, 116.41))
        assertEquals(two, PolylineSimplifier.simplify(two))
    }

    @Test
    fun 垂距计算符合几何直觉() {
        // 赤道附近，1e-4 度约 11.13m
        val a = p(0.0, 0.0)
        val b = p(0.0, 0.001)
        val mid = p(0.0001, 0.0005)
        val d = PolylineSimplifier.perpendicularDistanceMeters(mid, a, b)
        assertEquals(11.05, d, 0.5)
    }

    @Test
    fun 抽稀后首尾点必须保留() {
        val pts = (0..50).map { p(39.9 + it * 0.00005, 116.4 + it * 0.00007) }
        val out = PolylineSimplifier.simplify(pts, 5.0)
        assertEquals(pts.first(), out.first())
        assertEquals(pts.last(), out.last())
        assertTrue("抽稀后点数应少于原始", out.size < pts.size)
    }
}
