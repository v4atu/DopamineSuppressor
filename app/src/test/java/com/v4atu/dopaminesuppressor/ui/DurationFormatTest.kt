package com.v4atu.dopaminesuppressor.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatTest {

    @Test
    fun `formatea minutos, horas y mezcla`() {
        assertEquals("45 min", DurationFormat.minutes(45))
        assertEquals("2 h", DurationFormat.minutes(120))
        assertEquals("1 h 30 min", DurationFormat.minutes(90))
        assertEquals("0 min", DurationFormat.minutes(0))
    }

    @Test
    fun `millis trunca a minutos completos`() {
        assertEquals("1 min", DurationFormat.millis(119_999))
        assertEquals("0 min", DurationFormat.millis(59_999))
    }
}
