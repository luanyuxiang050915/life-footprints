package com.footprints.app.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Format {

    /** 米 → 千米字符串（两位小数） */
    fun km(meters: Double): String = String.format(Locale.CHINA, "%.2f", meters / 1000.0)

    /** 轨迹点时间：「M月d日 HH:mm」，跨年自动带年份 */
    fun pointTime(ts: Long): String {
        val cal = Calendar.getInstance()
        val nowYear = cal.get(Calendar.YEAR)
        cal.timeInMillis = ts
        val pattern =
            if (cal.get(Calendar.YEAR) == nowYear) "M月d日 HH:mm" else "yyyy年M月d日 HH:mm"
        return SimpleDateFormat(pattern, Locale.CHINA).format(Date(ts))
    }
}
