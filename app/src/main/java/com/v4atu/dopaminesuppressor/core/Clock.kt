package com.v4atu.dopaminesuppressor.core

import java.time.LocalDateTime

/** Reloj inyectable: permite probar la lógica con tiempo simulado. */
interface Clock {
    /** Tiempo monotónico en ms (en Android, `SystemClock.elapsedRealtime()`). */
    fun elapsedMillis(): Long

    /** Fecha y hora locales; define el día para el límite diario. */
    fun now(): LocalDateTime
}
