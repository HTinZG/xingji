package com.llyytt.path.core.geo

import kotlin.math.abs
import kotlin.math.cos

/**
 * 折线抽稀（Douglas-Peucker）。
 *
 * 为什么要抽稀：一小时步行能存 2000+ 个点，直接丢给地图 SDK 画线会掉帧，
 * 而且视觉上根本看不出差别。抽稀后通常能降到 1/10。
 *
 * 注意：抽稀只用于**渲染**，数据库里的原始点一个都不删。
 * 距离计算也基于原始点，不用抽稀后的。
 */
object PolylineSimplifier {

    /** 默认容差（米）。2 米大约等于一个手指宽，肉眼看不出。 */
    const val DEFAULT_EPSILON_METERS = 2.0

    fun simplify(points: List<Wgs84>, epsilonMeters: Double = DEFAULT_EPSILON_METERS): List<Wgs84> {
        if (points.size <= 2) return points

        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true

        // 用显式栈代替递归，避免长轨迹把栈打爆
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))

        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            if (end <= start + 1) continue

            var maxDist = 0.0
            var maxIndex = -1
            for (i in start + 1 until end) {
                val d = perpendicularDistanceMeters(points[i], points[start], points[end])
                if (d > maxDist) {
                    maxDist = d
                    maxIndex = i
                }
            }

            if (maxDist > epsilonMeters && maxIndex > 0) {
                keep[maxIndex] = true
                stack.addLast(intArrayOf(start, maxIndex))
                stack.addLast(intArrayOf(maxIndex, end))
            }
        }

        val result = ArrayList<Wgs84>(keep.count { it })
        for (i in points.indices) if (keep[i]) result.add(points[i])
        return result
    }

    /** 点到线段的垂距（米）。用等距圆柱投影近似，城市尺度误差可忽略。 */
    fun perpendicularDistanceMeters(p: Wgs84, a: Wgs84, b: Wgs84): Double {
        val lat0 = Math.toRadians((a.lat + b.lat) / 2.0)
        val kx = 111_320.0 * cos(lat0)
        val ky = 110_540.0

        val px = (p.lng - a.lng) * kx
        val py = (p.lat - a.lat) * ky
        val bx = (b.lng - a.lng) * kx
        val by = (b.lat - a.lat) * ky

        val lenSquared = bx * bx + by * by
        if (lenSquared == 0.0) return Math.hypot(px, py)

        var t = (px * bx + py * by) / lenSquared
        t = t.coerceIn(0.0, 1.0)
        return Math.hypot(px - t * bx, py - t * by)
    }

    /** 估算抽稀能省多少点，用于日志。 */
    fun savings(before: Int, after: Int): String =
        if (before == 0) "0%" else "${100 - after * 100 / before}%"
}
