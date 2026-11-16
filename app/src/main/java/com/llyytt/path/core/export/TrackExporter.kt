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
