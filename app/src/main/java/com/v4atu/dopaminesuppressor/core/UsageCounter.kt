package com.v4atu.dopaminesuppressor.core

import java.time.LocalDate

/** Milisegundos usados hoy por cada app limitada. Vive solo en memoria. */
class UsageCounter(private val clock: Clock) {

    private val usedMs = HashMap<String, Long>()

    var day: LocalDate = clock.now().toLocalDate()
        private set

    fun usedMs(pkg: String): Long = usedMs[pkg] ?: 0L

    fun add(pkg: String, ms: Long) {
        if (ms <= 0L) return
        usedMs[pkg] = usedMs(pkg) + ms
    }

    /** Reemplaza el conteo con el uso ya ocurrido hoy (ver SPEC §5). */
    fun seed(totals: Map<String, Long>) {
        usedMs.clear()
        usedMs.putAll(totals)
    }

    /** Si cambió el día, reinicia el conteo y devuelve `true` (quien llama debe volver a sembrar). */
    fun rolloverIfNeeded(): Boolean {
        val today = clock.now().toLocalDate()
        if (today == day) return false
        day = today
        usedMs.clear()
        return true
    }
}
