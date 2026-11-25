package com.llyytt.path.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.llyytt.path.MainActivity
import com.llyytt.path.XjApp
import com.llyytt.path.core.geo.Wgs84
import com.llyytt.path.core.rules.DurationCalc
import com.llyytt.path.core.rules.SpeedSampler
import com.llyytt.path.core.rules.SpeedSmoother
import com.llyytt.path.core.rules.Tuning
import com.llyytt.path.data.db.TrackPointEntity
import com.llyytt.path.data.db.TripEntity
import com.llyytt.path.data.db.TripStatus
import com.llyytt.path.data.repo.RecordingRepository
import com.llyytt.path.data.settings.SettingsStore
import com.llyytt.path.location.LocationError
import com.llyytt.path.location.LocationFix
import com.llyytt.path.location.LocationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 记录前台服务。
 *
 * 为什么必须是前台服务：只有带 location 类型的前台服务，才能在锁屏 / 切后台后
 * 继续拿定位、继续写库。普通后台服务在 Android 8+ 会被直接掐断。
 *
 * 单协程串行写：定位回调可能密集到达，用一个 Channel + 单个消费者保证
 * seq 严格递增、lastPoint/distance 不被并发改坏。
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var repo: RecordingRepository
    private lateinit var settings: SettingsStore

    private var location: LocationSource? = null

    @Volatile
    private var trip: TripEntity? = null

    private var tripId: Long = 0L
    private var nextSeq = 0
    private var lastPoint: TrackPointEntity? = null
    private var distance = 0.0
    private var pointCount = 0
    private var pauses: List<Pair<Long, Long?>> = emptyList()

    private var pendingNewSegment = true
    private var firstFixStored = false
    private var startRequestedElapsed = 0L
    private var lastError: LocationError? = null
    private var lastNotifText: String? = null
    private var ticker: Job? = null

    /** 显示用速度：平滑后的中位数 + 静止判定 */
    private val speedSmoother = SpeedSmoother()
    private var currentSpeedMps: Double? = null
    private var isStationary = false

    private val fixes = Channel<LocationFix>(Channel.UNLIMITED)

    override fun onCreate() {
        super.onCreate()
        val app = application as XjApp
        repo = app.recordingRepository
        settings = app.settings
        location = app.locationSource()
        createChannel()
        Log.i(TAG, "service created pid=${android.os.Process.myPid()}")

        // 看门狗：如果 5 秒内没有 adopt 成功，说明启动路径卡住了，
        // 打一条明显的 ERROR，而不是静默地什么都不做。
        // （第 2 批排查过一次「服务已启动但 19 分钟零日志」，就是靠这类痕迹定位）
        scope.launch {
            delay(WATCHDOG_MS)
            if (trip == null) {
                Log.e(TAG, "WATCHDOG: ${WATCHDOG_MS}ms 内没有 adopt，启动路径可能卡住")
            }
        }

        // 串行消费者：所有入库都在这一个协程里发生
        scope.launch {
            for (fix in fixes) {
                runCatching { processFix(fix) }
                    .onFailure { Log.e(TAG, "processFix failed", it) }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val id = intent?.getLongExtra(EXTRA_TRIP_ID, 0L) ?: 0L
        Log.i(TAG, "onStartCommand action=$action tripId=$id startId=$startId")

        try {
            when (action) {
            ACTION_START -> {
                // 必须在 5 秒内进入前台，先挂个「准备中」的通知
                beginForeground("正在开启定位…")
                tripId = id
                startRequestedElapsed = SystemClock.elapsedRealtime()
                firstFixStored = false
                scope.launch { loadAndStart(id) }
            }

            ACTION_RESUME -> {
                beginForeground("正在恢复定位…")
                if (id != 0L) tripId = id
                startRequestedElapsed = SystemClock.elapsedRealtime()
                scope.launch { doResume() }
            }

            ACTION_PAUSE -> scope.launch { doPause() }

            ACTION_STOP -> scope.launch { doStop() }

            // START_STICKY 被系统重启时 intent 为 null —— 这是断点恢复入口
            null -> {
                beginForeground("正在恢复记录…")
                startRequestedElapsed = SystemClock.elapsedRealtime()
                scope.launch { recover() }
            }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onStartCommand 失败 action=$action", t)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ticker?.cancel()
        location?.stop()
        fixes.close()
        scope.cancel()
        super.onDestroy()
    }

    // ---------- 生命周期 ----------

    private suspend fun loadAndStart(id: Long) {
        try {
            val t = repo.findTrip(id)
            if (t == null) {
                Log.e(TAG, "trip $id not found, stopping")
                stopSelfSafely()
                return
            }
            adopt(t)
            pendingNewSegment = true
            startLocationIfRecording()
            startTicker()
            publish()
        } catch (th: Throwable) {
            Log.e(TAG, "loadAndStart 失败 tripId=$id", th)
            stopSelfSafely()
        }
    }

    /** 进程被杀后重建：从数据库把「未结束行程」接上 */
    private suspend fun recover() {
        val t = repo.unfinished()
        if (t == null) {
            Log.i(TAG, "recover: no unfinished trip, stop")
            stopSelfSafely()
            return
        }
        adopt(t)
        // 恢复出来的第一个点也视为新段起点，避免把中断两端连成直线
        pendingNewSegment = true
        if (t.status == TripStatus.RECORDING) {
            startLocationIfRecording()
        }
        startTicker()
        publish()
    }

    private suspend fun adopt(t: TripEntity) {
        trip = t
        tripId = t.id
        nextSeq = repo.maxSeq(t.id) + 1
        lastPoint = repo.lastPoint(t.id)
        distance = repo.distanceMeters(t.id)
        pointCount = repo.pointCount(t.id)
        pauses = repo.pauses(t.id)
        resetSpeed()
        Log.i(TAG, "adopt trip=${t.id} status=${t.status} seq=$nextSeq points=$pointCount dist=$distance")
    }

    private suspend fun doPause() {
        val id = ensureTripId() ?: return
        location?.stop()
        resetSpeed()
        repo.pause(id)
        reloadDerived()
        publish()
    }

    private suspend fun doResume() {
        val id = ensureTripId() ?: return
        repo.resume(id)
        reloadDerived()
        pendingNewSegment = true
        firstFixStored = false
        resetSpeed()
        startLocationIfRecording()
        startTicker()
        publish()
    }

    private suspend fun doStop() {
        val id = ensureTripId()
        location?.stop()
        resetSpeed()
        if (id != null) repo.finish(id)
        reloadDerived()
        publish()
        stopTicker()
        stopSelfSafely()
    }

    private fun resetSpeed() {
        speedSmoother.reset()
        currentSpeedMps = null
        isStationary = false
    }

    private suspend fun ensureTripId(): Long? {
        if (tripId == 0L) {
            val pending = repo.unfinished() ?: return null
            adopt(pending)
        }
        return tripId.takeIf { it != 0L }
    }

    private suspend fun reloadDerived() {
        val id = tripId
        if (id == 0L) return
        trip = repo.findTrip(id)
        pauses = repo.pauses(id)
        pointCount = repo.pointCount(id)
        distance = repo.distanceMeters(id)
        lastPoint = repo.lastPoint(id)
    }

    // ---------- 定位 ----------

    private fun startLocationIfRecording() {
        val t = trip ?: return
        if (t.status != TripStatus.RECORDING) return
        location?.start(
            onFix = { fix -> fixes.trySend(fix) },
            onError = { error ->
                lastError = error
                Log.w(TAG, "location error: $error")
                publish()
            },
        )
    }

    private suspend fun processFix(fix: LocationFix) {
        val t = trip ?: return
        if (t.status != TripStatus.RECORDING) return

        // 收到点就清掉之前的定位错误（比如用户去设置里把定位打开了），
        // 否则界面上会一直挂着"定位没开"的红字。
        if (lastError != null) {
            lastError = null
            publish()
        }

        // 精度太差的点直接丢，不入库（不改善，只是不污染）
        if (fix.accuracyMeters > Tuning.Filter.MAX_ACCURACY_M) return
        val speed = fix.speedMps
        if (speed != null && speed > Tuning.Filter.MAX_SPEED_MPS) return

        // 丢掉缓存点：getLastKnownLocation 可能给几小时前的位置，
        // 存进去会凭空造出一段跨城的假距离。
        val age = System.currentTimeMillis() - fix.timeMillis
        if (age > STALE_FIX_MS) {
            Log.d(TAG, "skip stale fix, age=${age}ms")
            return
        }

        val startsNew = pendingNewSegment
        val prev = lastPoint

        // 速度样本要在插入新点之前算：用的还是「上一个点 → 当前点」
        val speedSample = SpeedSampler.sample(
            previousPoint = prev?.let { Wgs84(it.lat, it.lng) },
            previousTimeMillis = prev?.recordedAt,
            currentPoint = fix.point,
            currentTimeMillis = fix.timeMillis,
            systemSpeedMps = if (startsNew) null else fix.speedMps,
            accuracyMeters = fix.accuracyMeters,
        )

        val add = if (!startsNew && prev != null) {
            Wgs84(prev.lat, prev.lng).distanceTo(fix.point)
        } else {
            0.0
        }

        val seq = nextSeq
        val entity = repo.insertPoint(t.id, seq, fix, startsNew)
        nextSeq = seq + 1
        lastPoint = entity
        pointCount += 1
        distance += add
        pendingNewSegment = false
        lastError = null

        // 平滑后的速度只用于显示；着色仍然用原始 speedMps
        if (startsNew) speedSmoother.reset()
        if (speedSample != null) {
            val snap = speedSmoother.add(speedSample, fix.timeMillis)
            currentSpeedMps = snap.smoothedMps
            isStationary = snap.stationary
        }

        Log.i(TAG, "POINT_STORED seq=$seq age=${age}ms acc=${fix.accuracyMeters}")

        if (!firstFixStored) {
            firstFixStored = true
            val elapsed = SystemClock.elapsedRealtime() - startRequestedElapsed
            settings.firstFixMillis = elapsed
            Log.i(TAG, "FIRST_FIX_STORED: ${elapsed}ms since start request")
        }
        publish()
    }

    // ---------- 状态发布 ----------

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var tick = 0L
            while (isActive) {
                publish()
                // 每 10 秒打一条心跳：用来判断进程在后台是否被冻结
                if (tick % 10L == 0L) {
                    Log.i(TAG, "heartbeat points=$pointCount dist=${distance.toInt()} status=${trip?.status}")
                }
                tick++
                delay(Tuning.Recording.TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    @Synchronized
    private fun publish() {
        val t = trip ?: return
        val now = System.currentTimeMillis()
        val active = DurationCalc.activeMillis(
            startedAt = t.startedAt,
            endedAt = t.endedAt,
            status = t.status,
            pauses = pauses,
            now = now,
        )
        RecordingStateHolder.set(
            RecordingUiState(
                tripId = t.id,
                status = t.status,
                startedAt = t.startedAt,
                distanceMeters = distance,
                activeMillis = active,
                pointCount = pointCount,
                lastAccuracyM = lastPoint?.accuracyM,
                lastError = lastError,
                hasFirstFix = firstFixStored,
                speedMps = currentSpeedMps,
                isStationary = isStationary,
            ),
        )
        updateNotification(t, active)
    }

    // ---------- 通知 ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "轨迹记录",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "记录进行中时显示状态"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun beginForeground(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("行迹 · 正在记录")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun updateNotification(t: TripEntity, activeMillis: Long) {
        val km = distance / 1000.0
        val minutes = activeMillis / 60000
        val seconds = (activeMillis / 1000) % 60
        val text = when (t.status) {
            TripStatus.RECORDING -> "%.2f km · %d分%02d秒 · %d 点".format(km, minutes, seconds, pointCount)
            TripStatus.PAUSED -> "已暂停 · %.2f km · %d分%02d秒".format(km, minutes, seconds)
            TripStatus.FINISHED -> "已结束"
        }
        // 文本没变就不重复 notify。每秒发一次会触发系统 AlarmScheduler 刷屏
        if (text == lastNotifText) return
        lastNotifText = text
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun stopSelfSafely() {
        stopTicker()
        location?.stop()
        RecordingStateHolder.reset()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    companion object {
        private const val TAG = "XjRec"

        const val ACTION_START = "com.llyytt.path.action.START"
        const val ACTION_PAUSE = "com.llyytt.path.action.PAUSE"
        const val ACTION_RESUME = "com.llyytt.path.action.RESUME"
        const val ACTION_STOP = "com.llyytt.path.action.STOP"

        const val EXTRA_TRIP_ID = "trip_id"

        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1001

        /** 超过这个「年龄」的点视为缓存点，不入库 */
        private const val STALE_FIX_MS = 10_000L

        /** 看门狗：5 秒内没 adopt 就报错 */
        private const val WATCHDOG_MS = 5_000L

        fun intent(context: Context, action: String, tripId: Long = 0L): Intent =
            Intent(context, RecordingService::class.java).apply {
                this.action = action
                putExtra(EXTRA_TRIP_ID, tripId)
            }

        fun start(context: Context, tripId: Long) {
            ContextCompat.startForegroundService(
                context,
                intent(context, ACTION_START, tripId),
            )
        }

        fun resume(context: Context, tripId: Long) {
            ContextCompat.startForegroundService(
                context,
                intent(context, ACTION_RESUME, tripId),
            )
        }

        fun pause(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_PAUSE))
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_STOP))
        }
    }
}
