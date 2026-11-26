package com.llyytt.path.feature.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llyytt.path.XjApp
import com.llyytt.path.core.format.Fmt
import com.llyytt.path.data.db.TripStatus
import com.llyytt.path.data.repo.TripListItem
import com.llyytt.path.ui.XingjiTheme

/**
 * 行程列表。
 *
 * 视觉：行式列表，不用卡片盒子。眼睛只需要追两个东西——距离和时长。
 * 距离用超大窄体字，时长用琥珀色，其余信息压到最小。
 */
@Composable
fun TripListScreen(onOpen: (Long) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as XjApp
    val palette = XingjiTheme.palette

    var items by remember { mutableStateOf<List<TripListItem>?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    // 「导出全部」：写一个 zip，里面每条行程一个 GPX
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var exportMsg by remember { mutableStateOf<String?>(null) }
    val exportAllLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            exporting = true
            exportMsg = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    com.llyytt.path.core.export.BulkExporter.exportAll(context, app.tripRepository, uri)
                }
            }
            exporting = false
            exportMsg = result.fold(
                onSuccess = { r ->
                    if (r.error != null) "导出失败：${r.error.message}" else "已导出 ${r.count} 条行程"
                },
                onFailure = { "导出失败：${it.message ?: it.javaClass.simpleName}" },
            )
        }
    }

    LaunchedEffect(reload) {
        items = app.tripRepository.list()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    Text(
                        text = "←",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(9.dp).background(palette.accent, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "XINGJI",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        exportAllLauncher.launch("xingji-backup.zip")
                    },
                    enabled = !exporting,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                ) {
                    Text(
                        text = if (exporting) "导出中…" else "导出全部",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.accent,
                    )
                }
                TextButton(onClick = { reload++ }, contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                    Text(
                        text = "刷新",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textTertiary,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            exportMsg?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textTertiary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                )
            }

            Text(
                text = "行程",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            Spacer(Modifier.height(6.dp))

            val list = items
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.accent)
                }

                list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "还没有行程",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "回记录页开始第一次",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textTertiary,
                        )
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 24.dp, end = 24.dp, top = 8.dp, bottom = 40.dp,
                    ),
                ) {
                    items(list, key = { it.trip.id }) { item ->
                        TripRow(item = item, onClick = { onOpen(item.trip.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TripRow(item: TripListItem, onClick: () -> Unit) {
    val trip = item.trip
    val stats = item.stats
    val palette = XingjiTheme.palette
    val (num, unit) = splitDistance(stats.distanceMeters)

    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Spacer(Modifier.height(18.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = Fmt.dateTime(trip.startedAt),
                style = MaterialTheme.typography.labelMedium,
                color = palette.textTertiary,
                modifier = Modifier.weight(1f),
            )
            if (trip.status != TripStatus.FINISHED) {
                Box(Modifier.size(6.dp).background(palette.accent, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = statusLabel(trip.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.accent,
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = Fmt.duration(stats.activeMillis),
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 44.sp),
                color = palette.amber,
            )
        }

        Row {
            Text(
                text = num,
                style = MaterialTheme.typography.displayMedium.copy(fontSize = 76.sp, lineHeight = 72.sp),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = unit,
                style = MaterialTheme.typography.titleMedium,
                color = palette.textTertiary,
                modifier = Modifier.alignByBaseline(),
            )
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = buildString {
                append("${stats.pointCount} 点")
                if (item.segmentCount > 0) append(" · ${item.segmentCount} 段")
                if (item.stayCount > 0) append(" · ${item.stayCount} 个停留")
                if (stats.gapCount > 0) append(" · ${stats.gapCount} 处断点")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.hairline),
        )
    }
}

private fun splitDistance(meters: Double): Pair<String, String> =
    if (meters < 1000) "%.0f".format(meters) to "米"
    else "%.2f".format(meters / 1000.0) to "公里"

private fun statusLabel(status: TripStatus): String = when (status) {
    TripStatus.RECORDING -> "记录中"
    TripStatus.PAUSED -> "已暂停"
    TripStatus.FINISHED -> "已结束"
}
