package com.v4atu.dopaminesuppressor.core

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundTrackerTest {

    private val minute = 60_000L
    private val clock = FakeClock()
    private val counter = UsageCounter(clock)
    private val limits = mutableMapOf("a" to 5 * minute)
    private val ignored = setOf("own", "systemui", "ime")
    private var rollovers = 0

    private fun tracker() = ForegroundTracker(
        clock = clock,
        counter = counter,
        limits = { limits },
        isIgnored = { it in ignored },
        onDayRollover = { rollovers++ },
    )

    @Test
    fun `suma la sesion al cambiar de app`() {
        val t = tracker()
        t.onWindowChanged("a")
        clock.advance(minute)
        t.onWindowChanged("b")
        assertEquals(minute, counter.usedMs("a"))
    }

    @Test
    fun `no cuenta apps sin limite`() {
        val t = tracker()
        assertEquals(Action.Idle, t.onWindowChanged("b"))
        clock.advance(minute)
        t.onWindowChanged("a")
        assertEquals(0L, counter.usedMs("b"))
    }

    @Test
    fun `programa el bloqueo con el tiempo restante`() {
        val t = tracker()
        assertEquals(Action.Schedule("a", 5 * minute), t.onWindowChanged("a"))
    }

    @Test
    fun `eventos repetidos del mismo paquete se ignoran`() {
        val t = tracker()
        t.onWindowChanged("a")
        clock.advance(10_000)
        assertEquals(Action.Ignored, t.onWindowChanged("a"))
        clock.advance(10_000)
        t.onWindowChanged("b")
        assertEquals(20_000L, counter.usedMs("a"))
    }

    @Test
    fun `teclado y systemui no cortan la sesion`() {
        val t = tracker()
        t.onWindowChanged("a")
        clock.advance(10_000)
        assertEquals(Action.Ignored, t.onWindowChanged("ime"))
        assertEquals(Action.Ignored, t.onWindowChanged("systemui"))
        assertEquals(Action.Ignored, t.onWindowChanged("own"))
        clock.advance(10_000)
        t.onWindowChanged("b")
        assertEquals(20_000L, counter.usedMs("a"))
    }

    @Test
    fun `bloquea al abrir una app con el limite agotado`() {
        counter.seed(mapOf("a" to 5 * minute))
        val t = tracker()
        assertEquals(Action.Block("a"), t.onWindowChanged("a"))
    }

    @Test
    fun `la siembra descuenta lo ya usado`() {
        counter.seed(mapOf("a" to 4 * minute))
        val t = tracker()
        assertEquals(Action.Schedule("a", minute), t.onWindowChanged("a"))
    }

    @Test
    fun `temporizador vencido bloquea`() {
        val t = tracker()
        t.onWindowChanged("a")
        clock.advance(5 * minute)
        assertEquals(Action.Block("a"), t.refresh())
    }

    @Test
    fun `reabrir una app bloqueada vuelve a bloquearla sin evento del launcher`() {
        val t = tracker()
        t.onWindowChanged("a")
        clock.advance(5 * minute)
        assertEquals(Action.Block("a"), t.refresh())

        // El launcher no emitió evento: la misma app vuelve a abrirse.
        clock.advance(2_000)
        assertEquals(Action.Block("a"), t.onWindowChanged("a"))
        assertEquals(5 * minute, counter.usedMs("a"))
    }

    @Test
    fun `el evento repetido justo despues de bloquear no bloquea dos veces`() {
        val t = tracker()
        t.onWindowChanged("a")
        clock.advance(5 * minute)
        assertEquals(Action.Block("a"), t.refresh())

        clock.advance(900)
        assertEquals(Action.Ignored, t.onWindowChanged("a"))
        clock.advance(1_000)
        assertEquals(Action.Block("a"), t.onWindowChanged("a"))
    }

    @Test
    fun `abrir otra app tras un bloqueo reinicia la ventana anti-repeticion`() {
        val t = tracker()
        counter.seed(mapOf("a" to 5 * minute))
        assertEquals(Action.Block("a"), t.onWindowChanged("a"))
        t.onWindowChanged("b")
        clock.advance(100)
        assertEquals(Action.Block("a"), t.onWindowChanged("a"))
    }

    @Test
    fun `tras sembrar no se vuelve a sumar la sesion abierta`() {
        val t = tracker()
        t.onWindowChanged("a")
        clock.advance(2 * minute)

        // UsageStats ya cuenta los 2 min de la sesión abierta.
        counter.seed(mapOf("a" to 2 * minute))
        assertEquals(Action.Schedule("a", 3 * minute), t.onSeeded("a"))

        clock.advance(minute)
        t.onWindowChanged("b")
        assertEquals(3 * minute, counter.usedMs("a"))
    }

    @Test
    fun `sembrar sin app actual adopta la app en primer plano`() {
        val t = tracker()
        counter.seed(mapOf("a" to 4 * minute))
        assertEquals(Action.Schedule("a", minute), t.onSeeded("a"))
    }

    @Test
    fun `sembrar sin app en primer plano o con una ignorada no hace nada`() {
        val t = tracker()
        assertEquals(Action.Idle, t.onSeeded(null))
        assertEquals(Action.Idle, t.onSeeded("ime"))
    }

    @Test
    fun `sembrar con el limite agotado bloquea`() {
        val t = tracker()
        counter.seed(mapOf("a" to 6 * minute))
        assertEquals(Action.Block("a"), t.onSeeded("a"))
    }

    @Test
    fun `el contador desfasado se corrige al sembrar y bloquea con la UI`() {
        val t = tracker()
        // El servicio solo había contado 2 min, pero UsageStats (lo que ve la UI) dice 6 de 5.
        counter.seed(mapOf("a" to 2 * minute))
        assertEquals(Action.Schedule("a", 3 * minute), t.onWindowChanged("a"))

        counter.seed(mapOf("a" to 6 * minute))
        assertEquals(Action.Block("a"), t.onSeeded("a"))
    }

    @Test
    fun `usedSnapshot incluye la sesion abierta de la app limitada`() {
        val t = tracker()
        counter.seed(mapOf("a" to minute))
        t.onWindowChanged("a")
        clock.advance(30_000)
        assertEquals(mapOf("a" to minute + 30_000), t.usedSnapshot())

        t.onWindowChanged("b")
        assertEquals(mapOf("a" to minute + 30_000), t.usedSnapshot())
    }

    @Test
    fun `pantalla apagada no cuenta`() {
        val t = tracker()
        t.onWindowChanged("a")
        clock.advance(10_000)
        assertEquals(Action.Idle, t.onScreenOff())
        clock.advance(60 * minute)
        assertEquals(10_000L, counter.usedMs("a"))
        assertEquals(Action.Idle, t.refresh())
    }

    @Test
    fun `al desbloquear retoma la app resuelta`() {
        val t = tracker()
        t.onWindowChanged("a")
        clock.advance(minute)
        t.onScreenOff()
        clock.advance(10 * minute)

        assertEquals(Action.Schedule("a", 4 * minute), t.onUserPresent("a"))
        clock.advance(minute)
        t.onWindowChanged("b")
        assertEquals(2 * minute, counter.usedMs("a"))
    }

    @Test
    fun `al desbloquear sin app conocida o ignorada no hace nada`() {
        val t = tracker()
        assertEquals(Action.Idle, t.onUserPresent(null))
        assertEquals(Action.Idle, t.onUserPresent("systemui"))
    }

    @Test
    fun `editar el limite reprograma la app en primer plano`() {
        val t = tracker()
        t.onWindowChanged("a")
        clock.advance(3 * minute)
        limits["a"] = 2 * minute
        assertEquals(Action.Block("a"), t.refresh())

        // Tras un bloqueo la app ya no es la actual: con el límite subido, al reabrirla se reprograma.
        limits["a"] = 10 * minute
        assertEquals(Action.Idle, t.refresh())
        clock.advance(2_000)
        assertEquals(Action.Schedule("a", 7 * minute), t.onWindowChanged("a"))
    }

    @Test
    fun `subir el limite sin bloqueo previo reprograma la app en primer plano`() {
        val t = tracker()
        t.onWindowChanged("a")
        clock.advance(3 * minute)
        limits["a"] = 10 * minute
        assertEquals(Action.Schedule("a", 7 * minute), t.refresh())
    }

    @Test
    fun `el temporizador no pasa de medianoche`() {
        val c = FakeClock(local = LocalDateTime.of(2026, 10, 27, 23, 59, 30))
        val t = ForegroundTracker(c, UsageCounter(c), { limits }, { it in ignored })
        assertEquals(Action.Schedule("a", 30_000L), t.onWindowChanged("a"))
    }

    @Test
    fun `al cruzar medianoche reinicia, avisa y cuenta solo lo de hoy`() {
        val c = FakeClock(local = LocalDateTime.of(2026, 10, 27, 23, 59, 30))
        val counter = UsageCounter(c)
        var rolled = 0
        val t = ForegroundTracker(c, counter, { limits }, { it in ignored }, { rolled++ })

        t.onWindowChanged("a")
        c.advance(90_000) // 00:01:00 del día siguiente
        t.onWindowChanged("b")

        assertEquals(1, rolled)
        assertEquals(60_000L, counter.usedMs("a"))
    }

    @Test
    fun `refresh tras medianoche vuelve a dar el limite completo`() {
        val c = FakeClock(local = LocalDateTime.of(2026, 10, 27, 23, 59, 30))
        val counter = UsageCounter(c)
        val t = ForegroundTracker(c, counter, { limits }, { it in ignored })

        t.onWindowChanged("a")
        c.advance(30_000) // 00:00:00 exacto
        assertEquals(Action.Schedule("a", 5 * minute), t.refresh())
    }
}
