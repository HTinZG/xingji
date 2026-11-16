package com.llyytt.path.core.geo

import kotlin.math.hypot

/** 屏幕像素坐标（左上角原点，y 向下）。纯数据，不依赖 Android / 地图 SDK。 */
data class ScreenPoint(val x: Float, val y: Float)

/**
 * 折线命中判定的纯几何工具。
 *
 * 为什么不用地图 SDK 自带的折线点击：
 * 高德 11.x 的 PolylineOptions 没有 clickable 参数，setOnPolylineClickListener 实际不触发。
 * 所以统一由「地图点击回调 + 屏幕像素距离」自己判定，引擎无关、可单测。
 *
 * 设计约定：调用方负责把经纬度投影成 ScreenPoint（这一步依赖具体引擎），
 * 本类只做纯几何，方便第 4/5 步在分段选择里复用。
 */
object PolylineHitTest {

    /** 手指按在线附近就算选中：48px，520dpi 下约 14dp。 */
    const val DEFAULT_TOLERANCE_PX = 48f

    /** 点到线段的最短距离（像素）。 */
    fun distanceToSegment(p: ScreenPoint, a: ScreenPoint, b: ScreenPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSquared = dx * dx + dy * dy
        if (lenSquared == 0f) return hypot(p.x - a.x, p.y - a.y)

        var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSquared
        t = t.coerceIn(0f, 1f)
        val cx = a.x + t * dx
        val cy = a.y + t * dy
        return hypot(p.x - cx, p.y - cy)
    }

    /** 点到整条折线的最短距离。点少于 2 个时退化成点距。 */
    fun distanceToPolyline(p: ScreenPoint, points: List<ScreenPoint>): Float {
        if (points.isEmpty()) return Float.MAX_VALUE
        if (points.size == 1) return hypot(p.x - points[0].x, p.y - points[0].y)

        var best = Float.MAX_VALUE
        for (i in 1 until points.size) {
            val d = distanceToSegment(p, points[i - 1], points[i])
            if (d < best) best = d
        }
        return best
    }

    /**
     * 在一组折线里找离点击点最近的一条。
     * @return 命中的下标；没有命中返回 -1
     */
    fun nearestPolyline(
        p: ScreenPoint,
        polylines: List<List<ScreenPoint>>,
        tolerancePx: Float = DEFAULT_TOLERANCE_PX,
    ): Int {
        var bestIndex = -1
        var best = Float.MAX_VALUE
        polylines.forEachIndexed { index, points ->
            val d = distanceToPolyline(p, points)
            if (d < best) {
                best = d
                bestIndex = index
            }
        }
        return if (best <= tolerancePx) bestIndex else -1
    }
}
