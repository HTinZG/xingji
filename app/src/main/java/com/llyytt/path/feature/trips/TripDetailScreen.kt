package com.llyytt.path.feature.trips

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.llyytt.path.core.export.ShareComposer
import com.llyytt.path.core.export.TrackExporter
import com.llyytt.path.core.export.toExportModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.llyytt.path.XjApp
import com.llyytt.path.core.format.Fmt
import com.llyytt.path.core.geo.Wgs84
import com.llyytt.path.core.geo.Wgs84Bounds
import com.llyytt.path.core.rules.SpeedSeries
import com.llyytt.path.data.repo.TripDetail
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 行程详情：地图 + 统计 + 简版回放。
 *
 * 回放刻意做得很轻：一个滑块 + 一个小圆点 + 跟随开关，不做 3D、不做动画曲线。
 * 渲染分两条路径：轨迹只在切换着色方式时重画，光标每次只移动一个标记，
 * 避免拖动滑块时把整条轨迹反复重建。
 */
@Composable
fun TripDetailScreen(tripId: Long, onBack: () -> Unit, onOpenSegments: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as XjApp
    val engine = remember { app.mapEngine() }
    val controller = remember { engine.createController() }

    var detail by remember { mutableStateOf<TripDetail?>(null) }
    var colorMode by remember { mutableStateOf(TrackColorMode.SPEED) }
    var cursor by remember { mutableFloatStateOf(0f) }
    var playing by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(false) }

    LaunchedEffect(tripId) {
        detail = app.tripRepository.detail(tripId)
    }

    val d = detail

    // ---------------- 导出 / 分享 ----------------
    val scope = rememberCoroutineScope()
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var showFormatDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    fun writeExport(target: Uri, format: TrackExporter.Format) {
        val data = d ?: return
        scope.launch {
            try {
                val content = TrackExporter.build(
                    data.toExportModel(title = Fmt.dateTime(data.trip.startedAt)),
                    format,
                )
                // 导出前的最后一道闸：宁可导出失败，也不能把敏感串写进文件
                val findings = TrackExporter.scanForSensitiveContent(content)
                if (findings.isNotEmpty()) {
                    exportMessage = "已中止：文件里发现可疑内容（${findings.joinToString()}）"
                    return@launch
                }
                val bytes = content.toByteArray(Charsets.UTF_8)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(target)?.use { it.write(bytes) }
                }
                exportMessage = "已导出 ${format.name}：${bytes.size / 1024} KB，${data.points.size} 个点"
            } catch (t: Throwable) {
                exportMessage = "导出失败：${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    val gpxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(TrackExporter.Format.GPX.mimeType),
    ) { uri -> uri?.let { writeExport(it, TrackExporter.Format.GPX) } }

    val kmlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(TrackExporter.Format.KML.mimeType),
    ) { uri -> uri?.let { writeExport(it, TrackExporter.Format.KML) } }

    fun exportName(format: TrackExporter.Format): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date(d?.trip?.startedAt ?: System.currentTimeMillis()))
        return "xingji-$stamp.${format.extension}"
    }

    fun shareTrip() {
        val data = d ?: return
        scope.launch {
            val map = controller.snapshot()
            if (map == null) {
                exportMessage = "地图还没准备好，稍后再试"
                return@launch
            }
            try {
                val image = ShareComposer.compose(
                    map = map,
                    title = Fmt.dateTime(data.trip.startedAt),
                    subtitle = "行迹 · ${data.stats.pointCount} 个点 · ${data.segments.size} 段",
                    stats = listOf(
                        "距离" to Fmt.distance(data.stats.distanceMeters),
                        "时长" to Fmt.duration(data.stats.activeMillis),
                        "均速" to Fmt.speed(data.stats.avgSpeedMps),
                        "最高" to Fmt.speed(data.stats.maxSpeedMps),
                    ),
                )
                val uri = ShareComposer.writeToCache(context, image, "xingji-share.png")
                ShareComposer.share(context, uri, "行迹 · ${Fmt.dateTime(data.trip.startedAt)}")
            } catch (t: Throwable) {
                exportMessage = "分享失败：${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    // 回放速度：跟随设置开关，关着就不算
    val showSpeed = app.settings.showInstantSpeed
    val speeds = remember(d, showSpeed) {
        if (showSpeed) SpeedSeries.smoothed(d?.points ?: emptyList()) else emptyList()
    }

    // 轨迹：只在数据或着色方式变化时重画
    LaunchedEffect(d, colorMode) {
        val data = d ?: return@LaunchedEffect
        controller.renderTrack(TrackPainter.build(data.points, data.segments, colorMode))
        val bounds = Wgs84Bounds.of(data.points.map { Wgs84(it.lat, it.lng) })
        if (bounds != null) controller.fitBounds(bounds, 96)
        cursor = (data.points.size - 1).coerceAtLeast(0).toFloat()
    }

    // 光标：每次只移动一个标记
    LaunchedEffect(cursor, d) {
        val data = d ?: return@LaunchedEffect
        val p = data.points.getOrNull(cursor.roundToInt())
        if (p == null) {
            controller.renderCursor(null)
            return@LaunchedEffect
        }
        val wgs = Wgs84(p.lat, p.lng)
        controller.renderCursor(wgs)
        if (follow) controller.moveCameraTo(wgs, 17f)
    }

    // 播放：按点数自适应步长，让任何长度的行程都在 30 秒左右播完
    LaunchedEffect(playing, d) {
        val data = d ?: return@LaunchedEffect
        val total = data.points.size
        if (total <= 1) {
            playing = false
            return@LaunchedEffect
        }
        val step = maxOf(1, total / 250)
        while (playing) {
            delay(120)
            val next = cursor.roundToInt() + step
            if (next >= total) {
                cursor = (total - 1).toFloat()
                playing = false
            } else {
                cursor = next.toFloat()
            }
        }
    }

    val cumulative = remember(d) {
        val pts = d?.points ?: emptyList()
        val arr = DoubleArray(pts.size)
        var sum = 0.0
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            if (!b.startsNewSegment) {
                sum += Wgs84(a.lat, a.lng).distanceTo(Wgs84(b.lat, b.lng))
            }
            arr[i] = sum
        }
        arr
    }

    Box(modifier = Modifier.fillMaxSize()) {
        engine.MapView(controller = controller, modifier = Modifier.fillMaxSize())

        // 顶部栏
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        ) {
            Row(
                modifier = Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("返回") }
                Text(
                    text = d?.let { Fmt.dateTime(it.trip.startedAt) } ?: "行程详情",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    colorMode = if (colorMode == TrackColorMode.SPEED) TrackColorMode.MODE else TrackColorMode.SPEED
                }) {
                    Text(if (colorMode == TrackColorMode.SPEED) "按速度" else "按方式")
                }
            }
        }

        // 底部面板
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(12.dp)
                .fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (d == null) {
                    Text("加载中…", style = MaterialTheme.typography.bodyMedium)
                    return@Card
                }

                val stats = d.stats
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Stat("距离", Fmt.distance(stats.distanceMeters))
                    Stat("活动时长", Fmt.duration(stats.activeMillis))
                    Stat("均速", Fmt.speed(stats.avgSpeedMps))
                    Stat("最高", Fmt.speed(stats.maxSpeedMps))
                }

                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${stats.pointCount} 个点", style = MaterialTheme.typography.labelMedium)
                    if (stats.gapCount > 0) {
                        Text("${stats.gapCount} 处中断", style = MaterialTheme.typography.labelMedium)
                    }
                    if (d.stays.isNotEmpty()) {
                        Text("${d.stays.size} 个停留", style = MaterialTheme.typography.labelMedium)
                    }
                    if (d.segments.isEmpty()) {
                        Text(
                            "尚未分段 · 编辑",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onOpenSegments() },
                        )
                    } else {
                        Text(
                            "${d.segments.size} 段 · 编辑",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onOpenSegments() },
                        )
                    }
                }

                Text(
                    text = "底图缓存：弱网可看部分区域，不保证完整",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (d.points.size > 1) {
                    Spacer(Modifier.height(4.dp))
                    val idx = cursor.roundToInt()
                    val p = d.points.getOrNull(idx)
                    val speed = speeds.getOrNull(idx)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = p?.let { Fmt.clock(it.recordedAt) } ?: "--:--",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (showSpeed) {
                            Text(
                                text = when {
                                    speed?.stationary == true -> "静止"
                                    speed?.smoothedMps != null -> Fmt.speed(speed.smoothedMps)
                                    else -> "--"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            text = p?.let { "已走 ${Fmt.distance(cumulative.getOrElse(idx) { 0.0 })}" } ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    Slider(
                        value = cursor,
                        onValueChange = {
                            playing = false
                            cursor = it
                        },
                        valueRange = 0f..(d.points.size - 1).toFloat(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = {
                            if (!playing && cursor >= d.points.size - 1) cursor = 0f
                            playing = !playing
                            if (playing) follow = true
                        }) {
                            Text(if (playing) "暂停" else "回放")
                        }
                        TextButton(onClick = { follow = !follow }) {
                            Text(if (follow) "跟随：开" else "跟随：关")
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "这条行程没有轨迹点（可能是开始后一直没定位成功）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { showFormatDialog = true }) { Text("导出") }
                    TextButton(onClick = { shareTrip() }) { Text("分享图") }
                    TextButton(onClick = { showDeleteDialog = true }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                    exportMessage?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("删除这条行程？") },
                text = {
                    Text(
                        "轨迹点、分段、停留记录会一起删掉，不能恢复。\n" +
                            "如果想留着，先点「导出」存一份 GPX 或 KML。",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteDialog = false
                        scope.launch {
                            try {
                                app.tripRepository.delete(tripId)
                                onBack()
                            } catch (t: Throwable) {
                                exportMessage = "删除失败：${t.message ?: t.javaClass.simpleName}"
                            }
                        }
                    }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
                },
            )
        }

        if (showFormatDialog) {
            AlertDialog(
                onDismissRequest = { showFormatDialog = false },
                title = { Text("导出成什么格式") },
                text = {
                    Text(
                        "GPX 1.1 和 KML 2.2 都是 WGS84 坐标，能直接拖进 Google Earth。" +
                            "文件里只有轨迹点、时间、分段方式和停留点。",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showFormatDialog = false
                        gpxLauncher.launch(exportName(TrackExporter.Format.GPX))
                    }) { Text("GPX") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showFormatDialog = false
                        kmlLauncher.launch(exportName(TrackExporter.Format.KML))
                    }) { Text("KML") }
                },
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
