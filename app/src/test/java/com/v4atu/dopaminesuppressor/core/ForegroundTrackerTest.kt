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

        // Dentro de la ventana no se bloquea otra vez, pero tampoco se descarta el evento:
        // puede ser una reapertura real (Pixel, 12:14:59), así que se vuelve a comprobar al terminar.
        clock.advance(900)
        assertEquals(Action.Schedule("a", 600), t.onWindowChanged("a"))
        clock.advance(600)
        assertEquals(Action.Block("a"), t.refresh())
    }

    @Test
    fun `el eco tras bloquear no vuelve a bloquear si la app ya no esta delante`() {
        val t = tracker()
        t.onWindowChanged("a")
        clock.advance(5 * minute)
        assertEquals(Action.Block("a"), t.refresh())

        clock.advance(900)
        assertEquals(Action.Schedule("a", 600), t.onWindowChanged("a"))
        // El launcher pasa al frente antes de que venza la re-comprobación: se pide confirmar el primer
        // plano (no hay bloqueo) y la re-comprobación pendiente queda cancelada.
        clock.advance(100)
        assertEquals(Action.Verify(1_000L), t.onWindowChanged("home"))
        clock.advance(600)
        assertEquals(Action.Idle, t.refresh())
    }

    @Test
    fun `la reapertura dentro de la ventana no deja a la app sin bloquear ni sin contar`() {
        val t = tracker()
        counter.seed(mapOf("a" to 5 * minute))
        assertEquals(Action.Block("a"), t.onWindowChanged("a"))

        clock.advance(1_000)
        assertEquals(Action.Schedule("a", 500), t.onWindowChanged("a"))
        clock.advance(500)
        assertEquals(Action.Block("a"), t.refresh())
        // Cuenta desde la reapertura (0,5 s), igual que haría UsageStats desde su RESUMED.
        assertEquals(5 * minute + 500L, counter.usedMs("a"))
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

    // Al encender la pantalla directo a una app, el evento de ventana llega antes que el RESUMED de
    // UsageStats: un desbloqueo sin app resuelta no debe cancelar el temporizador ya programado.

    @Test
    fun `al desbloquear sin app resuelta conserva el temporizador de la app en seguimiento`() {
        val t = tracker()
        assertEquals(Action.Schedule("a", 5 * minute), t.onWindowChanged("a"))
        clock.advance(1_000)
        assertEquals(Action.Ignored, t.onUserPresent(null))
        clock.advance(5 * minute)
        assertEquals(Action.Block("a"), t.refresh())
    }

    @Test
    fun `al desbloquear con un paquete ignorado conserva el temporizador de la app en seguimiento`() {
        val t = tracker()
        t.onWindowChanged("a")
        clock.advance(1_000)
        assertEquals(Action.Ignored, t.onUserPresent("systemui"))
        clock.advance(5 * minute)
        assertEquals(Action.Block("a"), t.refresh())
    }

    @Test
    fun `tras apagar la pantalla un desbloqueo sin app resuelta no hace nada`() {
        val t = tracker()
        t.onWindowChanged("a")
        t.onScreenOff()
        assertEquals(Action.Idle, t.onUserPresent(null))
    }

    // Un evento tardío del paquete anterior (p. ej. el selector de compartir al cerrarse) puede llegar
    // después del de la app limitada y dejar al tracker apuntando a un paquete sin límite.

    @Test
    fun `salir de una app limitada a otra sin limite pide confirmar el primer plano`() {
        val t = tracker()
        t.onWindowChanged("a")
        clock.advance(30_000)
        assertEquals(Action.Verify(1_000L), t.onWindowChanged("b"))
    }

    @Test
    fun `confirmar que la app limitada sigue delante reprograma el temporizador`() {
        val t = tracker()
        t.onWindowChanged("a")
        clock.advance(30_000)
        t.onWindowChanged("b")
        clock.advance(1_000)
        assertEquals(Action.Schedule("a", 5 * minute - 30_000), t.onUserPresent("a"))
    }

    @Test
    fun `confirmar que sigue la app sin limite no pide otra confirmacion`() {
        val t = tracker()
        t.onWindowChanged("a")
        t.onWindowChanged("b")
        clock.advance(1_000)
        assertEquals(Action.Idle, t.onUserPresent("b"))
    }

    @Test
    fun `entre apps sin limite no se pide confirmacion`() {
        val t = tracker()
        assertEquals(Action.Idle, t.onWindowChanged("b"))
        assertEquals(Action.Idle, t.onWindowChanged("c"))
    }

    @Test
    fun `tras bloquear una app el launcher no pide confirmacion`() {
        val t = tracker()
        limits["a"] = 0L
        assertEquals(Action.Block("a"), t.onWindowChanged("a"))
        assertEquals(Action.Idle, t.onWindowChanged("launcher"))
    }

    @Test
    fun `la pantalla apagada no pide confirmacion`() {
        val t = tracker()
        t.onWindowChanged("a")
        assertEquals(Action.Idle, t.onScreenOff())
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

    // Latido (04-nov): un parpadeo del launcher hizo perder la app limitada y nadie volvió a seguirla.

    @Test
    fun `el latido recupera una app limitada que el seguimiento perdio`() {
        val t = tracker()
        t.onWindowChanged("a")
        clock.advance(2 * minute)
        assertEquals(Action.Verify(1_000), t.onWindowChanged("launcher"))
        assertEquals(Action.Ignored, t.onUserPresent(null)) // UsageStats no la vio: se queda perdida
        clock.advance(10 * minute)
        assertEquals(Action.Schedule("a", 3 * minute), t.onHeartbeat("a"))
    }

    @Test
    fun `el latido no toca la app que ya esta en seguimiento`() {
        val t = tracker()
        t.onWindowChanged("a")
        clock.advance(minute)
        assertEquals(Action.Ignored, t.onHeartbeat("a"))
        clock.advance(minute)
        t.onWindowChanged("b")
        assertEquals(2 * minute, counter.usedMs("a"))
    }

    @Test
    fun `el latido ignora apps sin limite nulos y paquetes ignorados`() {
        val t = tracker()
        assertEquals(Action.Ignored, t.onHeartbeat("b"))
        assertEquals(Action.Ignored, t.onHeartbeat(null))
        assertEquals(Action.Ignored, t.onHeartbeat("ime"))
    }

    @Test
    fun `el latido no cancela la app en seguimiento si UsageStats no ve nada`() {
        val t = tracker()
        t.onWindowChanged("a")
        assertEquals(Action.Ignored, t.onHeartbeat(null))
        clock.advance(minute)
        t.onWindowChanged("b")
        assertEquals(minute, counter.usedMs("a"))
    }

    @Test
    fun `el latido bloquea la app limitada recuperada si ya agoto el limite`() {
        val t = tracker()
        t.onWindowChanged("a")
        clock.advance(5 * minute)
        t.onWindowChanged("launcher")
        assertEquals(Action.Block("a"), t.onHeartbeat("a"))
    }

    @Test
    fun `el latido no suma dos veces el tiempo ya contado`() {
        val t = tracker()
        t.onWindowChanged("a")
        clock.advance(2 * minute)
        t.onWindowChanged("launcher")
        clock.advance(10 * minute)
        t.onHeartbeat("a")
        assertEquals(2 * minute, t.usedSnapshot()["a"])
    }
}
