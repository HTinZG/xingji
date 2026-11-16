package com.llyytt.path.map

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.llyytt.path.core.geo.Wgs84
import com.llyytt.path.core.geo.Wgs84Bounds

/**
 * 离线能力分级。
 *
 * MVP 走高德 11.x：offlineCapability = CACHE_ONLY，
 * 意思是只能吃 SDK 自己对底图的缓存，弱网/短暂离线时可能看到部分已缓存区域，
 * 不保证完整，也不提供城市列表/下载/删除任何 UI。
 */
enum class OfflineCapability {
    /** 完全没有离线能力，断网即白图。 */
    NONE,

    /** 只有 SDK 原生底图缓存，可用性有限且不可控。 */
    CACHE_ONLY,

    /** 支持按城市下载完整离线包（本 App MVP 不提供）。 */
    FULL,
}

data class MapPolyline(
    val id: String,
    val points: List<Wgs84>,
    val colorArgb: Int,
    val widthDp: Float = 6f,
)

data class MapMarker(
    val id: String,
    val position: Wgs84,
    val label: String? = null,
)

/** 一次渲染的全部内容。声明式：给什么画什么，不做增量更新。 */
data class MapScene(
    val polylines: List<MapPolyline> = emptyList(),
    val markers: List<MapMarker> = emptyList(),
)

interface MapController {
    /** 渲染一个场景（轨迹 + 标记，全量重画）。地图还没就绪时自动排队。 */
    fun render(scene: MapScene)

    /** 只重画轨迹，不动光标。回放时用这个，避免每帧重建整张图。 */
    fun renderTrack(polylines: List<MapPolyline>)

    /** 只移动回放光标。传 null 表示隐藏。 */
    fun renderCursor(point: Wgs84?)

    fun moveCameraTo(point: Wgs84, zoom: Float? = null)

    fun fitBounds(bounds: Wgs84Bounds, paddingDp: Int = 64)

    /** 地图截图，第 8 步用来生成分享图。地图未就绪时返回 null。 */
    suspend fun snapshot(): Bitmap?

    /** 点中某条折线时回调，参数是 MapPolyline.id。 */
    fun setOnPolylineClickListener(listener: (String) -> Unit)
}

/**
 * 地图引擎。业务层只依赖这个接口，永远不 import com.amap / com.baidu。
 *
 * 接口里只有「跨引擎共性」：画线、画点、相机、截图、生命周期、点击回调。
 * 引擎特有的东西（离线管理 UI、定位初始化、隐私合规、Key 配置）不在这里。
 */
interface MapEngine {
    val id: String

    val offlineCapability: OfflineCapability

    fun createController(): MapController

    @Composable
    fun MapView(controller: MapController, modifier: Modifier)
}
