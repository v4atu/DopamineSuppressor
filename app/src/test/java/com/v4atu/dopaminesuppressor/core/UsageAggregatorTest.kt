package com.v4atu.dopaminesuppressor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageAggregatorTest {

    private val limited = setOf("a")

    private fun resumed(pkg: String, t: Long, cls: String? = null) = UsageEvent(UsageEventType.RESUMED, pkg, t, cls)
    private fun paused(pkg: String, t: Long, cls: String? = null) = UsageEvent(UsageEventType.PAUSED, pkg, t, cls)
    private fun stopped(pkg: String, t: Long, cls: String? = null) = UsageEvent(UsageEventType.STOPPED, pkg, t, cls)
    private fun screenOff(t: Long) = UsageEvent(UsageEventType.SCREEN_OFF, null, t)

    private fun summarize(vararg events: UsageEvent, end: Long = 1_000_000L, home: Set<String> = emptySet()) =
        UsageAggregator.summarize(events.asSequence(), end, limited, home)

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

    // Varias actividades de un mismo paquete: el paquete sigue en primer plano mientras quede alguna abierta.

    @Test
    fun `dos actividades solapadas del mismo paquete cuentan hasta que se pausa la ultima`() {
        val s = summarize(
            resumed("a", 0, "Main"),
            resumed("a", 10_000, "Modal"),
            paused("a", 20_000, "Main"),
            paused("a", 50_000, "Modal"),
        )
        assertEquals(50_000L, s.totals["a"])
        assertNull(s.foreground)
    }

    @Test
    fun `el paquete sigue en primer plano mientras quede una actividad abierta`() {
        val s = summarize(
            resumed("a", 0, "Main"),
            resumed("a", 10_000, "Modal"),
            paused("a", 20_000, "Main"),
            end = 60_000L,
        )
        assertEquals(60_000L, s.totals["a"])
        assertEquals("a", s.foreground)
    }

    @Test
    fun `un PAUSED de una actividad que no estaba abierta no cierra otra del mismo paquete`() {
        val s = summarize(
            resumed("a", 0, "Main"),
            paused("a", 10_000, "Other"),
            paused("a", 30_000, "Main"),
        )
        assertEquals(30_000L, s.totals["a"])
    }

    @Test
    fun `la misma actividad pausada y reabierta no duplica tiempo`() {
        val s = summarize(
            resumed("a", 0, "Main"), paused("a", 10_000, "Main"),
            resumed("a", 10_000, "Main"), paused("a", 30_000, "Main"),
        )
        assertEquals(30_000L, s.totals["a"])
    }

    @Test
    fun `la pantalla apagada cierra todas las actividades abiertas de un paquete`() {
        val s = summarize(
            resumed("a", 0, "Main"),
            resumed("a", 10_000, "Modal"),
            screenOff(40_000),
            resumed("a", 100_000, "Main"),
            paused("a", 110_000, "Main"),
        )
        assertEquals(50_000L, s.totals["a"])
        assertNull(s.foreground)
    }

    // Android a veces emite RESUMED y STOPPED sin PAUSED (LinkedIn, 2026-10-31 21:06): STOPPED cierra la actividad.

    @Test
    fun `un STOPPED sin PAUSED previo cierra la sesion`() {
        // Sin esto la sesión seguía abierta hasta apagar la pantalla (aquí, ~33 min fantasma).
        val s = summarize(
            resumed("a", 0, "Main"),
            stopped("a", 1_000, "Main"),
            screenOff(2_000_000),
            end = 3_000_000L,
        )
        assertEquals(1_000L, s.totals["a"])
        assertNull(s.foreground)
    }

    @Test
    fun `un STOPPED despues del PAUSED no cambia nada`() {
        val s = summarize(
            resumed("a", 0, "Main"), paused("a", 10_000, "Main"), stopped("a", 10_500, "Main"),
            resumed("a", 20_000, "Main"), paused("a", 30_000, "Main"),
        )
        assertEquals(20_000L, s.totals["a"])
    }

    @Test
    fun `un STOPPED de una actividad no abierta no cierra otra del mismo paquete`() {
        val s = summarize(
            resumed("a", 0, "Main"),
            stopped("a", 10_000, "Other"),
            paused("a", 30_000, "Main"),
        )
        assertEquals(30_000L, s.totals["a"])
    }

    @Test
    fun `un STOPPED cierra solo la actividad indicada`() {
        val s = summarize(
            resumed("a", 0, "Main"),
            resumed("a", 10_000, "Modal"),
            stopped("a", 20_000, "Main"),
            paused("a", 50_000, "Modal"),
        )
        assertEquals(50_000L, s.totals["a"])
    }

    @Test
    fun `un STOPPED de una app suspendida por el launcher evita que se reabra al irse el launcher`() {
        // Escenario del 21:06: LinkedIn queda detrás del launcher, se detiene sin PAUSED y luego se abre otra app.
        val s = summarize(
            resumed("a", 0, "Main"),
            resumed("home", 10_000),
            stopped("a", 11_000, "Main"),
            paused("home", 50_000),
            end = 100_000L,
            home = setOf("home"),
        )
        assertEquals(10_000L, s.totals["a"])
        assertNull(s.foreground)
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

    // Recientes con «live tile» (Quickstep): la app queda RESUMED junto al launcher, sin PAUSED.

    @Test
    fun `recientes con tarjeta en vivo no suma tiempo`() {
        // Secuencia capturada en el Pixel: Instagram y el launcher RESUMED a la vez, 92 s en Recientes.
        val s = summarize(
            resumed("a", 0),
            resumed("home", 10_000),
            paused("a", 102_000),
            paused("home", 105_000),
            home = setOf("home"),
        )
        assertEquals(10_000L, s.totals["a"])
    }

    @Test
    fun `el launcher en primer plano cierra la sesion abierta de otra app`() {
        val s = summarize(resumed("a", 0), resumed("home", 10_000), end = 100_000L, home = setOf("home"))
        assertEquals(10_000L, s.totals["a"])
        assertEquals("home", s.foreground)
    }

    @Test
    fun `tocar la tarjeta en vivo reabre la sesion al salir el launcher`() {
        // Al tocar la tarjeta no llega un RESUMED nuevo de la app: sigue sin PAUSED.
        val s = summarize(
            resumed("a", 0),
            resumed("home", 10_000),
            paused("home", 40_000),
            end = 100_000L,
            home = setOf("home"),
        )
        assertEquals(10_000L + 60_000L, s.totals["a"])
        assertEquals("a", s.foreground)
    }

    @Test
    fun `si la app se pausa mientras el launcher esta delante no se reabre`() {
        val s = summarize(
            resumed("a", 0),
            resumed("home", 10_000),
            paused("a", 50_000),
            paused("home", 60_000),
            end = 100_000L,
            home = setOf("home"),
        )
        assertEquals(10_000L, s.totals["a"])
        assertNull(s.foreground)
    }

    @Test
    fun `la pantalla apagada descarta las sesiones cerradas por el launcher`() {
        val s = summarize(
            resumed("a", 0),
            resumed("home", 10_000),
            screenOff(50_000),
            resumed("home", 100_000),
            paused("home", 110_000),
            end = 200_000L,
            home = setOf("home"),
        )
        assertEquals(10_000L, s.totals["a"])
        assertNull(s.foreground)
    }

    // Madrugada del 04-nov: parpadeo del launcher (RESUMED/PAUSED/STOPPED en el mismo segundo) sobre Instagram abierta.
    private val night = arrayOf(
        resumed("a", 0),
        resumed("home", 21 * 60_000L, "Quickstep"),
        paused("home", 21 * 60_000L, "Quickstep"),
        stopped("home", 21 * 60_000L, "Quickstep"),
    )

    @Test
    fun `un parpadeo del launcher no quita la app de primer plano si se lee desde su RESUMED`() {
        val s = summarize(*night, end = 60 * 60_000L, home = setOf("home"))
        assertEquals("a", s.foreground)
        assertEquals(60 * 60_000L, s.totals["a"])
    }

    @Test
    fun `con una ventana corta que no incluye el RESUMED la app se pierde`() {
        val s = summarize(*night.drop(1).toTypedArray(), end = 60 * 60_000L, home = setOf("home"))
        assertNull(s.foreground)
    }

    @Test
    fun `sin paquetes de launcher el comportamiento no cambia`() {
        val s = summarize(resumed("a", 0), resumed("home", 10_000), paused("a", 50_000))
        assertEquals(50_000L, s.totals["a"])
    }
}
