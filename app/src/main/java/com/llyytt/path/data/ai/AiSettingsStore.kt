package com.llyytt.path.data.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.aiDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ai_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** AI 配置。apiKey 是解密后的明文，只活在内存里。 */
data class AiConfig(
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
    val disclosureAccepted: Boolean = false,
) {
    val isReady: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
}

/**
 * AI 设置的持久化。
 *
 * 存哪：DataStore（不是 SharedPreferences）。
 * Key 怎么存：只存 [KeyCrypto] 加密后的 Base64 密文，读出来才解密。
 * 也就是说，磁盘上、备份里、`adb backup` 出来的都只有密文，
 * 而解密密钥在 Android Keystore 里，拷到别的设备上解不开。
 */
class AiSettingsStore(private val context: Context) {

    val config: Flow<AiConfig> = context.aiDataStore.data.map { prefs ->
        val blob = prefs[KEY_KEY].orEmpty()
        AiConfig(
            baseUrl = prefs[KEY_BASE_URL].orEmpty(),
            model = prefs[KEY_MODEL].orEmpty(),
            apiKey = if (blob.isEmpty()) "" else KeyCrypto.decrypt(blob).orEmpty(),
            disclosureAccepted = prefs[KEY_DISCLOSURE] ?: false,
        )
    }

    /** 保存。apiKey 传空字符串表示"不修改已有 Key"。 */
    suspend fun save(baseUrl: String, model: String, apiKey: String) {
        context.aiDataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = baseUrl
            prefs[KEY_MODEL] = model
            if (apiKey.isNotBlank()) {
                prefs[KEY_KEY] = KeyCrypto.encrypt(apiKey)
            }
        }
    }

    suspend fun acceptDisclosure() {
        context.aiDataStore.edit { it[KEY_DISCLOSURE] = true }
    }

    suspend fun clear() {
        context.aiDataStore.edit { it.clear() }
        KeyCrypto.deleteKey()
    }

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_MODEL = stringPreferencesKey("model")
        val KEY_KEY = stringPreferencesKey("key_blob")
        val KEY_DISCLOSURE = booleanPreferencesKey("disclosure_accepted")
    }
}
