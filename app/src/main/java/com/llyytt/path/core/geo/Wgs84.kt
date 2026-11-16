package com.llyytt.path.core.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84 坐标：本 App 内部唯一合法的坐标类型。
 *
 * 三条铁律（破坏任意一条，导出文件就会永久偏移）：
 * 1. 系统 GNSS 拿到的原始值就是 WGS84，直接构造本类，不做任何偏移加减。
 * 2. 高德的 GCJ-02、百度的 BD-09 只允许出现在 mapimpl 包内部，
 *    绝不允许出现在这里、数据库里、导出文件里。
 * 3. 导出 GPX / KML 时直接写 lat/lng —— 这两个格式的规范坐标就是 WGS84。
 */
data class Wgs84(val lat: Double, val lng: Double) {

    val isValid: Boolean
        get() = lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)

    /** 球面距离（米）。轨迹统计一律走这里，不用平面近似。 */
    fun distanceTo(other: Wgs84): Double {
        val earthRadius = 6371008.8
        val dLat = Math.toRadians(other.lat - lat)
        val dLng = Math.toRadians(other.lng - lng)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) *
            sin(dLng / 2) * sin(dLng / 2)
        return 2 * earthRadius * asin(sqrt(a))
    }
}

/** 包含一组点的最小经纬度矩形，用于“缩放到全部轨迹”。 */
data class Wgs84Bounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    companion object {
        fun of(points: List<Wgs84>): Wgs84Bounds? {
            if (points.isEmpty()) return null
            var s = points[0].lat
            var n = points[0].lat
            var w = points[0].lng
            var e = points[0].lng
            for (p in points) {
                if (p.lat < s) s = p.lat
                if (p.lat > n) n = p.lat
                if (p.lng < w) w = p.lng
                if (p.lng > e) e = p.lng
            }
            return Wgs84Bounds(s, w, n, e)
        }
    }
}
