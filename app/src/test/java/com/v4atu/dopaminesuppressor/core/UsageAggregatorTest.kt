package com.v4atu.dopaminesuppressor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageAggregatorTest {

    private val limited = setOf("a")

    private fun resumed(pkg: String, t: Long) = UsageEvent(UsageEventType.RESUMED, pkg, t)
    private fun paused(pkg: String, t: Long) = UsageEvent(UsageEventType.PAUSED, pkg, t)
    private fun screenOff(t: Long) = UsageEvent(UsageEventType.SCREEN_OFF, null, t)

    private fun summarize(vararg events: UsageEvent, end: Long = 1_000_000L) =
        UsageAggregator.summarize(events.asSequence(), end, limited)

    @Test
    fun `suma sesiones completas y varias aperturas`() {
        val s = summarize(
            resumed("a", 0), paused("a", 10_000),
            resumed("b", 10_000), paused("b", 20_000),
            resumed("a", 30_000), paused("a", 45_000),
        )
        assertEquals(25_000L, s.totals["a"])
        assertNull(s.foreground)
    }

    @Test
    fun `solo devuelve las apps pedidas`() {
        val s = summarize(resumed("b", 0), paused("b", 10_000))
        assertEquals(emptyMap<String, Long>(), s.totals)
    }

    @Test
    fun `varias actividades de un mismo paquete se suman sin solapar`() {
        val s = summarize(
            resumed("a", 0), paused("a", 10_000),
            resumed("a", 10_000), paused("a", 30_000),
        )
        assertEquals(30_000L, s.totals["a"])
    }

    @Test
    fun `la sesion abierta al final cuenta hasta end y es la app en primer plano`() {
        val s = summarize(resumed("a", 100_000), end = 160_000L)
        assertEquals(60_000L, s.totals["a"])
        assertEquals("a", s.foreground)
    }

    @Test
    fun `la pantalla apagada cierra las sesiones abiertas`() {
        val s = summarize(resumed("a", 0), screenOff(40_000), resumed("a", 100_000), paused("a", 110_000))
        assertEquals(50_000L, s.totals["a"])
        assertNull(s.foreground)
    }

    @Test
    fun `un PAUSED sin RESUMED previo se ignora`() {
        val s = summarize(paused("a", 5_000), resumed("a", 10_000), paused("a", 20_000))
        assertEquals(10_000L, s.totals["a"])
    }

    @Test
    fun `la ultima app abierta sin pausar es la de primer plano`() {
        val s = summarize(resumed("a", 0), paused("a", 5_000), resumed("b", 5_000))
        assertEquals("b", s.foreground)
    }

    @Test
    fun `un RESUMED repetido sin PAUSED conserva el inicio original`() {
        // Si se pierde un PAUSED, el segundo RESUMED no reinicia la sesión: se cuenta desde el primero.
        val s = summarize(resumed("a", 0), resumed("a", 50_000), paused("a", 60_000))
        assertEquals(60_000L, s.totals["a"])
    }
}
