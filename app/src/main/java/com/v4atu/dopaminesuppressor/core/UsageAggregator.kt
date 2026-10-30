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

/**
 * Suma sesiones de primer plano a partir de los eventos de UsageStats (SPEC §5). Sin Android.
 *
 * `home` son los paquetes del launcher. En Recientes (Quickstep) la app anterior sigue RESUMED junto
 * al launcher sin PAUSED; mientras el launcher está delante esa app no cuenta.
 */
object UsageAggregator {

    fun summarize(
        events: Sequence<UsageEvent>,
        end: Long,
        limited: Set<String>,
        home: Set<String> = emptySet(),
    ): UsageSummary {
        val open = HashMap<String, Long>()
        val totals = HashMap<String, Long>()
        // Apps cerradas porque el launcher pasó al frente y que aún no recibieron PAUSED.
        val suspended = LinkedHashSet<String>()
        var foreground: String? = null

        fun close(pkg: String, at: Long) {
            val start = open.remove(pkg) ?: return
            if (pkg in limited) totals.merge(pkg, at - start, Long::plus)
        }

        for (event in events) {
            val pkg = event.packageName
            when (event.type) {
                UsageEventType.RESUMED -> if (pkg != null) {
                    // Recientes con «live tile»: la app sigue RESUMED junto al launcher, sin PAUSED.
                    // Con el launcher delante no es uso real, así que se cierra su sesión.
                    if (pkg in home) {
                        for (other in open.keys.filter { it !in home }) {
                            close(other, event.timestamp)
                            suspended.add(other)
                        }
                    }
                    suspended.remove(pkg)
                    open.putIfAbsent(pkg, event.timestamp)
                    foreground = pkg
                }
                UsageEventType.PAUSED -> if (pkg != null) {
                    suspended.remove(pkg)
                    close(pkg, event.timestamp)
                    if (foreground == pkg) foreground = null
                    // Si el launcher se va y la app nunca se pausó (se tocó su tarjeta), vuelve a estar delante.
                    if (pkg in home && suspended.isNotEmpty()) {
                        suspended.forEach { open[it] = event.timestamp }
                        foreground = suspended.last()
                        suspended.clear()
                    }
                }
                UsageEventType.SCREEN_OFF -> {
                    open.keys.toList().forEach { close(it, event.timestamp) }
                    suspended.clear()
                    foreground = null
                }
            }
        }
        open.keys.toList().forEach { close(it, end) }
        return UsageSummary(totals, foreground)
    }
}
