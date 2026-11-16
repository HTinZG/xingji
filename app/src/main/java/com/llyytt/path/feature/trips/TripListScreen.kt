package com.llyytt.path.feature.trips

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.llyytt.path.XjApp
import com.llyytt.path.core.format.Fmt
import com.llyytt.path.data.db.TripStatus
import com.llyytt.path.data.repo.TripListItem

@Composable
fun TripListScreen(onOpen: (Long) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as XjApp

    var items by remember { mutableStateOf<List<TripListItem>?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        items = app.tripRepository.list()
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
                    text = "行程",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { reload++ }) { Text("刷新") }
            }

            val list = items
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有行程，先去记录一次", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
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
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Fmt.dateTime(trip.startedAt),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = statusLabel(trip.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (trip.status == TripStatus.FINISHED) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Metric("距离", Fmt.distance(stats.distanceMeters))
                Metric("时长", Fmt.duration(stats.activeMillis))
                Metric("点", "${stats.pointCount}")
                if (stats.gapCount > 0) Metric("断点", "${stats.gapCount}")
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun statusLabel(status: TripStatus): String = when (status) {
    TripStatus.RECORDING -> "记录中"
    TripStatus.PAUSED -> "已暂停"
    TripStatus.FINISHED -> "已结束"
}
