package com.v4atu.dopaminesuppressor.core

import java.time.LocalDateTime

class FakeClock(
    private var elapsed: Long = 0L,
    private var local: LocalDateTime = LocalDateTime.of(2026, 10, 27, 12, 0, 0),
) : Clock {
    override fun elapsedMillis(): Long = elapsed

    override fun now(): LocalDateTime = local

    fun advance(ms: Long) {
        elapsed += ms
        local = local.plusNanos(ms * 1_000_000L)
    }
}
