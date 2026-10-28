package com.v4atu.dopaminesuppressor.core

enum class UsageEventType { RESUMED, PAUSED, SCREEN_OFF }

/** Evento de UsageStats reducido a lo que importa para contar; `timestamp` en ms. */
class UsageEvent(val type: UsageEventType, val packageName: String?, val timestamp: Long)

/**
 * @param totals ms usados por cada paquete pedido. Una sesión que sigue abierta al final se
 *   cuenta hasta `end`.
 * @param foreground última app con `RESUMED` sin `PAUSED` posterior, o `null`.
 */
data class UsageSummary(val totals: Map<String, Long>, val foreground: String?)

/** Suma sesiones de primer plano a partir de los eventos de UsageStats (SPEC §5). Sin Android. */
object UsageAggregator {

    fun summarize(events: Sequence<UsageEvent>, end: Long, limited: Set<String>): UsageSummary {
        val open = HashMap<String, Long>()
        val totals = HashMap<String, Long>()
        var foreground: String? = null

        fun close(pkg: String, at: Long) {
            val start = open.remove(pkg) ?: return
            if (pkg in limited) totals.merge(pkg, at - start, Long::plus)
        }

        for (event in events) {
            val pkg = event.packageName
            when (event.type) {
                UsageEventType.RESUMED -> if (pkg != null) {
                    open.putIfAbsent(pkg, event.timestamp)
                    foreground = pkg
                }
                UsageEventType.PAUSED -> if (pkg != null) {
                    close(pkg, event.timestamp)
                    if (foreground == pkg) foreground = null
                }
                UsageEventType.SCREEN_OFF -> {
                    open.keys.toList().forEach { close(it, event.timestamp) }
                    foreground = null
                }
            }
        }
        open.keys.toList().forEach { close(it, end) }
        return UsageSummary(totals, foreground)
    }
}
