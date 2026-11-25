package com.llyytt.path.feature.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import com.llyytt.path.XjApp
import com.llyytt.path.core.format.Fmt
import com.llyytt.path.core.geo.Wgs84
import com.llyytt.path.core.rules.Tuning
import com.llyytt.path.data.db.TrackPointEntity
import com.llyytt.path.data.db.TransportMode
import com.llyytt.path.data.db.TripSegmentEntity
import com.llyytt.path.data.repo.TripDetail
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.sp
import com.llyytt.path.ui.XingjiTheme

/**
 * 分段编辑器：自动分段 + 三个手动操作。
 *
 * 三个操作各走一个事务，失败就整体回滚（见 SegmentRepository）。
 * 这里只负责交互与提示，不直接碰数据库。
 */
@Composable
fun SegmentEditorScreen(tripId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as XjApp
    val scope = rememberCoroutineScope()

    var detail by remember { mutableStateOf<TripDetail?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var splitTarget by remember { mutableStateOf<TripSegmentEntity?>(null) }
    var mergeTarget by remember { mutableStateOf<Pair<TripSegmentEntity, TripSegmentEntity>?>(null) }

    suspend fun reload() {
        detail = app.tripRepository.detail(tripId)
    }

    LaunchedEffect(tripId) { reload() }

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                action()
                reload()
            } catch (t: Throwable) {
                error = t.message ?: "操作失败"
            } finally {
                busy = false
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("返回") }
                Text(
                    text = "分段",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = !busy,
                    onClick = { run { app.segmentRepository.autoSegment(tripId) } },
                ) {
                    Text("自动分段")
                }
            }

            val data = detail
            when {
                data == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                data.segments.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val palette = XingjiTheme.palette
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // 空状态视觉锚点：三条长短不一的横线，暗示"将被切开的轨迹"
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.width(72.dp).height(3.dp).background(palette.hairline))
                            Box(Modifier.width(48.dp).height(3.dp).background(palette.accent))
                            Box(Modifier.width(28.dp).height(3.dp).background(palette.hairline))
                        }
                        Spacer(Modifier.height(28.dp))
                        Text(
                            "还没有分段",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "按速度和停顿，自动把这条行程切成\n走路 / 骑车 / 坐车 / 停留",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        // 主 CTA：直接给「自动分段」按钮，不用让用户去右上角找
                        androidx.compose.material3.Button(
                            onClick = { run { app.segmentRepository.autoSegment(tripId) } },
                            enabled = !busy,
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = palette.accent,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                            modifier = Modifier.height(48.dp),
                        ) {
                            Text(
                                "自动分段",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(data.segments) { index, segment ->
                        val next = data.segments.getOrNull(index + 1)
                        SegmentCard(
                            index = index,
                            segment = segment,
                            points = data.points,
                            canSplit = segment.endPointSeq > segment.startPointSeq,
                            canMerge = next != null && next.startPointSeq == segment.endPointSeq + 1,
                            enabled = !busy,
                            onChangeMode = {
                                run { app.segmentRepository.setMode(segment.id, nextMode(segment.mode)) }
                            },
                            onSplit = { splitTarget = segment },
                            onMerge = { next?.let { mergeTarget = segment to it } },
                        )
                    }
                }
            }
        }
    }

    // ---------- 拆分对话框 ----------
    splitTarget?.let { segment ->
        val minSeq = segment.startPointSeq + 1
        val maxSeq = segment.endPointSeq
        var at by remember(segment.id) {
            mutableFloatStateOf(((minSeq + maxSeq) / 2).toFloat())
        }
        AlertDialog(
            onDismissRequest = { splitTarget = null },
            title = { Text("在这一段的哪个位置切开？") },
            text = {
                Column {
                    Text(
                        text = segmentRangeText(segment),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    val split = at.toInt()
                    val points = detail?.points ?: emptyList()
                    Text(
                        "前段 ${Fmt.duration(segmentDuration(points, segment.startPointSeq, split - 1))}" +
                            "  /  后段 ${Fmt.duration(segmentDuration(points, split, segment.endPointSeq))}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = at,
                        onValueChange = { at = it },
                        valueRange = minSeq.toFloat()..maxSeq.toFloat(),
                        steps = (maxSeq - minSeq - 1).coerceAtLeast(0),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cut = at.toInt()
                    splitTarget = null
                    run { app.segmentRepository.split(segment.id, cut) }
                }) { Text("拆分") }
            },
            dismissButton = {
                TextButton(onClick = { splitTarget = null }) { Text("取消") }
            },
        )
    }

    // ---------- 合并对话框 ----------
    mergeTarget?.let { (first, second) ->
        val points = detail?.points ?: emptyList()
        val mergedMillis = segmentDuration(points, first.startPointSeq, second.endPointSeq)
        val tooShort = mergedMillis < Tuning.Segment.MIN_SEGMENT_MS
        AlertDialog(
            onDismissRequest = { mergeTarget = null },
            title = { Text("合并这两段？") },
            text = {
                Column {
                    Text("${segmentRangeText(first)}\n合并 ${segmentRangeText(second)}")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "合并后时长 ${Fmt.duration(mergedMillis)}" +
                            if (tooShort) {
                                "，短于建议的 ${Tuning.Segment.MIN_SEGMENT_MS / 1000} 秒。"
                            } else {
                                "。"
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (tooShort) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    if (tooShort) {
                        Text(
                            "手动操作优先于规则，你确认就执行。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    mergeTarget = null
                    run { app.segmentRepository.merge(first.id, second.id) }
                }) { Text("合并") }
            },
            dismissButton = {
                TextButton(onClick = { mergeTarget = null }) { Text("取消") }
            },
        )
    }

    // ---------- 错误 ----------
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("操作没有生效") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { error = null }) { Text("知道了") }
            },
        )
    }
}

@Composable
private fun SegmentCard(
    index: Int,
    segment: TripSegmentEntity,
    points: List<TrackPointEntity>,
    canSplit: Boolean,
    canMerge: Boolean,
    enabled: Boolean,
    onChangeMode: () -> Unit,
    onSplit: () -> Unit,
    onMerge: () -> Unit,
) {
    val palette = XingjiTheme.palette
    val duration = segmentDuration(points, segment.startPointSeq, segment.endPointSeq)
    val distance = segmentDistance(points, segment.startPointSeq, segment.endPointSeq)

    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "%02d".format(index + 1),
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp),
                color = palette.textTertiary,
            )
            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = modeLabel(segment.mode),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "seq ${segment.startPointSeq}..${segment.endPointSeq} · ${Fmt.distance(distance)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textTertiary,
                )
            }

            Text(
                text = Fmt.duration(duration),
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 38.sp),
                color = palette.amber,
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            SegmentAction("改方式", palette.accent, enabled) { onChangeMode() }
            SegmentAction(
                "拆分",
                if (enabled && canSplit) palette.textTertiary else palette.hairline,
                enabled && canSplit,
            ) { onSplit() }
            SegmentAction(
                "合并下段",
                if (enabled && canMerge) palette.textTertiary else palette.hairline,
                enabled && canMerge,
            ) { onMerge() }
        }

        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.hairline),
        )
    }
}

@Composable
private fun SegmentAction(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

private fun modeLabel(mode: TransportMode): String = when (mode) {
    TransportMode.WALK -> "走路"
    TransportMode.BIKE -> "骑车"
    TransportMode.CAR -> "坐车"
    TransportMode.UNKNOWN -> "未知"
}

private fun nextMode(mode: TransportMode): TransportMode = when (mode) {
    TransportMode.WALK -> TransportMode.BIKE
    TransportMode.BIKE -> TransportMode.CAR
    else -> TransportMode.WALK
}

private fun segmentRangeText(s: TripSegmentEntity): String = "seq ${s.startPointSeq}..${s.endPointSeq}"

private fun segmentDuration(points: List<TrackPointEntity>, startSeq: Int, endSeq: Int): Long {
    val start = points.getOrNull(startSeq)?.recordedAt ?: return 0
    val end = points.getOrNull(endSeq)?.recordedAt ?: return 0
    return (end - start).coerceAtLeast(0)
}

private fun segmentDistance(points: List<TrackPointEntity>, startSeq: Int, endSeq: Int): Double {
    if (endSeq <= startSeq) return 0.0
    var sum = 0.0
    for (seq in startSeq until endSeq) {
        val a = points.getOrNull(seq) ?: continue
        val b = points.getOrNull(seq + 1) ?: continue
        sum += Wgs84(a.lat, a.lng).distanceTo(Wgs84(b.lat, b.lng))
    }
    return sum
}
