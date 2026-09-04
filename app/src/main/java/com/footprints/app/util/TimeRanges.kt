package com.footprints.app.util

import com.footprints.app.R
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** 底部页签对应的时间范围 [from, to] */
object TimeRanges {

    private val DAY_MS = TimeUnit.DAYS.toMillis(1)

    fun todayStart(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun forTab(tabId: Int): Pair<Long, Long> {
        val todayStart = todayStart()
        return when (tabId) {
            R.id.tab_life -> 0L to Long.MAX_VALUE
            R.id.tab_today -> todayStart to Long.MAX_VALUE
            R.id.tab_yesterday -> (todayStart - DAY_MS) to todayStart
            R.id.tab_week -> (todayStart - 6 * DAY_MS) to Long.MAX_VALUE
            R.id.tab_month -> {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                cal.timeInMillis to Long.MAX_VALUE
            }
            else -> todayStart to Long.MAX_VALUE
        }
    }
}
