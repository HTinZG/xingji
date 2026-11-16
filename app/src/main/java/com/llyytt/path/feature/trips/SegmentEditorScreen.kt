package com.llyytt.path.feature.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("还没有分段", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "点右上角「自动分段」按速度和停顿自动切分。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "[${index + 1}] ${modeLabel(segment.mode)}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = Fmt.duration(segmentDuration(points, segment.startPointSeq, segment.endPointSeq)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = "${segmentRangeText(segment)}  ·  " +
                    Fmt.distance(segmentDistance(points, segment.startPointSeq, segment.endPointSeq)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onChangeMode, enabled = enabled) { Text("改方式") }
                TextButton(onClick = onSplit, enabled = enabled && canSplit) { Text("拆分") }
                TextButton(onClick = onMerge, enabled = enabled && canMerge) { Text("合并下段") }
            }
        }
    }
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
