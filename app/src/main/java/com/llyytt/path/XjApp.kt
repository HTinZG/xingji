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
