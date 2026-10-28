package com.v4atu.dopaminesuppressor.guard

import android.os.SystemClock
import com.v4atu.dopaminesuppressor.core.Clock
import java.time.LocalDateTime

object AndroidClock : Clock {
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()

    override fun now(): LocalDateTime = LocalDateTime.now()
}
