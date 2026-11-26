package com.llyytt.path.feature.trips

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.llyytt.path.ui.XingjiTheme

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
    val palette = XingjiTheme.palette
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
                    context = context,
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

        // 浅色模式：高德默认浅色底图会把橙色轨迹淹没。
        // 整图压一层深色蒙版，让橙色轨迹跳出来，与深色模式的"夜间仪表盘"气质对齐。
        if (!isSystemInDarkTheme()) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0B0D10).copy(alpha = 0.35f)),
            )
        }

        // 顶部压一层暗色渐变，保证白色文字在浅色地图上也读得清
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(220.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent),
                    ),
                ),
        )

        // 顶部：悬浮一层，不用不透明顶栏，让地图透出来
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onBack,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
            ) {
                Text("←", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = d?.let { Fmt.dateTime(it.trip.startedAt) } ?: "行程详情",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                    .clickable {
                        colorMode = if (colorMode == TrackColorMode.SPEED) {
                            TrackColorMode.MODE
                        } else {
                            TrackColorMode.SPEED
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = if (colorMode == TrackColorMode.SPEED) "按速度" else "按方式",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.accent,
                )
            }
        }

        // 底部面板：圆角 + 顶部一条橙色线
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = palette.panel,
            shape = RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp),
        ) {
            androidx.compose.foundation.layout.Box {
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 22.dp),
                ) {
                    if (d == null) {
                        Text("加载中…", style = MaterialTheme.typography.bodyMedium)
                        return@Column
                    }

                    val stats = d.stats
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Stat("距离", distanceValue(stats.distanceMeters), distanceUnit(stats.distanceMeters))
                        Stat("时长", Fmt.duration(stats.activeMillis), "")
                        Stat("均速", speedValue(stats.avgSpeedMps), "km/h")
                        Stat("最高", speedValue(stats.maxSpeedMps), "km/h")
                    }

                    Spacer(Modifier.height(18.dp))

                    // meta：大写小标签，可点进入分段编辑
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenSegments() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = buildString {
                                append("${stats.pointCount} POINTS")
                                if (stats.gapCount > 0) append("   ${stats.gapCount} GAP")
                                if (d.stays.isNotEmpty()) append("   ${d.stays.size} STAY")
                                if (d.segments.isEmpty()) append("   NO SEGMENT") else
                                    append("   ${d.segments.size} SEGMENTS")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textTertiary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "编辑 ›",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.accent,
                        )
                    }

                    if (d.points.size > 1) {
                        Spacer(Modifier.height(18.dp))
                        val idx = cursor.roundToInt()
                        val p = d.points.getOrNull(idx)
                        val speed = speeds.getOrNull(idx)

                        // 回放条：橙色已播放 + 白色圆点
                        Slider(
                            value = cursor,
                            onValueChange = {
                                playing = false
                                cursor = it
                            },
                            valueRange = 0f..(d.points.size - 1).toFloat(),
                            colors = androidx.compose.material3.SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.onSurface,
                                activeTrackColor = palette.accent,
                                inactiveTrackColor = palette.hairline,
                            ),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = p?.let { Fmt.clock(it.recordedAt) } ?: "--:--",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (showSpeed) {
                                Text(
                                    text = when {
                                        speed?.stationary == true -> "静止"
                                        speed?.smoothedMps != null -> Fmt.speed(speed.smoothedMps)
                                        else -> "--"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = palette.amber,
                                )
                            }
                            Text(
                                text = p?.let {
                                    "已走 ${Fmt.distance(cumulative.getOrElse(idx) { 0.0 })}"
                                } ?: "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = {
                                    if (!playing && cursor >= d.points.size - 1) cursor = 0f
                                    playing = !playing
                                    if (playing) follow = true
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 0.dp,
                                ),
                            ) {
                                Text(
                                    text = if (playing) "暂停回放" else "回放",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = palette.accent,
                                )
                            }
                            Spacer(Modifier.width(24.dp))
                            TextButton(
                                onClick = { follow = !follow },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 0.dp,
                                ),
                            ) {
                                Text(
                                    text = if (follow) "跟随：开" else "跟随：关",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = palette.textTertiary,
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "这条行程没有轨迹点（可能是开始后一直没定位成功）",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textTertiary,
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    // 事后诊断：最后一个点离结束时间太久，说明中途定位就断了
                    run {
                        val lastPointAt = d.points.lastOrNull()?.recordedAt ?: 0L
                        val endedAt = d.trip.endedAt ?: 0L
                        if (lastPointAt > 0L && endedAt > lastPointAt) {
                            val silentMin = (endedAt - lastPointAt) / 60_000L
                            if (silentMin >= 5L) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = "⚠ 最后 ${silentMin} 分钟没有记录到位置" +
                                        "（${Fmt.clock(lastPointAt)} 之后定位就停了），" +
                                        "多半是后台服务被系统清理。请把本应用加入电池白名单。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ActionText("导出", palette.textTertiary) { showFormatDialog = true }
                        ActionText("分享图", palette.textTertiary) { shareTrip() }
                        ActionText("删除", MaterialTheme.colorScheme.error) { showDeleteDialog = true }
                        exportMessage?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textTertiary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // 顶部那条橙色线
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(palette.accent),
                )
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
private fun Stat(label: String, value: String, unit: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = XingjiTheme.palette.textTertiary,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 40.sp),
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = XingjiTheme.palette.textTertiary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ActionText(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

private fun distanceValue(meters: Double): String =
    if (meters < 1000) "%.0f".format(meters) else "%.2f".format(meters / 1000.0)

private fun distanceUnit(meters: Double): String =
    if (meters < 1000) "米" else "公里"

private fun speedValue(v: Double): String = "%.1f".format(v)
