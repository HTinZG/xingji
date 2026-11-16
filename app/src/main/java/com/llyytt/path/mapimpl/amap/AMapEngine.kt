package com.llyytt.path.mapimpl.amap

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.MapView as AMapMapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.llyytt.path.core.geo.PolylineHitTest
import com.llyytt.path.core.geo.ScreenPoint
import com.llyytt.path.core.geo.Wgs84
import com.llyytt.path.core.geo.Wgs84Bounds
import com.llyytt.path.map.MapController
import com.llyytt.path.map.MapEngine
import com.llyytt.path.map.MapPolyline
import com.llyytt.path.map.MapScene
import com.llyytt.path.map.OfflineCapability
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 高德 3D 地图实现。整个 App 里唯一 import com.amap 的地方。
 *
 * 坐标职责：业务层给的永远是 Wgs84；进这里之后由本类转成 GCJ-02 再交给 SDK。
 * 转出去的方向只有“出”：SDK 的 LatLng 绝不回流成 Wgs84 进业务层。
 */
class AMapEngine(private val appContext: Context) : MapEngine {

    override val id: String = "amap"

    override val offlineCapability: OfflineCapability = OfflineCapability.CACHE_ONLY

    override fun createController(): MapController = AMapController(appContext)

    @Composable
    override fun MapView(controller: MapController, modifier: Modifier) {
        val c = controller as AMapController
        val density = LocalDensity.current.density
        // 用 LocalContext 反查 LifecycleOwner，避免依赖 lifecycle-runtime-compose 这个额外库
        val lifecycleOwner = LocalContext.current as? LifecycleOwner

        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                AMapMapView(ctx).apply {
                    onCreate(Bundle())
                    onResume()
                    c.attach(this, density)
                }
            },
            onRelease = { view ->
                c.detach()
                view.onDestroy()
            },
        )

        if (lifecycleOwner != null) {
            DisposableEffect(lifecycleOwner, c) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> c.resumeMap()
                        Lifecycle.Event.ON_PAUSE -> c.pauseMap()
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
        }
    }

    companion object {
        @Volatile
        private var sdkReady = false

        /**
         * 高德强制要求的隐私合规声明，必须在任何 MapView 创建之前调用一次。
         * 忘了调 → 地图白屏，这是排查白屏的第一嫌疑。
         *
         * TODO(第 3 步)：改成用户点「同意」之后再调，不在 Application.onCreate 里直接同意。
         */
        fun initSdk(context: Context) {
            if (sdkReady) return
            synchronized(AMapEngine::class.java) {
                if (sdkReady) return
                MapsInitializer.updatePrivacyShow(context, true, true)
                MapsInitializer.updatePrivacyAgree(context, true)
                sdkReady = true
            }
        }
    }
}

internal class AMapController(private val appContext: Context) : MapController {

    private var mapView: AMapMapView? = null
    private var aMap: AMap? = null
    private var density: Float = 1f

    private var pendingScene: MapScene? = null
    private var pendingPoint: Pair<Wgs84, Float?>? = null
    private var pendingBounds: Pair<Wgs84Bounds, Int>? = null
    private var clickListener: ((String) -> Unit)? = null

    /** SDK 的 polylineId -> 我们的 MapPolyline.id */
    private val ourIdBySdkId = mutableMapOf<String, String>()

    /** SDK 的 polylineId -> 折线对象，命中判定要用它的屏幕坐标 */
    private val polylinesBySdkId = mutableMapOf<String, com.amap.api.maps.model.Polyline>()

    private val markers = mutableListOf<com.amap.api.maps.model.Marker>()
    private var cursorMarker: com.amap.api.maps.model.Marker? = null

    private var pendingTrack: List<MapPolyline>? = null
    private var pendingCursor: Wgs84? = null

    private val converter: CoordinateConverter by lazy { CoordinateConverter(appContext) }

    fun attach(view: AMapMapView, density: Float) {
        this.mapView = view
        this.density = density
        val map = view.map
        this.aMap = map

        map.setOnPolylineClickListener { polyline ->
            val sdkId = polyline?.id ?: return@setOnPolylineClickListener
            ourIdBySdkId[sdkId]?.let { clickListener?.invoke(it) }
        }

        // 高德 11.x 的 PolylineOptions 没有 clickable 参数，SDK 自带的折线点击不触发，
        // 所以用「地图点击 + 屏幕像素距离」自己做命中判定（阈值 48px ≈ 16dp）。
        map.setOnMapClickListener { latLng ->
            val sdkId = hitTest(latLng)
            if (sdkId != null) ourIdBySdkId[sdkId]?.let { clickListener?.invoke(it) }
        }

        pendingScene?.let { render(it) }
        pendingTrack?.let { renderTrack(it) }
        pendingCursor?.let { renderCursor(it) }
        pendingPoint?.let { (p, zoom) -> moveCameraTo(p, zoom) }
        pendingBounds?.let { (b, pad) -> fitBounds(b, pad) }
        pendingScene = null
        pendingTrack = null
        pendingCursor = null
        pendingPoint = null
        pendingBounds = null
    }

    fun detach() {
        aMap = null
        mapView = null
        ourIdBySdkId.clear()
        polylinesBySdkId.clear()
        markers.clear()
        cursorMarker = null
    }

    fun resumeMap() {
        mapView?.onResume()
    }

    fun pauseMap() {
        mapView?.onPause()
    }

    /** 全量：轨迹 + 标记。演示页用。 */
    override fun render(scene: MapScene) {
        val map = aMap ?: run {
            pendingScene = scene
            return
        }
        map.clear()
        markers.clear()
        for (marker in scene.markers) {
            markers.add(
                map.addMarker(
                    MarkerOptions()
                        .position(toGcj(marker.position))
                        .title(marker.label),
                ),
            )
        }
        renderTrackInternal(scene.polylines)
    }

    /** 只重画轨迹：逐条 remove，不做 map.clear()，这样回放光标不会被带走。 */
    override fun renderTrack(polylines: List<MapPolyline>) {
        if (aMap == null) {
            pendingTrack = polylines
            return
        }
        renderTrackInternal(polylines)
    }

    private fun renderTrackInternal(polylines: List<MapPolyline>) {
        val map = aMap ?: return
        polylinesBySdkId.values.forEach { runCatching { it.remove() } }
        polylinesBySdkId.clear()
        ourIdBySdkId.clear()

        for (line in polylines) {
            if (line.points.size < 2) continue
            val options = PolylineOptions()
                .addAll(line.points.map { toGcj(it) })
                .color(line.colorArgb)
                .width(line.widthDp * density)
            val polyline = map.addPolyline(options)
            ourIdBySdkId[polyline.id] = line.id
            polylinesBySdkId[polyline.id] = polyline
        }
    }

    /** 只移动光标标记。 */
    override fun renderCursor(point: Wgs84?) {
        val map = aMap ?: run {
            pendingCursor = point
            return
        }
        cursorMarker?.let { runCatching { it.remove() } }
        cursorMarker = null
        if (point != null) {
            cursorMarker = map.addMarker(
                MarkerOptions()
                    .position(toGcj(point))
                    .title(null),
            )
        }
    }

    override fun moveCameraTo(point: Wgs84, zoom: Float?) {
        val map = aMap ?: run {
            pendingPoint = point to zoom
            return
        }
        val target = toGcj(point)
        val update = if (zoom != null) {
            CameraUpdateFactory.newLatLngZoom(target, zoom)
        } else {
            CameraUpdateFactory.newLatLng(target)
        }
        map.animateCamera(update)
    }

    override fun fitBounds(bounds: Wgs84Bounds, paddingDp: Int) {
        val map = aMap ?: run {
            pendingBounds = bounds to paddingDp
            return
        }
        val builder = LatLngBounds.Builder()
        builder.include(toGcj(Wgs84(bounds.south, bounds.west)))
        builder.include(toGcj(Wgs84(bounds.south, bounds.east)))
        builder.include(toGcj(Wgs84(bounds.north, bounds.west)))
        builder.include(toGcj(Wgs84(bounds.north, bounds.east)))

        val paddingPx = (paddingDp * density).toInt()
        runCatching {
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), paddingPx))
        }
    }

    override suspend fun snapshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        val map = aMap
        if (map == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        var resumed = false
        map.getMapScreenShot(object : AMap.OnMapScreenShotListener {
            override fun onMapScreenShot(bitmap: Bitmap?) {
                if (!resumed) {
                    resumed = true
                    if (cont.isActive) cont.resume(bitmap)
                }
            }

            override fun onMapScreenShot(bitmap: Bitmap?, status: Int) {
                if (!resumed) {
                    resumed = true
                    if (cont.isActive) cont.resume(bitmap)
                }
            }
        })
    }

    override fun setOnPolylineClickListener(listener: (String) -> Unit) {
        clickListener = listener
    }

    /** WGS84 -> GCJ-02。这是本类唯一的坐标职责，业务层永远看不到 GCJ-02。 */
    private fun toGcj(point: Wgs84): LatLng {
        return converter
            .from(CoordinateConverter.CoordType.GPS)
            .coord(LatLng(point.lat, point.lng))
            .convert()
    }

    /**
     * 折线命中判定：把点击点和折线顶点都投影成屏幕像素，
     * 交给 core/geo 的 PolylineHitTest 算「点到线段」的最短距离。
     * 阈值 48px（520dpi ≈ 14dp），手指按在线附近就能选中。
     */
    private fun hitTest(clicked: LatLng): String? {
        val map = aMap ?: return null
        val projection = map.projection ?: return null

        val clickPx = projection.toScreenLocation(clicked)
        val clickPoint = ScreenPoint(clickPx.x.toFloat(), clickPx.y.toFloat())

        val ids = polylinesBySdkId.keys.toList()
        if (ids.isEmpty()) return null

        val screenPolylines = ids.map { id ->
            polylinesBySdkId.getValue(id).points.map { p ->
                val px = projection.toScreenLocation(p)
                ScreenPoint(px.x.toFloat(), px.y.toFloat())
            }
        }

        val hitIndex = PolylineHitTest.nearestPolyline(clickPoint, screenPolylines)
        return if (hitIndex >= 0) ids[hitIndex] else null
    }}
