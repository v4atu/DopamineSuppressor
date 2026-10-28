package com.v4atu.dopaminesuppressor.core

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageCounterTest {

    @Test
    fun `add acumula por paquete`() {
        val counter = UsageCounter(FakeClock())
        counter.add("a", 1_000)
        counter.add("a", 500)
        counter.add("b", 200)
        assertEquals(1_500L, counter.usedMs("a"))
        assertEquals(200L, counter.usedMs("b"))
        assertEquals(0L, counter.usedMs("c"))
    }

    @Test
    fun `seed reemplaza el conteo anterior`() {
        val counter = UsageCounter(FakeClock())
        counter.add("a", 1_000)
        counter.seed(mapOf("b" to 5_000L))
        assertEquals(0L, counter.usedMs("a"))
        assertEquals(5_000L, counter.usedMs("b"))
    }

    @Test
    fun `rollover solo reinicia cuando cambia el dia`() {
        val clock = FakeClock(local = LocalDateTime.of(2026, 10, 27, 23, 59, 0))
        val counter = UsageCounter(clock)
        counter.add("a", 1_000)

        assertFalse(counter.rolloverIfNeeded())
        assertEquals(1_000L, counter.usedMs("a"))

        clock.advance(120_000)
        assertTrue(counter.rolloverIfNeeded())
        assertEquals(0L, counter.usedMs("a"))
        assertFalse(counter.rolloverIfNeeded())
    }
}
