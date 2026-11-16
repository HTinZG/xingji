package com.llyytt.path.core.format

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 展示层格式化。集中一处，避免每个页面各写一份导致口径不一致。 */
object Fmt {

    fun distance(meters: Double): String =
        if (meters < 1000) "%.0f 米".format(meters) else "%.2f 公里".format(meters / 1000.0)

    fun duration(millis: Long): String {
        val totalSeconds = millis / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun speed(mps: Double): String = "%.1f km/h".format(mps * 3.6)

    fun clock(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(millis))

    fun dateTime(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(millis))
}
