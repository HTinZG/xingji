package com.llyytt.path.feature.record

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    // 设置开关：默认关。关着的时候这一块完全不渲染
    val showSpeed = app.settings.showInstantSpeed
    val state by RecordingStateHolder.state.collectAsState()
    val scope = rememberCoroutineScope()

    var message by remember { mutableStateOf<String?>(null) }
    var unfinished by remember { mutableStateOf<TripEntity?>(null) }

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

    // 首次进入时看看有没有「上次没结束的行程」
    LaunchedEffect(Unit) {
        if (!state.isActive) {
            unfinished = app.recordingRepository.unfinished()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 顶部「日落天空」：GTA 式的暖色渐变，只在顶部一带
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .background(Brush.verticalGradient(listOf(palette.skyTop, palette.skyBottom))),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp),
            ) {
                Spacer(Modifier.height(10.dp))

                // 顶栏：品牌点 + 入口
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).background(palette.accent, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "XINGJI",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.weight(1f))
                    NavAction("行程", onOpenTrips)
                    NavAction("设置", onOpenSettings)
                    NavAction("AI", onOpenAi)
                }

                Spacer(Modifier.height(34.dp))

                // HUD 状态行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(
                                if (state.isActive) palette.accent else palette.textTertiary,
                                CircleShape,
                            ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = hudStatus(state.status, state.isActive),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (state.isActive) palette.amber else palette.textTertiary,
                    )
                }

                Spacer(Modifier.height(18.dp))

                // 主数字：超大 + 斜体 + 单位基线对齐
                val (num, unit) = splitDistance(state.distanceMeters)
                Row {
                    Text(
                        text = num,
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontStyle = FontStyle.Italic,
                            fontSize = 132.sp,
                            lineHeight = 120.sp,
                        ),
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.headlineMedium,
                        color = palette.textTertiary,
                        modifier = Modifier.alignByBaseline(),
                    )
                }

                Spacer(Modifier.height(2.dp))

                Text(
                    text = formatDuration(state.activeMillis),
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontSize = 72.sp,
                        lineHeight = 76.sp,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                )

                // 瞬时速度：设置里打开才显示。没有样本时显示「等待定位」，不显示 0.0
                if (state.isActive && showSpeed) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = when {
                            state.isStationary -> "静止"
                            state.speedMps != null -> Fmt.speed(state.speedMps!!)
                            else -> "等待定位"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (state.isStationary) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            palette.accent
                        },
                    )
                }

                Spacer(Modifier.height(28.dp))

                // 数据行：点数 / 精度（速度开启时多一列）
                Row(modifier = Modifier.fillMaxWidth()) {
                    if (state.isActive && showSpeed) {
                        HudStat("速度", state.speedMps?.let { Fmt.speed(it) } ?: "—", palette)
                    }
                    HudStat("点数", "${state.pointCount}", palette)
                    HudStat(
                        "精度",
                        state.lastAccuracyM?.let { "±${it.toInt()}m" } ?: "—",
                        palette,
                    )
                }

                if (state.isActive && state.status == TripStatus.RECORDING && !state.hasFirstFix) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "正在搜星…（首次定位可能要几十秒）",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary,
                    )
                }

                state.lastError?.let { err ->
                    Spacer(Modifier.height(14.dp))
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

                message?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.weight(1f))

                // 任务提示条：GTA 的任务行
                MissionBar(
                    text = if (state.isActive) "任务进行中 · 保持定位开启" else "任务 · 记录下一段行程",
                    palette = palette,
                )

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
                        ) {
                            Text("结束", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            val fine = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.ACCESS_FINE_LOCATION,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (fine) startNow() else permissionLauncher.launch(requiredPermissions())
                        },
                        modifier = Modifier.fillMaxWidth().height(68.dp),
                        shape = RoundedCornerShape(34.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.accent,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text("开始记录", style = MaterialTheme.typography.titleLarge)
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "离线：仅底图缓存，弱网可看部分区域",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textTertiary,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(18.dp))
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
private fun NavAction(label: String, onClick: () -> Unit) {
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
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
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
