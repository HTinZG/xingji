# 行迹 · 请帮我评审这个 Android 应用

> 这份文档是给 AI 阅读的。里面是全部关键代码、设计决策和已知问题。
> 我需要你从**产品 / 视觉 / 工程**三个角度给我具体、可执行的改进建议。

---

## 0. 怎么用这份文档

- 读完后，请按第 9 节的问题清单逐条回答，不要泛泛而谈。
- **必须先看第 10 节的约束**：有几条红线不能碰（不做服务器、不做账号、不破坏本地优先）。
  如果你的建议违反约束，请直接说明"这条会破坏约束"，然后给替代方案。
- 代码里的注释是中文，那是作者给自己看的备忘，可以当作设计意图的补充说明。

---

## 1. 这是什么

一个**纯本地**的 Android 出行轨迹记录 App，叫「行迹」。

- 包名 `com.llyytt.path`
- 给本人和家人用，**不上架、没有服务器、没有账号**
- 数据全部在手机本地，卸载即清除

**使用者画像**：一个人，日常走路 / 骑车 / 坐车，想留下来过的路线，
偶尔回顾"我上周走了多少""哪次骑车最久"。

**核心价值主张**：不联网也能记录、数据完全属于自己、支持导出到通用格式（GPX/KML）。

---

## 2. 已完成的功能

| 功能 | 说明 |
|---|---|
| 记录 | 手动开始 / 暂停 / 结束；前台服务保证锁屏后继续记录 |
| 地图 | 高德 3D 地图，轨迹按速度或交通方式着色 |
| 行程列表 | 距离、时长、点数、断点数 |
| 行程详情 | 地图 + 四项统计 + 滑块回放（可跟随） |
| 自动分段 | 按平滑速度自动切成 走路 / 骑车 / 坐车 / 停留 |
| 手动修正 | 改方式、拆分、合并；每次操作一个事务，校验失败整体回滚 |
| 删除行程 | 详情页，级联删除子表 |
| 导出 | GPX 1.1 / KML 2.2，WGS84，可直接拖进 Google Earth |
| 分享图 | 只取地图渲染结果 + 自绘统计带（不截屏） |
| AI 聊天 | 用户自填 OpenAI 兼容接口；Key 用 Android Keystore AES-256-GCM 加密 |
| 隐私说明 | 内置七节说明页 |

### 明确不做（这些是需求层面的决定，不是遗漏）

账号体系、云同步、社交、离线地图包下载、多轮工具调用、函数调用、流式输出、AI 自动生成标题。

---

## 3. 技术栈

| 组件 | 版本 | 备注 |
|---|---|---|
| Kotlin | 2.4.21 | |
| AGP / Gradle | 8.13.2 / 8.14.3 | |
| minSdk / targetSdk / compileSdk | 26 / 36 / 36 | |
| Jetpack Compose | BOM 2025.06.01 | 全部 UI 用 Compose，无 XML 布局 |
| Room | 2.8.5 | 用 KAPT（KSP 版本对不上 Kotlin 2.4） |
| OkHttp | 4.12.0 | 只用于 AI 请求 |
| DataStore | 1.1.7 | 存 AI 配置 |
| 高德 3D 地图 SDK | 11.3.100 | jar + .so，仅 arm64-v8a |

R8 开启，release 包 44.3 MB（其中高德 .so 占 32.9 MB）。

---

## 4. 架构与五条硬规矩

| 规矩 | 原因 |
|---|---|
| 数据库只存 **WGS84**，各引擎渲染时自己转 GCJ-02 | 库里存转换后坐标会让导出 GPX 整体偏 300~600 米 |
| `MapEngine` 只暴露跨引擎能力 | 不被高德 API 绑死，将来可换 MapLibre |
| `LocationSource` 独立于 `MapEngine` | 地图加载失败不能影响记录 |
| 高德实现只在 `XjApp` 引用一次 | 换引擎只改一处 |
| Key 用 Keystore、导出用 SAF、分享用 FileProvider | 不申请存储权限 |

分层：`feature`（Compose 页面）→ `data/repo`（仓库）→ `data/db`（Room），
纯算法放在 `core/rules` 和 `core/export`，不依赖 Android，可直接 JVM 单测（现有 65 个）。

---

## 5. 数据模型

5 张表，子表全部 `onDelete = CASCADE`：

- `trip`：一条行程（开始/结束时间、状态）
- `track_point`：GPS 点（seq、lat/lng WGS84、速度、精度、时间、来源）
- `trip_segment`：分段（seq、方式、起点 seq、终点 seq），`(tripId, startPointSeq)` 唯一
- `stay_point`：停留（中心点、半径、到达/离开时间）
- `trip_pause`：暂停区间
- `ai_chat_message`：AI 聊天记录

统计（距离/时长/均速/最高）**不缓存**，每次按点现算。

---

## 6. 关键代码

下面这些文件是完整的，其它文件见第 8 节索引。

### `app/src/main/java/com/llyytt/path/XjApp.kt`

应用组装点：数据库、仓库、地图引擎的唯一创建处

```kotlin
package com.llyytt.path

import android.app.Application
import com.llyytt.path.data.ai.AiClient
import com.llyytt.path.data.ai.AiSettingsStore
import com.llyytt.path.data.db.AppDatabase
import com.llyytt.path.data.repo.AiChatRepository
import com.llyytt.path.data.repo.RecordingRepository
import com.llyytt.path.data.repo.SegmentRepository
import com.llyytt.path.data.repo.TripRepository
import com.llyytt.path.data.settings.SettingsStore
import com.llyytt.path.location.GnssLocationSource
import com.llyytt.path.location.LocationSource
import com.llyytt.path.map.MapEngine
import com.llyytt.path.mapimpl.amap.AMapEngine

/**
 * 全 App 唯一允许 import mapimpl 的地方。
 *
 * 地图引擎懒创建，且创建前检查隐私已同意 —— 从结构上保证「同意前不存在 MapView」。
 */
class XjApp : Application() {

    lateinit var settings: SettingsStore
        private set

    lateinit var database: AppDatabase
        private set

    lateinit var recordingRepository: RecordingRepository
        private set

    lateinit var tripRepository: TripRepository
        private set

    lateinit var segmentRepository: SegmentRepository
        private set

    lateinit var aiChatRepository: AiChatRepository
        private set

    lateinit var aiSettings: AiSettingsStore
        private set

    val aiClient: AiClient = AiClient()

    private var engine: MapEngine? = null
    private var location: LocationSource? = null

    val isPrivacyAccepted: Boolean
        get() = settings.privacyAccepted

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        database = AppDatabase.get(this)
        recordingRepository = RecordingRepository(database)
        tripRepository = TripRepository(database)
        segmentRepository = SegmentRepository(database)
        aiChatRepository = AiChatRepository(database.aiChatDao())
        aiSettings = AiSettingsStore(this)
        // 注意：这里**不**初始化高德 SDK，等用户同意隐私之后再 initSdk
    }

    fun acceptPrivacy() {
        settings.privacyAccepted = true
        ensureMapEngine()
    }

    fun mapEngine(): MapEngine = ensureMapEngine()

    fun locationSource(): LocationSource =
        location ?: GnssLocationSource(this).also { location = it }

    private fun ensureMapEngine(): MapEngine {
        engine?.let { return it }
        check(settings.privacyAccepted) { "隐私协议未同意，禁止创建地图引擎" }
        AMapEngine.initSdk(this)
        return AMapEngine(this).also { engine = it }
    }
}
```

### `app/src/main/java/com/llyytt/path/MainActivity.kt`

页面路由，用 sealed interface 管理所有屏幕

```kotlin
package com.llyytt.path

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.llyytt.path.feature.ai.AiChatScreen
import com.llyytt.path.feature.offline.OfflineInfoScreen
import com.llyytt.path.feature.privacy.PrivacyGateScreen
import com.llyytt.path.feature.privacy.PrivacyPolicyScreen
import com.llyytt.path.feature.record.RecordScreen
import com.llyytt.path.feature.settings.SettingsScreen
import com.llyytt.path.feature.trips.SegmentEditorScreen
import com.llyytt.path.feature.trips.TripDetailScreen
import com.llyytt.path.feature.trips.TripListScreen

private sealed interface Screen {
    data object Record : Screen
    data object Trips : Screen
    data object Settings : Screen
    data object Offline : Screen
    data object Privacy : Screen
    data object Ai : Screen
    data class Detail(val tripId: Long) : Screen
    data class Segments(val tripId: Long) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as XjApp

        setContent {
            MaterialTheme {
                var accepted by remember { mutableStateOf(app.isPrivacyAccepted) }
                var screen by remember { mutableStateOf<Screen>(Screen.Record) }

                BackHandler(enabled = accepted && screen != Screen.Record) {
                    screen = when (val current = screen) {
                        is Screen.Detail -> Screen.Trips
                        is Screen.Segments -> Screen.Detail(current.tripId)
                        is Screen.Offline -> Screen.Settings
                        is Screen.Privacy -> Screen.Settings
                        is Screen.Ai -> Screen.Record
                        else -> Screen.Record
                    }
                }

                if (!accepted) {
                    PrivacyGateScreen(
                        onAgree = {
                            app.acceptPrivacy()
                            accepted = true
                        },
                        onDecline = { finish() },
                    )
                    return@MaterialTheme
                }

                when (val current = screen) {
                    Screen.Record -> RecordScreen(
                        onOpenTrips = { screen = Screen.Trips },
                        onOpenSettings = { screen = Screen.Settings },
                        onOpenAi = { screen = Screen.Ai },
                    )
                    Screen.Trips -> TripListScreen(
                        onOpen = { screen = Screen.Detail(it) },
                        onBack = { screen = Screen.Record },
                    )
                    Screen.Settings -> SettingsScreen(
                        onBack = { screen = Screen.Record },
                        onOpenOfflineInfo = { screen = Screen.Offline },
                        onOpenPrivacy = { screen = Screen.Privacy },
                    )
                    Screen.Privacy -> PrivacyPolicyScreen(
                        onBack = { screen = Screen.Settings },
                    )
                    Screen.Offline -> OfflineInfoScreen(
                        onBack = { screen = Screen.Settings },
                    )
                    Screen.Ai -> AiChatScreen(
                        onBack = { screen = Screen.Record },
                        onOpenSettings = { screen = Screen.Settings },
                    )
                    is Screen.Detail -> TripDetailScreen(
                        tripId = current.tripId,
                        onBack = { screen = Screen.Trips },
                        onOpenSegments = { screen = Screen.Segments(current.tripId) },
                    )
                    is Screen.Segments -> SegmentEditorScreen(
                        tripId = current.tripId,
                        onBack = { screen = Screen.Detail(current.tripId) },
                    )
                }
            }
        }
    }
}
```

### `app/src/main/java/com/llyytt/path/core/rules/Tuning.kt`

所有可调阈值集中在这里（采样、过滤、分段、速度）

```kotlin
package com.llyytt.path.core.rules

/**
 * 所有可调参数集中在这里。
 *
 * 以后你说「采样太密费电」「停留判定太松」，只改这个文件，不进业务逻辑。
 */
object Tuning {

    object Sampling {
        /** 骑行/开车：≥5 m/s（18 km/h） */
        const val FAST_SPEED_MPS = 5f

        /**
         * 走路与慢速的分界。正常步行约 1.0~1.4 m/s（3.6~5.0 km/h），
         * 取 1.4 作为上界；第 5 步做交通方式分类时直接复用这个数。
         */
        const val WALK_SPEED_MPS = 1.4f

        const val FAST_INTERVAL_MS = 1000L
        const val NORMAL_INTERVAL_MS = 2000L
        const val SLOW_INTERVAL_MS = 5000L
    }

    object Filter {
        /** 精度半径差于此值的点直接丢弃，不入库 */
        const val MAX_ACCURACY_M = 100f

        /** 超过此速度基本是跳点，丢弃 */
        const val MAX_SPEED_MPS = 60f
    }

    object Segment {
        // ---- 速度分档：分段分类 与 地图「按速度着色」共用这一套，不要各写一份 ----
        /** 走路上限（含）。约 8 km/h，覆盖散步到快走 */
        const val WALK_MAX_MPS = 2.2

        /** 骑车上限（含）。约 25 km/h，超过就算车 */
        const val BIKE_MAX_MPS = 7.0

        // ---- 停留 ----
        /** 低于此速度视为静止候选 */
        const val STOP_SPEED_MPS = 0.8

        /** 连续静止超过此时长才记为停留（20 秒，能滤掉等红灯这类短暂停顿） */
        const val STOP_MIN_DURATION_MS = 20_000L

        // ---- 中断 ----
        /** 相邻两点时间差超过此值视为信号中断 */
        const val GAP_SPLIT_MS = 120_000L

        // ---- 段合并 ----
        /** 短于此长度的段并入相邻段，避免切得太碎 */
        const val MIN_SEGMENT_MS = 15_000L

        /** 分段前对速度做中位数平滑的窗口 */
        const val SMOOTH_WINDOW = 5
    }

    object Recording {
        /** 冷启动测量：超过这个时长要提示「正在搜星」 */
        const val FIRST_FIX_SLOW_MS = 20_000L

        /** 通知栏刷新间隔 */
        const val TICK_MS = 1000L

        /** 轨迹点攒够多少条落一次库（每条也落，这里只是留个扩展点） */
        const val BATCH_SIZE = 1
    }

    /**
     * 瞬时速度显示。
     *
     * 只影响「显示」：地图按速度着色用的是原始值，不走这里的平滑。
     */
    object SpeedDisplay {
        /** 中位数窗口：取最近几个有效速度 */
        const val WINDOW_SIZE = 5

        /** 低于这个速度算「可能静止」 */
        const val STATIONARY_SPEED_MPS = 0.5

        /** 连续低于阈值超过这么久，才判定为静止（避免走走停停时闪烁） */
        const val STATIONARY_HOLD_MS = 5_000L
    }
}
```

### `app/src/main/java/com/llyytt/path/core/rules/Segmenter.kt`

自动分段算法：按平滑速度分类 + 停留检测

```kotlin
package com.llyytt.path.core.rules

import com.llyytt.path.core.geo.Wgs84
import com.llyytt.path.data.db.TrackPointEntity
import com.llyytt.path.data.db.TransportMode

/** 分段草稿：只描述 seq 区间和方式，落库时才生成实体。 */
data class SegmentDraft(
    val startSeq: Int,
    val endSeq: Int,
    val mode: TransportMode,
)

/** 停留草稿。segment 里不出现 STAY，停留只在这里。 */
data class StayDraft(
    val startSeq: Int,
    val endSeq: Int,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Double,
    val arriveAt: Long,
    val leaveAt: Long,
)

data class SegmentationResult(
    val segments: List<SegmentDraft>,
    val stays: List<StayDraft>,
)

/**
 * 端上自动分段。
 *
 * 规则（全部参数在 Tuning.Segment，与地图「按速度着色」共用同一套阈值）：
 * 1. 先按 `startsNewSegment` 切「连续片」，断点两边不参与同一段。
 * 2. 片内用中位数平滑后的速度找停留：连续低于 STOP_SPEED 且时长 ≥ STOP_MIN 记为停留。
 * 3. 剩下的点在各自连续片内按速度分档（走路/骑车/车）合并成段。
 * 4. 短于 MIN_SEGMENT 的段并入相邻段，避免切得太碎。
 *
 * 注意：停留点是「空洞」，不会出现在任何 segment 的区间里。
 * startsNewSegment 只在这里被用作切分依据；校验层不会因此报错（见 TripInvariants）。
 */
object Segmenter {

    private data class RangeSeg(val start: Int, val end: Int, val mode: TransportMode)

    fun run(points: List<TrackPointEntity>): SegmentationResult {
        if (points.size < 2) return SegmentationResult(emptyList(), emptyList())

        val speeds = smoothedSpeeds(points)
        val stayFlag = BooleanArray(points.size)
        val stays = ArrayList<StayDraft>()
        val segments = ArrayList<RangeSeg>()

        for (run in contiguousRuns(points)) {
            // 1) 停留
            var i = run.first
            while (i <= run.last) {
                if (speeds[i] >= Tuning.Segment.STOP_SPEED_MPS) {
                    i++
                    continue
                }
                var j = i
                while (j < run.last && speeds[j + 1] < Tuning.Segment.STOP_SPEED_MPS) j++
                val duration = points[j].recordedAt - points[i].recordedAt
                if (duration >= Tuning.Segment.STOP_MIN_DURATION_MS) {
                    for (k in i..j) stayFlag[k] = true
                    stays.add(buildStay(points, i, j))
                }
                i = j + 1
            }

            // 2) 分段（跳过停留点）
            var segStart = -1
            var segMode: TransportMode? = null
            for (k in run) {
                if (stayFlag[k]) {
                    if (segStart >= 0) {
                        segments.add(RangeSeg(segStart, k - 1, segMode!!))
                        segStart = -1
                        segMode = null
                    }
                    continue
                }
                val mode = classify(speeds[k])
                if (segStart < 0) {
                    segStart = k
                    segMode = mode
                } else if (mode != segMode) {
                    segments.add(RangeSeg(segStart, k - 1, segMode!!))
                    segStart = k
                    segMode = mode
                }
            }
            if (segStart >= 0) segments.add(RangeSeg(segStart, run.last, segMode!!))
        }

        // 3) 合并过短的段
        mergeShort(points, segments)

        return SegmentationResult(
            segments = segments.map {
                SegmentDraft(
                    startSeq = points[it.start].seq,
                    endSeq = points[it.end].seq,
                    mode = it.mode,
                )
            }.sortedBy { it.startSeq },
            stays = stays.sortedBy { it.arriveAt },
        )
    }

    /** 速度分档。与 TrackPainter.speedBucket 用同一组阈值。 */
    fun classify(speedMps: Double): TransportMode = when {
        speedMps < Tuning.Segment.WALK_MAX_MPS -> TransportMode.WALK
        speedMps < Tuning.Segment.BIKE_MAX_MPS -> TransportMode.BIKE
        else -> TransportMode.CAR
    }

    // ---------- 内部 ----------

    private fun contiguousRuns(points: List<TrackPointEntity>): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = 0
        for (i in 1 until points.size) {
            if (points[i].startsNewSegment) {
                out.add(start until i)
                start = i
            }
        }
        out.add(start until points.size)
        return out
    }

    /** 逐点速度：优先系统值，否则用上一点算；断点后重新开始。 */
    private fun rawSpeeds(points: List<TrackPointEntity>): DoubleArray {
        val out = DoubleArray(points.size)
        var prev: TrackPointEntity? = null
        for (i in points.indices) {
            val p = points[i]
            if (p.startsNewSegment) prev = null
            val system = p.speedMps
            out[i] = when {
                system != null && system >= 0f -> system.toDouble()
                prev != null -> {
                    val last = prev!!
                    val seconds = (p.recordedAt - last.recordedAt) / 1000.0
                    if (seconds > 0.2) {
                        Wgs84(last.lat, last.lng).distanceTo(Wgs84(p.lat, p.lng)) / seconds
                    } else {
                        0.0
                    }
                }
                else -> 0.0
            }
            prev = p
        }
        return out
    }

    private fun smoothedSpeeds(points: List<TrackPointEntity>): DoubleArray {
        val raw = rawSpeeds(points)
        val out = DoubleArray(points.size)
        val window = ArrayDeque<Double>()
        for (i in points.indices) {
            if (points[i].startsNewSegment) window.clear()
            window.addLast(raw[i])
            while (window.size > Tuning.Segment.SMOOTH_WINDOW) window.removeFirst()
            out[i] = SpeedSmoother.median(window)
        }
        return out
    }

    private fun buildStay(points: List<TrackPointEntity>, start: Int, end: Int): StayDraft {
        var latSum = 0.0
        var lngSum = 0.0
        for (k in start..end) {
            latSum += points[k].lat
            lngSum += points[k].lng
        }
        val count = end - start + 1
        val centerLat = latSum / count
        val centerLng = lngSum / count
        var radius = 0.0
        for (k in start..end) {
            val d = Wgs84(centerLat, centerLng).distanceTo(Wgs84(points[k].lat, points[k].lng))
            if (d > radius) radius = d
        }
        return StayDraft(
            startSeq = points[start].seq,
            endSeq = points[end].seq,
            lat = centerLat,
            lng = centerLng,
            radiusMeters = radius,
            arriveAt = points[start].recordedAt,
            leaveAt = points[end].recordedAt,
        )
    }

    private fun duration(points: List<TrackPointEntity>, seg: RangeSeg): Long =
        points[seg.end].recordedAt - points[seg.start].recordedAt

    /**
     * 过短的段并入相邻段。
     * 只在「索引相邻」时合并——中间隔着停留的两段不能合，否则停留点会被包进 segment。
     */
    private fun mergeShort(points: List<TrackPointEntity>, segs: MutableList<RangeSeg>) {
        var changed = true
        while (changed && segs.size > 1) {
            changed = false
            for (i in segs.indices) {
                val seg = segs[i]
                if (duration(points, seg) >= Tuning.Segment.MIN_SEGMENT_MS) continue

                val prev = segs.getOrNull(i - 1)
                val next = segs.getOrNull(i + 1)
                val prevAdjacent = prev != null && prev.end + 1 == seg.start
                val nextAdjacent = next != null && seg.end + 1 == next.start

                val mergePrev = prevAdjacent &&
                    (!nextAdjacent || duration(points, prev!!) >= duration(points, next!!))

                when {
                    mergePrev -> {
                        segs[i - 1] = prev!!.copy(end = seg.end)
                        segs.removeAt(i)
                        changed = true
                    }
                    nextAdjacent -> {
                        segs[i + 1] = next!!.copy(start = seg.start)
                        segs.removeAt(i)
                        changed = true
                    }
                    else -> Unit // 两边都是停留，保持原样
                }
                if (changed) break
            }
        }
    }
}
```

### `app/src/main/java/com/llyytt/path/core/rules/SpeedDisplay.kt`

瞬时速度取样、中位数平滑、非对称静止判定

```kotlin
package com.llyytt.path.core.rules

import com.llyytt.path.core.geo.Wgs84
import com.llyytt.path.data.db.TrackPointEntity

/**
 * 瞬时速度的取数、平滑、静止判定。
 *
 * 三个明确的边界：
 * 1. 精度差的点**不参与**速度计算（不用它们做样本，也不拿它们算距离/时间）。
 * 2. 显示用平滑值（最近 N 个样本的中位数），地图着色用原始值，两者不绑死。
 * 3. 内部一律 m/s，只有展示层转 km/h。
 */
object SpeedSampler {

    /**
     * 从一次定位里取速度样本。
     *
     * 优先系统 Speed；为 null 时回退到「上一点到当前点的距离 / 时间」。
     * 返回 null 表示这次不产生样本（精度不够、没有上一点、时间差太小）。
     */
    fun sample(
        previousPoint: Wgs84?,
        previousTimeMillis: Long?,
        currentPoint: Wgs84,
        currentTimeMillis: Long,
        systemSpeedMps: Float?,
        accuracyMeters: Float,
    ): Double? {
        if (accuracyMeters > Tuning.Filter.MAX_ACCURACY_M) return null

        if (systemSpeedMps != null && systemSpeedMps >= 0f) {
            return systemSpeedMps.toDouble()
        }

        if (previousPoint == null || previousTimeMillis == null) return null
        val seconds = (currentTimeMillis - previousTimeMillis) / 1000.0
        if (seconds <= 0.2) return null

        return previousPoint.distanceTo(currentPoint) / seconds
    }
}

/**
 * 速度平滑 + 静止判定。有状态，一个行程一个实例。
 *
 * 用中位数而不是平均数：单次 GPS 跳点（比如突然 30 m/s）不会把显示带偏。
 */
class SpeedSmoother(
    private val windowSize: Int = Tuning.SpeedDisplay.WINDOW_SIZE,
    private val stationarySpeedMps: Double = Tuning.SpeedDisplay.STATIONARY_SPEED_MPS,
    private val stationaryHoldMillis: Long = Tuning.SpeedDisplay.STATIONARY_HOLD_MS,
) {

    data class Snapshot(
        val smoothedMps: Double,
        val stationary: Boolean,
    )

    private val window = ArrayDeque<Double>()
    private var belowSinceMillis: Long? = null

    fun add(speedMps: Double, atMillis: Long): Snapshot {
        window.addLast(speedMps)
        while (window.size > windowSize) window.removeFirst()

        val smoothed = median(window)

        // 非对称判定：进入静止要谨慎（连续 5 秒低于阈值），离开静止要灵敏。
        // 只靠中位数退出会有明显延迟——窗口里旧的低速样本会把中位数压住好几十秒，
        // 用户明明已经走起来了，界面还显示「静止」。所以只要本次原始样本达到阈值就立即解除。
        val below = smoothed < stationarySpeedMps && speedMps < stationarySpeedMps
        if (!below) {
            belowSinceMillis = null
        } else if (belowSinceMillis == null) {
            belowSinceMillis = atMillis
        }

        val stationary = below && belowSinceMillis?.let { atMillis - it >= stationaryHoldMillis } == true
        return Snapshot(smoothed, stationary)
    }

    fun reset() {
        window.clear()
        belowSinceMillis = null
    }

    companion object {
        fun median(values: Collection<Double>): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            val n = sorted.size
            return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        }
    }
}

/**
 * 对已存库的行程离线跑一遍同样的平滑，供详情页回放使用。
 *
 * 与实时记录用的是同一个 [SpeedSmoother]，所以两处的「静止」语义一致。
 */
object SpeedSeries {

    data class Sample(
        val smoothedMps: Double?,
        val stationary: Boolean,
    )

    fun smoothed(points: List<TrackPointEntity>): List<Sample> {
        val smoother = SpeedSmoother()
        val out = ArrayList<Sample>(points.size)

        var prev: TrackPointEntity? = null
        for (p in points) {
            if (p.startsNewSegment) {
                // 中断处重新开窗，避免把断点两端的位移当成速度
                smoother.reset()
                prev = null
            }

            val sample = SpeedSampler.sample(
                previousPoint = prev?.let { Wgs84(it.lat, it.lng) },
                previousTimeMillis = prev?.recordedAt,
                currentPoint = Wgs84(p.lat, p.lng),
                currentTimeMillis = p.recordedAt,
                systemSpeedMps = p.speedMps,
                accuracyMeters = p.accuracyM ?: -1f,
            )

            if (sample == null) {
                out.add(Sample(null, false))
            } else {
                val snap = smoother.add(sample, p.recordedAt)
                out.add(Sample(snap.smoothedMps, snap.stationary))
            }
            prev = p
        }
        return out
    }
}
```

### `app/src/main/java/com/llyytt/path/data/db/Entities.kt`

Room 实体，5 张表

```kotlin
package com.llyytt.path.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/** 行程状态。 */
enum class TripStatus { RECORDING, PAUSED, FINISHED }

/**
 * 交通方式。
 *
 * 刻意**没有 STAY**：停留不是一种「交通方式」，它由 stay_point 独立表达。
 * UNKNOWN 表示分段算法还没跑过。
 */
enum class TransportMode { UNKNOWN, WALK, BIKE, CAR }

/** 这个点是哪个定位源产出的。将来接入高德定位后，坐标变换可审计。 */
enum class PointSource { GNSS, AMAP_LOCATION }

/**
 * 行程。
 *
 * 不缓存距离和时长：距离从 track_point 实时算，时长按 DurationCalc 从
 * trip 的起止时间 + trip_pause 算。理由：手动修正分段后数字必须自动正确，
 * 缓存字段早晚会和真值漂移。
 */
@Entity(tableName = "trip")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val status: TripStatus = TripStatus.RECORDING,
    val title: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 轨迹点。
 *
 * 不变量：同一 trip 内 seq 必须 0..N-1 连续，入库后不修改、不删除。
 * 坐标永远是 WGS84。
 */
@Entity(
    tableName = "track_point",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tripId", "seq"], unique = true)],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val seq: Int,
    val lat: Double,
    val lng: Double,
    val altitudeM: Double? = null,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val accuracyM: Float? = null,
    val recordedAt: Long,
    val source: PointSource = PointSource.GNSS,
    /**
     * 这个点之前有中断（暂停或丢信号）。**只影响画线断开**，
     * 不强制这里必须切分段。
     */
    val startsNewSegment: Boolean = false,
)

/**
 * 分段结果。用「点索引范围」关联，不用外键列表。
 *
 * 拆分 = 改两行数字；合并 = 删一行改一行数字。track_point 永远不动。
 * 唯一约束放在 (tripId, startPointSeq)：同一行程里不允许两段起点相同。
 * 不对 (tripId, seq) 设唯一——拆分合并时 seq 要重排，唯一约束会很别扭。
 */
@Entity(
    tableName = "trip_segment",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["tripId", "startPointSeq"], unique = true),
        Index(value = ["tripId", "seq"]),
    ],
)
data class TripSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val seq: Int,
    val mode: TransportMode,
    /** 起点 seq（含） */
    val startPointSeq: Int,
    /** 终点 seq（含） */
    val endPointSeq: Int,
    val manuallyEdited: Boolean = false,
)

/** 停留点。segment 里没有 STAY，停留只在这里。 */
@Entity(
    tableName = "stay_point",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tripId", "arriveAt"])],
)
data class StayPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Double,
    val arriveAt: Long,
    val leaveAt: Long? = null,
)

/**
 * 暂停记录。
 *
 * 状态不变量（第 5 步校验函数会检查）：
 * - PAUSED 时：有且仅有一条 resumedAt = null 的记录
 * - RECORDING 时：不允许存在 resumedAt = null 的记录
 * - FINISHED 时：所有记录都已闭合，且 trip.endedAt 非空
 */
@Entity(
    tableName = "trip_pause",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tripId", "pausedAt"])],
)
data class TripPauseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val pausedAt: Long,
    /** null = 还在暂停中 */
    val resumedAt: Long? = null,
)

class Converters {
    @TypeConverter
    fun tripStatusToString(value: TripStatus): String = value.name

    @TypeConverter
    fun stringToTripStatus(value: String): TripStatus =
        runCatching { TripStatus.valueOf(value) }.getOrDefault(TripStatus.FINISHED)

    @TypeConverter
    fun transportModeToString(value: TransportMode): String = value.name

    @TypeConverter
    fun stringToTransportMode(value: String): TransportMode =
        runCatching { TransportMode.valueOf(value) }.getOrDefault(TransportMode.UNKNOWN)

    @TypeConverter
    fun pointSourceToString(value: PointSource): String = value.name

    @TypeConverter
    fun stringToPointSource(value: String): PointSource =
        runCatching { PointSource.valueOf(value) }.getOrDefault(PointSource.GNSS)
}
```

### `app/src/main/java/com/llyytt/path/data/repo/SegmentRepository.kt`

分段增删改：三个操作各一个事务 + 不变量校验

```kotlin
package com.llyytt.path.data.repo

import androidx.room.withTransaction
import com.llyytt.path.core.rules.SegmentDraft
import com.llyytt.path.core.rules.Segmenter
import com.llyytt.path.core.rules.StayDraft
import com.llyytt.path.core.rules.TripInvariants
import com.llyytt.path.data.db.AppDatabase
import com.llyytt.path.data.db.StayPointEntity
import com.llyytt.path.data.db.TrackPointEntity
import com.llyytt.path.data.db.TransportMode
import com.llyytt.path.data.db.TripSegmentEntity

/**
 * 分段的写操作。三个操作各自一个事务。
 *
 * 铁律：**原始 track_point 永远不修改**。分段只是「点的区间 + 方式」，
 * 所有编辑都只动 trip_segment / stay_point。
 */
class SegmentRepository(private val db: AppDatabase) {

    /** 跑一次自动分段并覆盖旧结果。返回段数。 */
    suspend fun autoSegment(tripId: Long): Int = db.withTransaction {
        val points = db.trackPointDao().getByTrip(tripId)
        val result = Segmenter.run(points)

        db.tripSegmentDao().deleteByTrip(tripId)
        db.stayPointDao().deleteByTrip(tripId)

        db.tripSegmentDao().insertAll(
            result.segments.mapIndexed { index, draft ->
                TripSegmentEntity(
                    tripId = tripId,
                    seq = index,
                    mode = draft.mode,
                    startPointSeq = draft.startSeq,
                    endPointSeq = draft.endSeq,
                )
            },
        )
        db.stayPointDao().insertAll(
            result.stays.map {
                StayPointEntity(
                    tripId = tripId,
                    lat = it.lat,
                    lng = it.lng,
                    radiusMeters = it.radiusMeters,
                    arriveAt = it.arriveAt,
                    leaveAt = it.leaveAt,
                )
            },
        )

        validateOrThrow(tripId)
        result.segments.size
    }

    /** 只改方式：不动 seq、不重排、不跑全局校验。 */
    suspend fun setMode(segmentId: Long, mode: TransportMode) = db.withTransaction {
        val segment = db.tripSegmentDao().findById(segmentId) ?: error("分段不存在")
        db.tripSegmentDao().update(segment.copy(mode = mode, manuallyEdited = true))
    }

    /**
     * 拆分：在 atSeq 处切开，atSeq 属于后段。
     *
     * 步骤（同一事务）：
     * 1. 前段 endPointSeq = atSeq - 1
     * 2. 后续段 seq 顺移（从后往前，避免中间态冲突）
     * 3. 插入后段
     * 4. 跑不变量校验；不通过就抛异常，Room 回滚整个事务
     */
    suspend fun split(segmentId: Long, atSeq: Int) = db.withTransaction {
        val target = db.tripSegmentDao().findById(segmentId) ?: error("分段不存在")
        require(atSeq in (target.startPointSeq + 1)..target.endPointSeq) {
            "拆分点必须落在段内部（$atSeq 不在 ${target.startPointSeq + 1}..${target.endPointSeq}）"
        }
        val tripId = target.tripId
        val all = db.tripSegmentDao().getByTrip(tripId).sortedBy { it.seq }

        db.tripSegmentDao().update(target.copy(endPointSeq = atSeq - 1, manuallyEdited = true))

        all.filter { it.seq > target.seq }
            .sortedByDescending { it.seq }
            .forEach { db.tripSegmentDao().update(it.copy(seq = it.seq + 1)) }

        db.tripSegmentDao().insert(
            TripSegmentEntity(
                tripId = tripId,
                seq = target.seq + 1,
                mode = target.mode,
                startPointSeq = atSeq,
                endPointSeq = target.endPointSeq,
                manuallyEdited = true,
            ),
        )

        validateOrThrow(tripId)
    }

    /**
     * 合并相邻两段：保留前段、删掉后段、后续 seq 前移。
     */
    suspend fun merge(firstId: Long, secondId: Long) = db.withTransaction {
        val first = db.tripSegmentDao().findById(firstId) ?: error("分段不存在")
        val second = db.tripSegmentDao().findById(secondId) ?: error("分段不存在")
        require(first.tripId == second.tripId) { "不能跨行程合并" }
        require(first.endPointSeq + 1 == second.startPointSeq) { "只能合并相邻的两段" }

        val tripId = first.tripId
        db.tripSegmentDao().update(first.copy(endPointSeq = second.endPointSeq, manuallyEdited = true))
        db.tripSegmentDao().deleteById(second.id)

        db.tripSegmentDao().getByTrip(tripId)
            .filter { it.seq > second.seq }
            .sortedBy { it.seq }
            .forEach { db.tripSegmentDao().update(it.copy(seq = it.seq - 1)) }

        validateOrThrow(tripId)
    }

    /**
     * 校验失败就抛，让事务回滚。绝不静默吞掉。
     */
    private suspend fun validateOrThrow(tripId: Long) {
        val points = db.trackPointDao().getByTrip(tripId)
        val segments = db.tripSegmentDao().getByTrip(tripId).map {
            SegmentDraft(it.startPointSeq, it.endPointSeq, it.mode)
        }
        val stays = db.stayPointDao().getByTrip(tripId)
        val violations = TripInvariants.validateSegments(points, segments, toDrafts(stays, points))
        if (violations.isNotEmpty()) {
            throw IllegalStateException(
                "分段校验失败：" + violations.joinToString { "${it.code}(${it.message})" },
            )
        }
    }

    /**
     * stay_point 表里没有存 seq 区间（只存了中心点、半径、到达/离开时间）。
     * 校验时用时间戳把它映射回点的下标 —— 因为 arriveAt/leaveAt 本来就取自点的 recordedAt，
     * 能精确对上。这样避免了为校验再加两个字段和一次 schema 迁移。
     */
    private fun toDrafts(stays: List<StayPointEntity>, points: List<TrackPointEntity>): List<StayDraft> =
        stays.mapNotNull { stay ->
            val start = points.indexOfFirst { it.recordedAt >= stay.arriveAt }
            val end = points.indexOfLast { it.recordedAt <= (stay.leaveAt ?: Long.MAX_VALUE) }
            if (start < 0 || end < start) {
                null
            } else {
                StayDraft(
                    startSeq = points[start].seq,
                    endSeq = points[end].seq,
                    lat = stay.lat,
                    lng = stay.lng,
                    radiusMeters = stay.radiusMeters,
                    arriveAt = stay.arriveAt,
                    leaveAt = stay.leaveAt ?: stay.arriveAt,
                )
            }
        }
}
```

### `app/src/main/java/com/llyytt/path/feature/record/RecordScreen.kt`

记录页 UI（最主要的界面之一）

```kotlin
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
```

### `app/src/main/java/com/llyytt/path/feature/trips/TripListScreen.kt`

行程列表页 UI

```kotlin
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
```

### `app/src/main/java/com/llyytt/path/feature/trips/TripDetailScreen.kt`

行程详情页 UI（地图 + 统计 + 回放）

```kotlin
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
```

### `app/src/main/java/com/llyytt/path/feature/settings/SettingsScreen.kt`

设置页 UI（速度开关 / 离线说明 / AI 配置）

```kotlin
package com.llyytt.path.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.llyytt.path.XjApp
import com.llyytt.path.data.ai.UrlValidator
import kotlinx.coroutines.launch

/**
 * 设置页。
 *
 * 两组：显示偏好（瞬时速度开关）和 AI 服务（BaseURL / 模型 / Key）。
 * Key 输入框永远回显空，只提示"已保存"——避免明文在屏幕上长期停留。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenOfflineInfo: () -> Unit,
    onOpenPrivacy: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as XjApp
    val scope = rememberCoroutineScope()

    var showSpeed by remember { mutableStateOf(app.settings.showInstantSpeed) }

    val config by app.aiSettings.config.collectAsState(initial = null)
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var keyInput by remember { mutableStateOf("") }
    var initialized by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingHttpUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(config) {
        val c = config
        if (!initialized && c != null) {
            baseUrl = c.baseUrl
            model = c.model
            initialized = true
        }
    }

    fun doSave(url: String) {
        scope.launch {
            runCatching {
                app.aiSettings.save(url, model.trim(), keyInput.trim())
            }.onSuccess {
                baseUrl = url
                keyInput = ""
                status = "已保存"
            }.onFailure {
                status = "保存失败：${it.message}"
            }
        }
    }

    fun save() {
        status = null
        when (val outcome = UrlValidator.validate(baseUrl)) {
            is UrlValidator.Outcome.Invalid -> status = outcome.reason
            is UrlValidator.Outcome.Https -> doSave(outcome.normalized)
            is UrlValidator.Outcome.Http -> pendingHttpUrl = outcome.normalized
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
                Text(text = "设置", style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider()

            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
            ) {
                // ---------- 显示 ----------
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("显示瞬时速度", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "记录页显示实时速度，回放时显示该时刻速度",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = showSpeed,
                        onCheckedChange = {
                            showSpeed = it
                            app.settings.showInstantSpeed = it
                        },
                    )
                }

                Text(
                    text = "默认关闭。室外步行精度验证通过前，建议先关着——" +
                        "室内没有卫星信号时打开会一直显示「等待定位」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )

                HorizontalDivider(modifier = Modifier.padding(top = 16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenOfflineInfo() }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("离线地图说明", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "弱网时能看到多少、为什么不做下载",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPrivacy() }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("隐私说明", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "数据存在哪、AI 请求发到哪、怎么删",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()

                // ---------- AI ----------
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text("AI 服务", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "OpenAI 兼容接口。Key 用系统级加密存本机，不写日志、不进导出文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(12.dp))

                    // 当前已保存了什么——不显示 Key 明文，只说"已保存"
                    val saved = config
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "当前已保存",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            if (saved == null || !saved.isReady) {
                                Text(
                                    text = "还没有配置。填下面的三项并保存即可。",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            } else {
                                Text(
                                    text = "BaseURL：${saved.baseUrl}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = "模型：${saved.model}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = "Key：已保存（加密存储，不显示原文）",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(Modifier.height(8.dp))
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            app.aiSettings.clear()
                                            baseUrl = ""
                                            model = ""
                                            keyInput = ""
                                            status = "已清除 AI 配置"
                                        }
                                    },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                ) {
                                    Text("清除配置", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it; status = null },
                        label = { Text("BaseURL") },
                        placeholder = { Text("https://api.openai.com/v1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it; status = null },
                        label = { Text("模型名") },
                        placeholder = { Text("gpt-4o-mini") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it; status = null },
                        label = { Text("API Key") },
                        placeholder = {
                            Text(if (config?.apiKey?.isNotBlank() == true) "已保存，留空表示不修改" else "sk-...")
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { save() }) { Text("保存") }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = status.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status == "已保存") {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    pendingHttpUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { pendingHttpUrl = null },
            title = { Text("这是明文传输") },
            text = {
                Text(
                    "http:// 不加密。你和服务器之间的任何一跳都能看到 Key 和行程内容，" +
                        "公共 Wi-Fi 下尤其危险。\n\n确定要用 $url 吗？",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingHttpUrl = null
                    doSave(url)
                }) { Text("我知道风险，继续") }
            },
            dismissButton = {
                TextButton(onClick = { pendingHttpUrl = null }) { Text("改用 https") }
            },
        )
    }
}
```

### `app/src/main/java/com/llyytt/path/core/export/TrackExporter.kt`

GPX/KML 生成器，纯函数可单测

```kotlin
package com.llyytt.path.core.export

import com.llyytt.path.data.db.TransportMode
import java.util.Locale

/**
 * GPX 1.1 / KML 2.2 生成器。
 *
 * 三条硬规则：
 * 1. **坐标一律 WGS84**，不做任何偏移转换。Google Earth、GPX 查看器都按 WGS84 解释，
 *    这里要是偷偷转成 GCJ-02，导出的轨迹整体会偏 300~600 米——也就是"位置偏移"。
 * 2. 时间一律 ISO 8601 UTC（`2026-11-16T12:00:00Z`）。
 * 3. 只写轨迹点、时间、分段方式、停留点。不写任何配置、不写任何对话。
 *
 * 纯函数，无 Android 依赖，可单测。
 */
object TrackExporter {

    enum class Format(val extension: String, val mimeType: String) {
        GPX("gpx", "application/gpx+xml"),
        KML("kml", "application/vnd.google-earth.kml+xml"),
    }

    /** 出现这些片段就拒绝导出——防御性检查，正常内容里不可能有。 */
    private val FORBIDDEN_TOKENS = listOf("sk-", "Authorization", "Bearer ", "api_key", "apiKey")

    /**
     * GPX `<type>` 的取值。集中在一个常量里，避免散落在拼接逻辑中改一处漏一处。
     * 取值用 GPX 社区通行写法，Google Earth / Strava / gpx.studio 都能识别。
     */
    private val GPX_TYPE: Map<TransportMode, String> = mapOf(
        TransportMode.WALK to "walking",
        TransportMode.BIKE to "cycling",
        TransportMode.CAR to "driving",
        TransportMode.UNKNOWN to "unknown",
    )

    /** KML 线颜色，aabbggrr（KML 和常规 ARGB 反着来）。 */
    private val KML_COLOR: Map<TransportMode, String> = mapOf(
        TransportMode.WALK to "ff4caf50",
        TransportMode.BIKE to "ff03a9f4",
        TransportMode.CAR to "ffff9800",
        TransportMode.UNKNOWN to "ff9e9e9e",
    )

    fun build(detail: TripDetailForExport, format: Format): String =
        when (format) {
            Format.GPX -> toGpx(detail)
            Format.KML -> toKml(detail)
        }

    // ---------------------------------------------------------------- GPX 1.1

    fun toGpx(detail: TripDetailForExport): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine(
            """<gpx version="1.1" creator="Xingji" """ +
                """xmlns="http://www.topografix.com/GPX/1/1" """ +
                """xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """ +
                """xsi:schemaLocation="http://www.topografix.com/GPX/1/1 """ +
                """http://www.topografix.com/GPX/1/1/gpx.xsd">""",
        )
        appendLine("  <metadata>")
        appendLine("    <name>${escape(detail.title)}</name>")
        appendLine("    <time>${iso8601(detail.startedAt)}</time>")
        appendLine("  </metadata>")

        // 停留点放在最前面，查看器里会显示成独立标记
        detail.stays.forEachIndexed { index, stay ->
            appendLine("""  <wpt lat="${coord(stay.lat)}" lon="${coord(stay.lng)}">""")
            appendLine("    <name>停留 ${index + 1}</name>")
            appendLine("    <time>${iso8601(stay.arriveAt)}</time>")
            val desc = "停留 ${(stay.durationMillis / 1000)} 秒"
            appendLine("    <desc>${escape(desc)}</desc>")
            appendLine("  </wpt>")
        }

        // 每段一个 trk：GPX 的 trkseg 装不下"方式"，拆成多个 trk 才能保住这个信息
        detail.segments.forEachIndexed { index, segment ->
            appendLine("  <trk>")
            appendLine("    <name>第 ${index + 1} 段 · ${modeLabel(segment.mode)}</name>")
            appendLine("    <type>${GPX_TYPE[segment.mode] ?: GPX_TYPE.getValue(TransportMode.UNKNOWN)}</type>")
            appendLine("    <trkseg>")
            for (seq in segment.startSeq..segment.endSeq) {
                val point = detail.pointAt(seq) ?: continue
                appendLine("""      <trkpt lat="${coord(point.lat)}" lon="${coord(point.lng)}">""")
                appendLine("        <time>${iso8601(point.recordedAt)}</time>")
                appendLine("      </trkpt>")
            }
            appendLine("    </trkseg>")
            appendLine("  </trk>")
        }
        append("</gpx>")
    }

    // ---------------------------------------------------------------- KML 2.2

    fun toKml(detail: TripDetailForExport): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<kml xmlns="http://www.opengis.net/kml/2.2">""")
        appendLine("  <Document>")
        appendLine("    <name>${escape(detail.title)}</name>")
        appendLine("    <description>${escape("导出于 ${iso8601(detail.exportedAt)}，坐标 WGS84")}</description>")

        // 每段一个独立 Style：id 带段号，即使两段同方式也不会撞 id。
        // 这样以后想按段给不同颜色（而不是按方式），不用改 id 规则。
        detail.segments.forEachIndexed { index, segment ->
            val styleId = styleIdFor(index, segment.mode)
            appendLine("    <Style id=\"$styleId\">")
            appendLine("      <LineStyle><color>${KML_COLOR[segment.mode]}</color><width>4</width></LineStyle>")
            appendLine("    </Style>")
            appendLine("    <Placemark>")
            appendLine("      <name>${escape("第 ${index + 1} 段 · ${modeLabel(segment.mode)}")}</name>")
            appendLine("      <styleUrl>#$styleId</styleUrl>")
            appendLine("      <LineString>")
            appendLine("        <tessellate>1</tessellate>")
            append("<coordinates>")
            // KML 是 经度,纬度,高程 —— 顺序和人们说话时相反，这里不能写反
            for (seq in segment.startSeq..segment.endSeq) {
                val point = detail.pointAt(seq) ?: continue
                append("${coord(point.lng)},${coord(point.lat)},0 ")
            }
            appendLine("</coordinates>")
            appendLine("      </LineString>")
            appendLine("    </Placemark>")
        }

        detail.stays.forEachIndexed { index, stay ->
            appendLine("    <Placemark>")
            appendLine("      <name>${escape("停留 ${index + 1}")}</name>")
            appendLine("      <Point><coordinates>${coord(stay.lng)},${coord(stay.lat)},0</coordinates></Point>")
            appendLine("    </Placemark>")
        }

        appendLine("  </Document>")
        append("</kml>")
    }

    // ---------------------------------------------------------------- 工具

    /** 导出前的最后一道闸：发现可疑片段就返回问题列表，调用方必须中止。 */
    fun scanForSensitiveContent(text: String, extraForbidden: List<String> = emptyList()): List<String> {
        val findings = mutableListOf<String>()
        (FORBIDDEN_TOKENS + extraForbidden).forEach { token ->
            if (token.isNotBlank() && text.contains(token, ignoreCase = true)) {
                findings += "命中「$token」"
            }
        }
        return findings
    }

    /** ISO 8601，UTC，秒级。GPX/KML 都按这个解释。 */
    fun iso8601(epochMillis: Long): String =
        java.time.Instant.ofEpochMilli(epochMillis)
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            .toString()

    /** 6 位小数约 0.1 米，足够；固定 Locale.US 防止某些地区写出逗号小数点。 */
    fun coord(value: Double): String = String.format(Locale.US, "%.6f", value)

    /**
     * KML 样式 id：`seg_<序号>_<方式>`。
     * 带段号是为了保证唯一——同一行程里两段都是骑车时，只按方式命名会撞 id。
     */
    fun styleIdFor(segmentIndex: Int, mode: TransportMode): String =
        "seg_${segmentIndex + 1}_${mode.name.lowercase()}"

    private fun modeLabel(mode: TransportMode): String = when (mode) {
        TransportMode.WALK -> "走路"
        TransportMode.BIKE -> "骑车"
        TransportMode.CAR -> "坐车"
        TransportMode.UNKNOWN -> "未分类"
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
```

### `app/src/main/java/com/llyytt/path/data/ai/AiClient.kt`

AI 请求：OkHttp、超时、错误映射

```kotlin
package com.llyytt.path.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

data class ChatTurn(val role: String, val content: String)

sealed interface AiResult {
    data class Success(val text: String) : AiResult
    data class Failure(val message: String, val statusCode: Int? = null) : AiResult
}

/**
 * OpenAI 兼容接口的最小客户端。
 *
 * 三条硬约束：
 * 1. **不加任何 OkHttp logging interceptor** —— 那玩意儿默认会把 Authorization 头打进 logcat。
 * 2. Key 只出现在请求头里，不进 URL、不进 body、不进日志。
 * 3. 连接 10 秒 / 读 60 秒，超时按"网络超时"上报。
 */
class AiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun chat(config: AiConfig, turns: List<ChatTurn>): AiResult = withContext(Dispatchers.IO) {
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"

        val payload = JSONObject().apply {
            put("model", config.model)
            put("temperature", 0.3)
            put(
                "messages",
                JSONArray().apply {
                    turns.forEach {
                        put(JSONObject().put("role", it.role).put("content", it.content))
                    }
                },
            )
        }.toString()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + config.apiKey)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> {
                        val content = runCatching {
                            JSONObject(raw)
                                .getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content")
                        }.getOrNull()
                        if (content.isNullOrBlank()) {
                            AiResult.Failure("响应格式不正确（没有 choices[0].message.content）", response.code)
                        } else {
                            AiResult.Success(content)
                        }
                    }

                    response.code == 401 -> AiResult.Failure("Key 无效或已过期", 401)
                    response.code == 429 -> AiResult.Failure("请求太频繁", 429)
                    else -> AiResult.Failure("服务返回 HTTP ${response.code}", response.code)
                }
            }
        } catch (e: SocketTimeoutException) {
            AiResult.Failure("网络超时")
        } catch (e: IOException) {
            AiResult.Failure("网络错误：${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            AiResult.Failure("请求失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
```

---

## 7. 界面现状（我自己的评价）

目前所有页面都用 **Material3 默认主题**，没有自定义配色、没有自定义字体、没有插画/图标资源。
主要界面只有文字和系统控件。

界面截图在这个仓库的 `docs/screenshots/` 下，其中一张合成了 6 个主要界面：
`docs/screenshots/contact-sheet.png`（记录中 / 行程列表 / 行程详情 / 分段编辑 / 设置 / 分享图）

已知的视觉问题：

1. **没有品牌感**：整个 App 就是 Material 默认紫色，和"记录足迹"这件事没有情绪关联。
2. **记录页太空**：一屏只有数字和两个按钮，大量留白没有利用（现在没有地图，因为记录时不显示地图）。
3. **列表页信息密度低**：每张卡片四行，只能显示 4 条行程，滚动成本高。
4. **详情页底部卡片拥挤**：统计、meta、滑块、三个按钮挤在一个卡片里。
5. **没有空状态设计**：没有行程时只显示"还没有行程，先去记录一次"一行字。
6. **没有动效**：页面切换、数字变化、按钮反馈都没有过渡。
7. **分享图**：白底 + 灰色文字，没有品牌色、没有 logo、没有日期排版设计。

---

## 8. 其余文件索引

（只有路径和一句话说明，代码未附）

| 文件 | 说明 |
|---|---|
| `app/src/main/java/com/llyytt/path/core/export/ExportModels.kt` | 导出用的扁平模型。 |
| `app/src/main/java/com/llyytt/path/core/export/ShareComposer.kt` | 分享图合成。 |
| `app/src/main/java/com/llyytt/path/core/format/Formatters.kt` | 展示层格式化。集中一处，避免每个页面各写一份导致口径不一致。 |
| `app/src/main/java/com/llyytt/path/core/geo/PolylineSimplifier.kt` | 折线抽稀（Douglas-Peucker）。 |
| `app/src/main/java/com/llyytt/path/core/geo/ScreenHitTest.kt` | 屏幕像素坐标（左上角原点，y 向下）。纯数据，不依赖 Android / 地图 SDK。 |
| `app/src/main/java/com/llyytt/path/core/geo/Wgs84.kt` | WGS-84 坐标：本 App 内部唯一合法的坐标类型。 |
| `app/src/main/java/com/llyytt/path/core/rules/DurationCalc.kt` | 活动时长计算。不缓存，每次按当前状态现算。 |
| `app/src/main/java/com/llyytt/path/core/rules/TripInvariants.kt` | 数据一致性校验。 |
| `app/src/main/java/com/llyytt/path/core/rules/TripStats.kt` | 中断次数（暂停 / 丢信号） |
| `app/src/main/java/com/llyytt/path/data/ai/AiSettingsStore.kt` | AI 配置。apiKey 是解密后的明文，只活在内存里。 |
| `app/src/main/java/com/llyytt/path/data/ai/KeyCrypto.kt` | AI Key 的本地加密。 |
| `app/src/main/java/com/llyytt/path/data/ai/TripContextBuilder.kt` | 给 AI 的上下文：**只发摘要，不发全量轨迹点**。 |
| `app/src/main/java/com/llyytt/path/data/ai/UrlValidator.kt` | BaseURL 校验。 |
| `app/src/main/java/com/llyytt/path/data/db/AiChatEntities.kt` | AI 聊天记录。 |
| `app/src/main/java/com/llyytt/path/data/db/AppDatabase.kt` | 1 -> 2 的开发期迁移：丢表重建（v1 从未对外发布，只有测试机有数据）。 |
| `app/src/main/java/com/llyytt/path/data/db/Daos.kt` | 进程被杀后重启时用它找回「没结束的行程」。最多一条（由业务层保证）。 |
| `app/src/main/java/com/llyytt/path/data/repo/AiChatRepository.kt` | 聊天记录。只存本地，不进导出文件；清空数据时一起删。 |
| `app/src/main/java/com/llyytt/path/data/repo/RecordingRepository.kt` | 开始记录的结果。已有未结束行程时不能悄悄再开一条。 |
| `app/src/main/java/com/llyytt/path/data/repo/TripRepository.kt` | 只读查询。写入仍然走 RecordingRepository，职责分开。 |
| `app/src/main/java/com/llyytt/path/data/settings/SettingsStore.kt` | 轻量设置存储。 |
| `app/src/main/java/com/llyytt/path/feature/ai/AiChatScreen.kt` | AI 聊天。 |
| `app/src/main/java/com/llyytt/path/feature/mapdemo/MapDemoScreen.kt` | 天安门附近的一圈假轨迹，只用来验证“能画线”。第 3 步会换成真实记录。 |
| `app/src/main/java/com/llyytt/path/feature/offline/OfflineInfoScreen.kt` | 离线地图说明页。 |
| `app/src/main/java/com/llyytt/path/feature/privacy/PrivacyGateScreen.kt` | 首启隐私门禁。 |
| `app/src/main/java/com/llyytt/path/feature/privacy/PrivacyPolicyScreen.kt` | 隐私说明页。 |
| `app/src/main/java/com/llyytt/path/feature/trips/SegmentEditorScreen.kt` | 分段编辑器：自动分段 + 三个手动操作。 |
| `app/src/main/java/com/llyytt/path/feature/trips/TrackPainter.kt` | 把轨迹点转成地图能画的折线。 |
| `app/src/main/java/com/llyytt/path/location/GnssLocationSource.kt` | 系统 GNSS 定位。MVP 唯一的定位实现。 |
| `app/src/main/java/com/llyytt/path/location/LocationSource.kt` | 定位失败原因。冷启动实测出问题时，靠它区分「没信号」还是「provider 没开」。 |
| `app/src/main/java/com/llyytt/path/map/MapEngine.kt` | 离线能力分级。 |
| `app/src/main/java/com/llyytt/path/mapimpl/amap/AMapEngine.kt` | 高德 3D 地图实现。整个 App 里唯一 import com.amap 的地方。 |
| `app/src/main/java/com/llyytt/path/service/RecordingService.kt` | 记录前台服务。 |
| `app/src/main/java/com/llyytt/path/service/RecordingStateHolder.kt` | 记录页的实时状态。服务写，UI 读。 |
| `app/src/test/java/com/llyytt/path/core/export/TrackExporterTest.kt` | — |
| `app/src/test/java/com/llyytt/path/core/geo/PolylineHitTestTest.kt` | PolylineHitTest 的单元测试。 |
| `app/src/test/java/com/llyytt/path/core/geo/PolylineSimplifierTest.kt` | — |
| `app/src/test/java/com/llyytt/path/core/rules/DurationCalcTest.kt` | — |
| `app/src/test/java/com/llyytt/path/core/rules/SegmenterTest.kt` | 合成轨迹：走 2 分钟 → 停 30 秒 → 快走 1 分钟 → 骑车 2 分钟。 |
| `app/src/test/java/com/llyytt/path/core/rules/SpeedDisplayTest.kt` | — |
| `app/src/test/java/com/llyytt/path/core/rules/TripInvariantsTest.kt` | — |

---

## 9. 我想得到的建议（请逐条回答）

### A. 产品与功能

1. 对"一个人记录日常出行"这个场景，现在最缺的 3 个功能是什么？按价值排序并说明理由。
2. 行程列表只显示 4 条（信息密度低），你会怎么重新设计这一屏？
3. 记录页现在没有地图（记录时不显示），这是对还是错？如果加地图，有什么代价？

### B. 视觉与体验

4. 如果只做**三处**视觉改动就能让这个 App 看起来专业很多，你会改哪三处？请给具体方案（颜色值、字号、间距）。
5. 现在用 Material3 默认紫色。给「足迹 / 行程 / 户外」这个主题配一套颜色方案，给出十六进制值和使用场景。
6. 记录页那种"超大数字"的排版，你会怎么改进层级？
7. 列表卡片、详情页底部卡片，具体怎么写布局（给 Compose 伪代码或参数）？

### C. 工程与性能

8. 行程列表现在是 N+1 查询（每条行程单独查点、查段数），50 条行程会慢。你会怎么改？
9. 记录服务每秒处理一个 GPS 点并写库，电量消耗怎么评估？有什么优化空间？
10. 单 Activity + `sealed interface Screen` 的手写路由，在页面变多后会有什么问题？值得换成 Navigation Compose 吗？

### D. 代码质量

11. 看一下 `SegmentRepository` 的事务写法，有没有边界情况会漏（比如并发编辑、行程被删）？
12. `Segmenter` 的算法有没有明显的分类错误场景？给几个真实轨迹的例子说明会怎么误判。
13. 哪些地方属于**过度设计**（对一个人用的 App 来说）？哪些属于**设计不足**？

### E. 隐私与安全

14. AI Key 用 Keystore AES-256-GCM 加密存 DataStore，这个方案有什么漏洞？
15. 允许用户配置 http（明文）BaseURL，虽然做了二次确认，还有什么风险？

---

## 10. 约束（重要，请务必遵守）

这些是这个项目的**前提**，违反它们的建议我不会采纳：

1. **不做服务器**。没有后端、没有账号、没有云同步。任何"上传到云端再分析"的方案都不行。
2. **不做账号体系**。用户就是一个人，不需要登录。
3. **不破坏本地优先**。断网时除了地图和 AI 之外所有功能必须可用。
4. **不申请存储权限**。导出用 SAF，分享用 FileProvider。
5. **高德商用授权**：上架或商用需要付费（约 5 万元/年），所以方案不能依赖"购买更多高德服务"。
6. **不能用需要审核 / 企业资质的第三方 SDK**（比如推送、统计）。
7. **保持单模块、可 KAPT 构建**，不引入会大幅提高构建复杂度的方案（如注解处理器链、多模块拆分）。

如果你的建议触碰了以上任何一条，请明确标注「违反约束 X」，然后给替代方案。
