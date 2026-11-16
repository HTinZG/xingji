package com.llyytt.path.core.export

import com.llyytt.path.data.db.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackExporterTest {

    private fun sample(): TripDetailForExport = TripDetailForExport(
        title = "测试行程",
        startedAt = 1_700_000_000_000L,
        exportedAt = 1_700_000_100_000L,
        points = listOf(
            ExportPoint(0, 39.900000, 116.400000, 1_700_000_000_000L),
            ExportPoint(1, 39.900100, 116.400100, 1_700_000_002_000L),
            ExportPoint(2, 39.900200, 116.400200, 1_700_000_004_000L),
            ExportPoint(3, 39.900300, 116.400300, 1_700_000_006_000L),
        ),
        segments = listOf(
            ExportSegment(0, 1, TransportMode.WALK),
            ExportSegment(2, 3, TransportMode.BIKE),
        ),
        stays = listOf(ExportStay(39.900050, 116.400050, 1_700_000_001_000L, 1_700_000_002_000L)),
    )

    @Test
    fun `gpx 是 1_1 且声明了命名空间`() {
        val gpx = TrackExporter.toGpx(sample())
        assertTrue(gpx.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(gpx.contains("version=\"1.1\""))
        assertTrue(gpx.contains("http://www.topografix.com/GPX/1/1"))
    }

    @Test
    fun `gpx 每段一个 trk 且方式写进 type`() {
        val gpx = TrackExporter.toGpx(sample())
        assertEquals(2, Regex("<trk>").findAll(gpx).count())
        assertTrue(gpx.contains("<type>walking</type>"))
        assertTrue(gpx.contains("<type>cycling</type>"))
    }

    @Test
    fun `gpx 坐标是 WGS84 原值 6 位小数`() {
        val gpx = TrackExporter.toGpx(sample())
        assertTrue(gpx.contains("lat=\"39.900000\" lon=\"116.400000\""))
        assertTrue(gpx.contains("lat=\"39.900300\" lon=\"116.400300\""))
    }

    @Test
    fun `gpx 时间是 iso8601 utc`() {
        val gpx = TrackExporter.toGpx(sample())
        assertTrue(gpx.contains("<time>2023-11-14T22:13:20Z</time>"))
    }

    @Test
    fun `gpx 停留点包含时长`() {
        val gpx = TrackExporter.toGpx(sample())
        assertTrue(gpx.contains("<wpt lat=\"39.900050\" lon=\"116.400050\">"))
        assertTrue(gpx.contains("停留 1 秒"))
    }

    @Test
    fun `kml 是 2_2 且坐标顺序是经度在前`() {
        val kml = TrackExporter.toKml(sample())
        assertTrue(kml.contains("<kml xmlns=\"http://www.opengis.net/kml/2.2\">"))
        // 经度 116.4 必须在纬度 39.9 前面
        assertTrue(kml.contains("116.400000,39.900000,0"))
        assertFalse(kml.contains("39.900000,116.400000,0"))
    }

    @Test
    fun `kml 每段一个 LineString 并带样式`() {
        val kml = TrackExporter.toKml(sample())
        assertEquals(2, Regex("<LineString>").findAll(kml).count())
        assertTrue(kml.contains("id=\"seg_1_walk\""))
        assertTrue(kml.contains("id=\"seg_2_bike\""))
        assertTrue(kml.contains("styleUrl>#seg_1_walk<"))
        assertTrue(kml.contains("styleUrl>#seg_2_bike<"))
    }

    @Test
    fun `kml 样式 id 唯一 即使两段同方式`() {
        val sameMode = sample().copy(
            segments = listOf(
                ExportSegment(0, 1, TransportMode.BIKE),
                ExportSegment(2, 3, TransportMode.BIKE),
            ),
        )
        val kml = TrackExporter.toKml(sameMode)
        val ids = Regex("id=\"(seg_[^\"]+)\"").findAll(kml).map { it.groupValues[1] }.toList()
        assertEquals("两段同方式也必须有两个不同 id", 2, ids.size)
        assertEquals(ids.toSet().size, ids.size)
        assertEquals(listOf("seg_1_bike", "seg_2_bike"), ids)
    }

    @Test
    fun `xml 特殊字符被转义`() {
        val detail = sample().copy(title = "a<b>c&d\"e")
        assertTrue(TrackExporter.toGpx(detail).contains("a&lt;b&gt;c&amp;d&quot;e"))
        assertTrue(TrackExporter.toKml(detail).contains("a&lt;b&gt;c&amp;d&quot;e"))
    }

    @Test
    fun `导出内容里没有密钥类字样`() {
        val findings = TrackExporter.scanForSensitiveContent(
            TrackExporter.toGpx(sample()) + TrackExporter.toKml(sample()),
        )
        assertTrue("不应命中：$findings", findings.isEmpty())
    }

    @Test
    fun `扫描能抓到 sk 前缀和 Authorization`() {
        assertTrue(TrackExporter.scanForSensitiveContent("key=sk-abc123").isNotEmpty())
        assertTrue(TrackExporter.scanForSensitiveContent("Authorization: Bearer x").isNotEmpty())
    }

    @Test
    fun `坐标格式化不受地区影响`() {
        assertEquals("39.900000", TrackExporter.coord(39.9))
        assertEquals("-0.000001", TrackExporter.coord(-0.000001))
    }

    @Test
    fun `iso8601 去掉毫秒并带 Z`() {
        assertEquals("2023-11-14T22:13:20Z", TrackExporter.iso8601(1_700_000_000_123L))
    }
}
