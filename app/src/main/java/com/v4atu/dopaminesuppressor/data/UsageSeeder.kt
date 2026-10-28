package com.v4atu.dopaminesuppressor.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager

/**
 * Lee `UsageStatsManager.queryEvents` una sola vez por llamada (SPEC §5). Debe llamarse
 * fuera del hilo principal.
 */
class UsageSeeder(private val usm: UsageStatsManager) {

    /**
     * @param totals ms usados en el rango por cada paquete de `limited`. Una sesión que sigue
     *   abierta al final se cuenta hasta `end`.
     * @param foreground última app con `ACTIVITY_RESUMED` sin `ACTIVITY_PAUSED` posterior, o `null`.
     */
    class Result(val totals: Map<String, Long>, val foreground: String?)

    fun read(begin: Long, end: Long, limited: Set<String>): Result {
        val events = usm.queryEvents(begin, end)
        val event = UsageEvents.Event()
        val open = HashMap<String, Long>()
        val totals = HashMap<String, Long>()
        var foreground: String? = null

        fun close(pkg: String, at: Long) {
            val start = open.remove(pkg) ?: return
            if (pkg in limited) totals.merge(pkg, at - start, Long::plus)
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    open.putIfAbsent(pkg, event.timeStamp)
                    foreground = pkg
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    close(pkg, event.timeStamp)
                    if (foreground == pkg) foreground = null
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    open.keys.toList().forEach { close(it, event.timeStamp) }
                    foreground = null
                }
            }
        }
        open.keys.toList().forEach { close(it, end) }
        return Result(totals, foreground)
    }
}
