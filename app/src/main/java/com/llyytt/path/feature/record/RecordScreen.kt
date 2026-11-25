package com.llyytt.path.feature.record

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.llyytt.path.XjApp
import com.llyytt.path.core.format.Fmt
import com.llyytt.path.data.db.TripEntity
import com.llyytt.path.data.db.TripStatus
import com.llyytt.path.data.repo.StartOutcome
import com.llyytt.path.location.LocationError
import com.llyytt.path.service.RecordingService
import com.llyytt.path.service.RecordingStateHolder
import com.llyytt.path.ui.XingjiTheme
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.llyytt.path.map.MapPolyline

/**
 * 记录页。
 *
 * 视觉：夜间仪表盘 —— 顶部一条"日落天空"渐变，超大斜体距离数字，
 * HUD 式的状态行，底部一条任务提示。功能逻辑与之前完全一致。
 */
@Composable
fun RecordScreen(
    onOpenTrips: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAi: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as XjApp
    val palette = XingjiTheme.palette
    val engine = remember { app.mapEngine() }
    val controller = remember { engine.createController() }
    // 设置开关：默认关。关着的时候这一块完全不渲染
    val showSpeed = app.settings.showInstantSpeed
    val state by RecordingStateHolder.state.collectAsState()
    val scope = rememberCoroutineScope()

    var message by remember { mutableStateOf<String?>(null) }
    var unfinished by remember { mutableStateOf<TripEntity?>(null) }
    var showSaved by remember { mutableStateOf(false) }
    var wasActive by remember { mutableStateOf(false) }
    // 任务提示条：记录中只在状态切换时短暂显示 3 秒，避免常驻遮挡地图
    var showMissionBar by remember { mutableStateOf(true) }
    // 停止时 state.pointCount 会被重置成 0，所以要提前记住「停之前有多少点」
    var lastActivePoints by remember { mutableIntStateOf(0) }

    fun startNow() {
        scope.launch {
            when (val outcome = app.recordingRepository.startNewTrip()) {
                is StartOutcome.Started -> {
                    message = null
                    RecordingService.start(context, outcome.trip.id)
                }
                is StartOutcome.AlreadyRunning -> {
                    unfinished = outcome.trip
                    message = "已经有一条没结束的行程"
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startNow()
        } else {
            message = "没有定位权限，无法记录"
        }
    }

    // 实时轨迹：点变了才重画；地图跟着最新点走
    LaunchedEffect(state.liveTrack.size, state.tripId, state.isActive) {
        val pts = state.liveTrack
        if (pts.size >= 2) {
            controller.renderTrack(
                listOf(
                    com.llyytt.path.map.MapPolyline(
                        id = "live",
                        points = pts,
                        colorArgb = palette.accent.toArgb(),
                        widthDp = 7f,
                    ),
                ),
            )
        }
        pts.lastOrNull()?.let {
            controller.renderCursor(it)
            if (state.isActive) controller.moveCameraTo(it, 17f)
        }
    }

    LaunchedEffect(state.isActive, state.pointCount) {
        if (state.isActive) lastActivePoints = state.pointCount
    }

    // 结束行程时在页面内提示「行程已保存」。
    // 注意：不要做成覆盖全屏的遮罩层——记录页底下是地图（SurfaceView），
    // 遮罩移除后那块区域不会重绘，屏幕上会一直留着残影（实测踩过）。
    LaunchedEffect(state.isActive) {
        if (wasActive && !state.isActive && lastActivePoints > 0) {
            showSaved = true
        }
        wasActive = state.isActive
    }

    LaunchedEffect(showSaved) {
        if (showSaved) {
            kotlinx.coroutines.delay(2200)
            showSaved = false
        }
    }

    // 任务提示条：状态切换时短暂显示 3 秒。空闲时一直显示，记录中让地图呼吸。
    LaunchedEffect(state.isActive, state.status) {
        showMissionBar = true
        if (state.isActive) {
            kotlinx.coroutines.delay(3000)
            showMissionBar = false
        }
    }

    // 首次进入时看看有没有「上次没结束的行程」
    LaunchedEffect(Unit) {
        if (!state.isActive) {
            unfinished = app.recordingRepository.unfinished()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 地图占满整屏：记录时最重要的是「我在哪、走过哪」
            engine.MapView(controller = controller, modifier = Modifier.fillMaxSize())

            // 顶部压一层暗色渐变，保证文字在浅色地图上也读得清
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(430.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.60f), Color.Transparent),
                        ),
                    ),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp),
            ) {
                Spacer(Modifier.height(8.dp))

                // 顶栏
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).background(palette.accent, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "XINGJI",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                    Spacer(Modifier.weight(1f))
                    NavAction("行程", Color.White.copy(alpha = 0.85f), onOpenTrips)
                    NavAction("设置", Color.White.copy(alpha = 0.85f), onOpenSettings)
                    NavAction("AI", Color.White.copy(alpha = 0.85f), onOpenAi)
                }

                Spacer(Modifier.height(12.dp))

                // HUD 状态 + 瞬时速度
                Surface(color = Color(0xCC161A20), shape = RoundedCornerShape(12.dp)) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(if (state.isActive) palette.accent else Color.White.copy(alpha = 0.5f), CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = hudStatus(state.status, state.isActive),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (state.isActive) palette.amber else Color.White.copy(alpha = 0.75f),
                    )
                    if (state.isActive && showSpeed) {
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = when {
                                state.isStationary -> "静止"
                                state.speedMps != null -> Fmt.speed(state.speedMps!!)
                                else -> "等待定位"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.amber,
                        )
                    }
                }
                }

                // 紧凑数据卡：文字退居次要，把画面让给地图
                Surface(
                    color = Color(0xE6161A20),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val (num, unit) = splitDistance(state.distanceMeters)
                        MiniStat("距离", num, unit, palette.accent)
                        MiniStat("时长", formatDuration(state.activeMillis), "", Color.White)
                        MiniStat("点数", "${state.pointCount}", "", Color.White)
                        MiniStat(
                            "精度",
                            state.lastAccuracyM?.let { "±${it.toInt()}" } ?: "—",
                            if (state.lastAccuracyM != null) "m" else "",
                            Color.White,
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (state.isActive && state.status == TripStatus.RECORDING && !state.hasFirstFix) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "正在搜星…（首次定位可能要几十秒）",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }

                state.lastError?.let { err ->
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        color = palette.panel.copy(alpha = 0.94f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = describeError(err),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            val providerIssue = err == LocationError.PROVIDER_DISABLED
                            val permissionIssue = err == LocationError.PERMISSION_DENIED
                            if (providerIssue || permissionIssue) {
                                TextButton(
                                    onClick = {
                                        val intent = if (providerIssue) {
                                            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                        } else {
                                            Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.fromParts("package", context.packageName, null),
                                            )
                                        }
                                        runCatching {
                                            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        }
                                    },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                ) {
                                    Text(
                                        text = if (providerIssue) "打开定位设置" else "打开应用权限",
                                        color = palette.accent,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
                    }
                }

                message?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                AnimatedVisibility(visible = showSaved) {
                    Surface(
                        color = Color(0xE6161A20),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(bottom = 10.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "✓",
                                style = MaterialTheme.typography.labelLarge,
                                color = palette.accent,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "行程已保存",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                            )
                        }
                    }
                }


                Spacer(Modifier.weight(1f))

                AnimatedVisibility(visible = showMissionBar) {
                    MissionBar(
                        text = if (state.isActive) "任务进行中 · 保持定位开启" else "任务 · 记录下一段行程",
                        palette = palette,
                    )
                }

                Spacer(Modifier.height(14.dp))

                if (state.isActive) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (state.status == TripStatus.RECORDING) {
                                    RecordingService.pause(context)
                                } else {
                                    RecordingService.resume(context, state.tripId)
                                }
                            },
                            modifier = Modifier.weight(1f).height(60.dp),
                            shape = RoundedCornerShape(30.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        ) {
                            Text(
                                text = if (state.status == TripStatus.RECORDING) "暂停" else "继续",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Button(
                            onClick = { RecordingService.stop(context) },
                            modifier = Modifier.weight(1f).height(60.dp),
                            shape = RoundedCornerShape(30.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = palette.accent,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Text("结束", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                } else {
                    // 浅色模式下 #E85D0F 在米白底上对比偏弱，给按钮一圈同色光晕把"开始记录"跳出来。
                    // 深色模式不加——深色底上的橙色已经足够亮。
                    val isDark = isSystemInDarkTheme()
                    Button(
                        onClick = {
                            val fine = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.ACCESS_FINE_LOCATION,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (fine) startNow() else permissionLauncher.launch(requiredPermissions())
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(66.dp)
                            .shadow(
                                elevation = if (isDark) 0.dp else 18.dp,
                                shape = RoundedCornerShape(33.dp),
                                ambientColor = palette.accent,
                                spotColor = palette.accent,
                            ),
                        shape = RoundedCornerShape(33.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.accent,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text("开始记录", style = MaterialTheme.typography.titleLarge)
                    }
                }

                Spacer(Modifier.height(12.dp))
                Surface(color = Color(0xCC161A20), shape = RoundedCornerShape(10.dp)) {
                    Text(
                        text = "离线：仅底图缓存，弱网可看部分区域",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }


    // 未结束行程：只给两个出口，绝不「悄悄再开一条」
    val pending = unfinished
    if (pending != null && !state.isActive) {
        AlertDialog(
            onDismissRequest = { unfinished = null },
            title = { Text("有一条没结束的行程") },
            text = {
                Text("开始于 ${formatTime(pending.startedAt)}，状态 ${statusText(pending.status)}。要继续它，还是结束它？")
            },
            confirmButton = {
                TextButton(onClick = {
                    unfinished = null
                    RecordingService.resume(context, pending.id)
                }) { Text("继续") }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch {
                        app.recordingRepository.finish(pending.id)
                        unfinished = null
                        message = "已结束旧行程，可以重新开始"
                    }
                }) { Text("结束它") }
            },
        )
    }
}

@Composable
private fun MiniStat(
    label: String,
    value: String,
    unit: String,
    valueColor: androidx.compose.ui.graphics.Color,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.55f),
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp),
                color = valueColor,
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(2.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun NavAction(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun NavActionOld(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HudStat(label: String, value: String, palette: com.llyytt.path.ui.XingjiPalette) {
    Column(modifier = Modifier.padding(end = 40.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textTertiary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MissionBar(text: String, palette: com.llyytt.path.ui.XingjiPalette) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xE6161A20), RoundedCornerShape(6.dp))
            .padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(16.dp).background(palette.accent))
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = palette.amber,
        )
    }
}

private fun hudStatus(status: TripStatus?, active: Boolean): String = when {
    !active -> "READY"
    status == TripStatus.RECORDING -> "RECORDING"
    status == TripStatus.PAUSED -> "PAUSED"
    else -> "FINISHED"
}

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

private fun describeError(error: LocationError): String = when (error) {
    LocationError.PERMISSION_DENIED -> "定位权限被拒绝。请到「设置 → 应用 → 行迹 → 权限」里改成「精确位置」。"
    LocationError.PROVIDER_DISABLED -> "手机的定位总开关是关的。点下面的按钮打开，打开后会自动开始定位。"
    LocationError.TIMEOUT -> "30 秒没拿到定位。请确认：① 定位已打开 ② 在室外或窗边（室内通常定不上）。"
    LocationError.UNKNOWN -> "定位失败"
}

private fun statusText(status: TripStatus): String = when (status) {
    TripStatus.RECORDING -> "记录中"
    TripStatus.PAUSED -> "已暂停"
    TripStatus.FINISHED -> "已结束"
}

/** 拆成「数字」+「单位」两段，方便分别排版。 */
private fun splitDistance(meters: Double): Pair<String, String> =
    if (meters < 1000) "%.0f".format(meters) to "米"
    else "%.2f".format(meters / 1000.0) to "公里"

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun formatTime(millis: Long): String {
    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
    return fmt.format(java.util.Date(millis))
}
