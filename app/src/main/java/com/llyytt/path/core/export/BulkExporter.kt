package com.llyytt.path.core.export

import android.content.Context
import android.net.Uri
import com.llyytt.path.data.repo.TripRepository
import com.llyytt.path.data.repo.TripDetail
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把**所有行程**一次性导出成一个 zip，里面每条行程一个 GPX 文件。
 *
 * 为什么打包而不是导出成一个大文件：
 * - 一个大 GPX 里塞多条轨迹，轨迹之间会连成一条假线（GPX 没有"分段断开"的语义）；
 * - 单条一个文件，可以直接拖进 Google Earth / 其他轨迹工具；
 * - 备份时一个包也好保存。
 *
 * 安全性：每条都用 [TrackExporter.scanForSensitiveContent] 扫一遍，
 * 一旦发现可疑内容（比如误把配置写进去）就整包中止，不生成半成品。
 */
object BulkExporter {

    /** 导出进度回调：已处理 / 总数 */
    suspend fun exportAll(
        context: Context,
        repo: TripRepository,
        target: Uri,
        format: TrackExporter.Format = TrackExporter.Format.GPX,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        val trips = repo.listAllForExport()
        if (trips.isEmpty()) return Result(0, null)

        val now = System.currentTimeMillis()
        val stream = context.contentResolver.openOutputStream(target)
            ?: return Result(0, IllegalStateException("无法写入所选位置"))
        var count = 0
        BufferedOutputStream(stream).use { out ->
            ZipOutputStream(out).use { zip ->
                trips.forEachIndexed { index, detail ->
                    val content = TrackExporter.build(
                        detail.toExportModel(title = titleOf(detail)),
                        format,
                    )
                    val findings = TrackExporter.scanForSensitiveContent(content)
                    if (findings.isNotEmpty()) {
                        throw IllegalStateException(
                            "第 ${index + 1} 条行程里有可疑内容（${findings.joinToString()}），已中止",
                        )
                    }
                    zip.putNextEntry(ZipEntry(fileName(detail, index, format)))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    count++
                    onProgress(count, trips.size)
                }
            }
        }
        return Result(count, null)
    }

    data class Result(val count: Int, val error: Throwable?)

    private fun titleOf(d: TripDetail): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(d.trip.startedAt))

    private fun fileName(d: TripDetail, index: Int, format: TrackExporter.Format): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(Date(d.trip.startedAt))
        return "%03d_%s.%s".format(index + 1, stamp, format.extension)
    }
}
