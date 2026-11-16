package com.llyytt.path.feature.mapdemo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.llyytt.path.XjApp
import com.llyytt.path.core.geo.Wgs84
import com.llyytt.path.core.geo.Wgs84Bounds
import com.llyytt.path.map.MapPolyline
import com.llyytt.path.map.MapScene
import com.llyytt.path.map.OfflineCapability
import kotlinx.coroutines.launch

/** 天安门附近的一圈假轨迹，只用来验证“能画线”。第 3 步会换成真实记录。 */
private val DEMO_TRACK = listOf(
    Wgs84(39.90870, 116.39750),
    Wgs84(39.90960, 116.39910),
    Wgs84(39.91040, 116.39690),
    Wgs84(39.90910, 116.39560),
    Wgs84(39.90870, 116.39750),
)

@Composable
fun MapDemoScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as XjApp
    val engine = app.mapEngine()
    val controller = remember { engine.createController() }
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("地图加载中…") }

    fun locateNow() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            status = "需要定位权限才能「定位到我」"
            return
        }
        val me = readLastKnownWgs84(context)
        if (me == null) {
            status = "系统还没有定位缓存：先打开任意地图 App 定位一次，再回来点"
            return
        }
        controller.moveCameraTo(me, 16f)
        status = "已定位：%.5f, %.5f".format(me.lat, me.lng)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) locateNow()
        else status = "定位权限被拒绝：只影响「定位到我」，不影响地图显示"
    }

    LaunchedEffect(controller) {
        controller.setOnPolylineClickListener { id ->
            Toast.makeText(context, "点到折线：$id", Toast.LENGTH_SHORT).show()
        }
        controller.render(
            MapScene(
                polylines = listOf(
                    MapPolyline(
                        id = "demo",
                        points = DEMO_TRACK,
                        colorArgb = 0xFF2F6B57.toInt(),
                    )
                )
            )
        )
        Wgs84Bounds.of(DEMO_TRACK)?.let { controller.fitBounds(it, paddingDp = 96) }
        status = "地图就绪。绿色线是天安门附近的测试轨迹"
    }

    Box(modifier = Modifier.fillMaxSize()) {
        engine.MapView(controller = controller, modifier = Modifier.fillMaxSize())

        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp)
                .fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "离线能力：" + when (engine.offlineCapability) {
                        OfflineCapability.NONE -> "无"
                        OfflineCapability.CACHE_ONLY -> "仅 SDK 底图缓存（弱网可看部分区域，不保证完整）"
                        OfflineCapability.FULL -> "完整离线包"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = status, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) locateNow()
                        else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }) {
                        Text("定位到我")
                    }
                    OutlinedButton(onClick = {
                        Wgs84Bounds.of(DEMO_TRACK)?.let { controller.fitBounds(it, paddingDp = 96) }
                    }) {
                        Text("复位")
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val bmp = controller.snapshot()
                            status = if (bmp != null) {
                                "截图成功：${bmp.width} × ${bmp.height}（第 8 步会存相册）"
                            } else {
                                "截图失败：地图还没渲染完"
                            }
                        }
                    }) {
                        Text("截图")
                    }
                }
            }
        }
    }
}

/**
 * 取最近一次已知位置。只用于第 2 步的“地图先动起来”，
 * 第 3 步会换成正式的 LocationSource（持续、可配置采样）。
 */
private fun readLastKnownWgs84(context: Context): Wgs84? {
    val fine = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) return null

    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val candidates = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
    ).mapNotNull { provider ->
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    }
    val best = candidates.maxByOrNull { it.time } ?: return null
    return Wgs84(best.latitude, best.longitude)
}
