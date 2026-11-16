package com.llyytt.path.feature.record

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.llyytt.path.XjApp
import com.llyytt.path.core.format.Fmt
import com.llyytt.path.data.db.TripEntity
import com.llyytt.path.data.db.TripStatus
import com.llyytt.path.data.repo.StartOutcome
import com.llyytt.path.location.LocationError
import com.llyytt.path.service.RecordingService
import com.llyytt.path.service.RecordingStateHolder
import kotlinx.coroutines.launch

/**
 * 记录页。本步只做最小可用：开始 / 暂停 / 继续 / 结束 + 实时距离时长。
 * 行程列表、详情、地图，都属于第 4 步，这里不碰。
 */
@Composable
fun RecordScreen(
    onOpenTrips: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAi: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as XjApp
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

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onOpenAi) { Text("AI") }
                TextButton(onClick = onOpenSettings) { Text("设置") }
                TextButton(onClick = onOpenTrips) { Text("行程") }
            }
            Text(text = "行迹", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when (state.status) {
                    TripStatus.RECORDING -> "记录中"
                    TripStatus.PAUSED -> "已暂停"
                    TripStatus.FINISHED -> "已结束"
                    null -> "未开始"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = formatDistance(state.distanceMeters),
                style = MaterialTheme.typography.displayMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = formatDuration(state.activeMillis),
                style = MaterialTheme.typography.headlineMedium,
            )

            // 瞬时速度：设置里打开才显示。没有样本时显示「等待定位」，不显示 0.0
            if (state.isActive && showSpeed) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = when {
                        state.isStationary -> "静止"
                        state.speedMps != null -> Fmt.speed(state.speedMps!!)
                        else -> "等待定位"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (state.isStationary) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (state.isActive) {
                Text(
                    text = buildString {
                        append("${state.pointCount} 个点")
                        state.lastAccuracyM?.let { append(" · 精度 ±${it.toInt()}m") }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.status == TripStatus.RECORDING && !state.hasFirstFix) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "正在搜星…（首次定位可能要几十秒）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.lastError?.let { err ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = describeError(err),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            message?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            if (state.isActive) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.status == TripStatus.RECORDING) {
                        OutlinedButton(onClick = { RecordingService.pause(context) }) {
                            Text("暂停")
                        }
                    } else {
                        Button(onClick = { RecordingService.resume(context, state.tripId) }) {
                            Text("继续")
                        }
                    }
                    OutlinedButton(onClick = { RecordingService.stop(context) }) {
                        Text("结束")
                    }
                }
            } else {
                Button(
                    onClick = {
                        val fine = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_FINE_LOCATION,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (fine) {
                            startNow()
                        } else {
                            permissionLauncher.launch(requiredPermissions())
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("开始记录")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "离线：仅底图缓存，弱网可看部分区域，不保证完整",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(24.dp))
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

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

private fun describeError(error: LocationError): String = when (error) {
    LocationError.PERMISSION_DENIED -> "定位权限被拒绝，点「结束」后重新授权"
    LocationError.PROVIDER_DISABLED -> "系统定位开关是关的，请打开定位"
    LocationError.TIMEOUT -> "30 秒没拿到定位，可能是室内或没有信号"
    LocationError.UNKNOWN -> "定位失败"
}

private fun statusText(status: TripStatus): String = when (status) {
    TripStatus.RECORDING -> "记录中"
    TripStatus.PAUSED -> "已暂停"
    TripStatus.FINISHED -> "已结束"
}

private fun formatDistance(meters: Double): String =
    if (meters < 1000) "%.0f 米".format(meters) else "%.2f 公里".format(meters / 1000.0)

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
