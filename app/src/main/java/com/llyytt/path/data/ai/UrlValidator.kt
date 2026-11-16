package com.llyytt.path.data.ai

/**
 * BaseURL 校验。
 *
 * 规则是刻意的：
 * - 空、非法、缺 scheme 一律拒绝（不帮用户补 http://，免得猜错）
 * - https 直接过
 * - http 不直接过，要 UI 弹出"明文传输"警告，用户显式确认后才放行
 */
object UrlValidator {

    sealed interface Outcome {
        data class Https(val normalized: String) : Outcome
        data class Http(val normalized: String) : Outcome
        data class Invalid(val reason: String) : Outcome
    }

    fun validate(raw: String): Outcome {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Outcome.Invalid("请填写 BaseURL")

        val lower = trimmed.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return Outcome.Invalid("必须以 http:// 或 https:// 开头")
        }

        val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
            ?: return Outcome.Invalid("URL 格式不正确")
        if (uri.host.isNullOrBlank()) return Outcome.Invalid("缺少主机名")
        if (uri.rawQuery != null) return Outcome.Invalid("BaseURL 不要带查询参数")

        val normalized = trimmed.trimEnd('/')
        val isHttps = lower.startsWith("https://")
        // scheme 统一小写，路径部分原样保留（大小写敏感的路径不能被改）
        val rest = normalized.substringAfter("://")
        val canonical = (if (isHttps) "https://" else "http://") + rest
        return if (isHttps) Outcome.Https(canonical) else Outcome.Http(canonical)
    }
}
