package com.v4atu.dopaminesuppressor.ui

object DurationFormat {
    fun minutes(total: Int): String {
        val h = total / 60
        val m = total % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h h"
            else -> "$h h $m min"
        }
    }

    fun millis(ms: Long): String = minutes((ms / 60_000L).toInt())
}
