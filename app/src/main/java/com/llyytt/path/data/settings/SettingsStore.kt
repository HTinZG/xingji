package com.llyytt.path.data.settings

import android.content.Context

/**
 * 轻量设置存储。
 *
 * 用什么存：SharedPreferences。本步只需要「隐私是否已同意」一个布尔，
 * 没必要为它引入 DataStore 一整套协程管线。
 * 注意：AI Key **不在这里**，它走 Android Keystore 加密（第 7 步）。
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("xingji_settings", Context.MODE_PRIVATE)

    /** 隐私说明页是否已被用户明确同意。默认 false —— 没同意就不许创建地图。 */
    var privacyAccepted: Boolean
        get() = prefs.getBoolean(KEY_PRIVACY_ACCEPTED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PRIVACY_ACCEPTED, value).apply()
        }

    /** 首次定位耗时实测结果，第 3 步第二批会写入，方便以后回归对比。 */
    var firstFixMillis: Long
        get() = prefs.getLong(KEY_FIRST_FIX_MS, -1L)
        set(value) {
            prefs.edit().putLong(KEY_FIRST_FIX_MS, value).apply()
        }

    /**
     * 是否显示瞬时速度。
     *
     * 默认关闭：室外精度还没验证过（见 step9-test-checklist）。
     * 打开后记录页显示实时速度、详情页回放显示该时刻速度，
     * 用的都是 SpeedDisplay.kt 里那套平滑逻辑。
     */
    var showInstantSpeed: Boolean
        get() = prefs.getBoolean(KEY_SHOW_INSTANT_SPEED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_INSTANT_SPEED, value).apply()
        }

    private companion object {
        const val KEY_PRIVACY_ACCEPTED = "privacy_accepted"
        const val KEY_FIRST_FIX_MS = "first_fix_millis"
        const val KEY_SHOW_INSTANT_SPEED = "show_instant_speed"
    }
}
