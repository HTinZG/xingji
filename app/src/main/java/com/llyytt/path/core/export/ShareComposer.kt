package com.llyytt.path.core.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.FileProvider
import java.io.File

/**
 * 分享图合成。
 *
 * 刻意的设计：**不截屏**。截屏会把状态栏、导航栏、按钮一起带进去，
 * 而且很容易把别的界面（设置页、聊天页）拍进去。
 *
 * 这里只拿地图控件的 `snapshot()`，然后自己画一条统计带拼在下面，
 * 所以分享图里必然只有：地图 + 轨迹 + 统计数字。
 */
object ShareComposer {

    private const val BAND_RATIO = 0.24f
    private const val MIN_BAND_PX = 240f

    fun compose(
        map: Bitmap,
        title: String,
        subtitle: String,
        stats: List<Pair<String, String>>,
    ): Bitmap {
        val width = map.width
        val bandHeight = maxOf(map.height * BAND_RATIO, MIN_BAND_PX * (width / 1080f)).toInt()

        val output = Bitmap.createBitmap(width, map.height + bandHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(map, 0f, 0f, null)

        val top = map.height.toFloat()
        canvas.drawRect(0f, top, width.toFloat(), output.height.toFloat(), Paint().apply {
            color = Color.WHITE
        })

        val margin = width * 0.05f
        var y = top + bandHeight * 0.26f

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1A1A")
            textSize = width * 0.048f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(title, margin, y, titlePaint)

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#757575")
            textSize = width * 0.030f
        }
        y += bandHeight * 0.20f
        canvas.drawText(subtitle, margin, y, subPaint)

        // 统计：一行四项，等宽排开
        if (stats.isNotEmpty()) {
            y += bandHeight * 0.26f
            val slot = (width - margin * 2) / stats.size
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#9E9E9E")
                textSize = width * 0.026f
            }
            val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1A1A1A")
                textSize = width * 0.042f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            stats.forEachIndexed { index, (label, value) ->
                val x = margin + slot * index
                canvas.drawText(label, x, y, labelPaint)
                canvas.drawText(value, x, y + bandHeight * 0.16f, valuePaint)
            }
        }

        return output
    }

    fun writeToCache(context: Context, bitmap: Bitmap, fileName: String): android.net.Uri {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, fileName)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun share(context: Context, uri: android.net.Uri, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享行程").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
