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
