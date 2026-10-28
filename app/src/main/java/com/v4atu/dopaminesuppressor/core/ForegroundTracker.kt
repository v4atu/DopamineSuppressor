package com.v4atu.dopaminesuppressor.core

import java.time.Duration

/**
 * Lógica de SPEC §4: qué app está visible, desde cuándo, y cuánto le queda.
 * No usa APIs de Android; el servicio le entrega los eventos.
 *
 * @param limits límite diario en ms por paquete (solo apps con límite activo).
 * @param isIgnored paquetes que no cuentan como cambio de app (propio, systemui, teclados).
 * @param onDayRollover se llama cuando cambia el día, para volver a sembrar el conteo.
 */
class ForegroundTracker(
    private val clock: Clock,
    private val counter: UsageCounter,
    private val limits: () -> Map<String, Long>,
    private val isIgnored: (String) -> Boolean,
    private val onDayRollover: () -> Unit = {},
) {
    private var current: String? = null
    private var since: Long = 0L
    private var sessionDay = counter.day
    private var lastBlockedPkg: String? = null
    private var lastBlockedAt = 0L

    fun onWindowChanged(pkg: String): Action {
        if (isIgnored(pkg) || pkg == current) return Action.Ignored
        // Tras bloquear, la propia app emite otro evento ~1 s después; no hay que bloquear dos veces.
        if (pkg == lastBlockedPkg && clock.elapsedMillis() - lastBlockedAt < BLOCK_DEBOUNCE_MS) {
            return Action.Ignored
        }
        return switchTo(pkg)
    }

    /** La pantalla apagada no cuenta. */
    fun onScreenOff(): Action {
        closeSession()
        current = null
        return Action.Idle
    }

    /** Al desbloquear no siempre llega un evento de ventana; el servicio resuelve la app con UsageStats. */
    fun onUserPresent(pkg: String?): Action {
        if (pkg == null || isIgnored(pkg)) return Action.Idle
        return switchTo(pkg)
    }

    /** Vuelve a evaluar la app actual: temporizador vencido, medianoche o límite editado. */
    fun refresh(): Action {
        val pkg = current ?: return Action.Idle
        return switchTo(pkg)
    }

    /**
     * El conteo se acaba de sembrar desde UsageStats, que ya incluye la sesión abierta: se reinicia
     * la sesión sin volver a sumarla. Si no había app actual, se adopta `foreground`.
     */
    fun onSeeded(foreground: String?): Action {
        val pkg = current
        if (pkg == null) {
            if (foreground == null || isIgnored(foreground)) return Action.Idle
            return switchTo(foreground)
        }
        since = clock.elapsedMillis()
        sessionDay = counter.day
        return track(pkg, evaluate(pkg))
    }

    private fun switchTo(pkg: String): Action {
        closeSession()
        current = pkg
        since = clock.elapsedMillis()
        sessionDay = counter.day
        return track(pkg, evaluate(pkg))
    }

    private fun track(pkg: String, action: Action): Action {
        // Tras bloquear, la app deja de ser la actual: si el launcher no emite evento,
        // reabrirla no debe confundirse con «mismo paquete» (SPEC §4, paso 5).
        if (action is Action.Block) {
            current = null
            lastBlockedPkg = pkg
            lastBlockedAt = clock.elapsedMillis()
        } else {
            lastBlockedPkg = null
        }
        return action
    }

    private fun closeSession() {
        if (counter.rolloverIfNeeded()) onDayRollover()
        val pkg = current ?: return
        if (pkg !in limits()) return
        val elapsed = clock.elapsedMillis() - since
        // Una sesión que cruza la medianoche solo cuenta la parte de hoy.
        val counted = if (sessionDay == counter.day) elapsed else minOf(elapsed, msSinceMidnight())
        counter.add(pkg, counted)
    }

    private fun evaluate(pkg: String): Action {
        val limit = limits()[pkg] ?: return Action.Idle
        val remaining = limit - counter.usedMs(pkg)
        if (remaining <= 0L) return Action.Block(pkg)
        return Action.Schedule(pkg, minOf(remaining, msUntilMidnight()))
    }

    private fun msSinceMidnight(): Long {
        val now = clock.now()
        return Duration.between(now.toLocalDate().atStartOfDay(), now).toMillis()
    }

    private fun msUntilMidnight(): Long {
        val now = clock.now()
        return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).toMillis()
    }

    private companion object {
        const val BLOCK_DEBOUNCE_MS = 1_500L
    }
}
