package com.v4atu.dopaminesuppressor.core

enum class UsageEventType { RESUMED, PAUSED, STOPPED, SCREEN_OFF }

/**
 * Evento de UsageStats reducido a lo que importa para contar; `timestamp` en ms.
 * `className` identifica la actividad: un paquete con varias actividades emite un par RESUMED/PAUSED por cada una.
 */
class UsageEvent(
    val type: UsageEventType,
    val packageName: String?,
    val timestamp: Long,
    val className: String? = null,
)

/**
 * @param totals ms usados por cada paquete pedido. Una sesión que sigue abierta al final se
 *   cuenta hasta `end`.
 * @param foreground última app con `RESUMED` sin `PAUSED` posterior, o `null`.
 */
data class UsageSummary(val totals: Map<String, Long>, val foreground: String?)

/**
 * Suma sesiones de primer plano a partir de los eventos de UsageStats (SPEC §5). Sin Android.
 *
 * Un paquete está en primer plano mientras tenga alguna actividad abierta (`RESUMED` sin su `PAUSED`):
 * al pasar de una actividad a otra del mismo paquete los eventos pueden solaparse, y el `PAUSED` de la
 * primera no debe cortar la sesión.
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
        // Inicio de la sesión abierta de cada paquete y actividades que la mantienen abierta.
        val open = HashMap<String, Long>()
        val activities = HashMap<String, MutableSet<String?>>()
        val totals = HashMap<String, Long>()
        // Apps cerradas porque el launcher pasó al frente y que aún no recibieron PAUSED, con sus actividades.
        val suspended = LinkedHashMap<String, MutableSet<String?>>()
        var foreground: String? = null

        fun close(pkg: String, at: Long) {
            val start = open.remove(pkg) ?: return
            if (pkg in limited) totals.merge(pkg, at - start, Long::plus)
        }

        // La actividad ya no está delante (PAUSED) o ya no es visible (STOPPED); el paquete se cierra con la última.
        fun release(pkg: String, className: String?, at: Long) {
            suspended[pkg]?.let {
                it.remove(className)
                if (it.isEmpty()) suspended.remove(pkg)
            }
            val remaining = activities[pkg] ?: return
            remaining.remove(className)
            if (remaining.isEmpty()) {
                activities.remove(pkg)
                close(pkg, at)
                if (foreground == pkg) foreground = null
            }
        }

        for (event in events) {
            val pkg = event.packageName
            when (event.type) {
                UsageEventType.RESUMED -> if (pkg != null) {
                    // Recientes con «live tile»: la app sigue RESUMED junto al launcher, sin PAUSED.
                    // Con el launcher delante no es uso real, así que se cierra su sesión.
                    if (pkg in home) {
                        for (other in open.keys.filter { it !in home }) {
                            suspended[other] = activities.remove(other) ?: mutableSetOf()
                            close(other, event.timestamp)
                        }
                    }
                    suspended.remove(pkg)
                    activities.getOrPut(pkg) { mutableSetOf() }.add(event.className)
                    open.putIfAbsent(pkg, event.timestamp)
                    foreground = pkg
                }
                UsageEventType.PAUSED -> if (pkg != null) {
                    release(pkg, event.className, event.timestamp)
                    // Si el launcher se va y la app nunca se pausó (se tocó su tarjeta), vuelve a estar delante.
                    if (pkg in home && suspended.isNotEmpty()) {
                        for ((other, classes) in suspended) {
                            activities[other] = classes
                            open[other] = event.timestamp
                        }
                        foreground = suspended.keys.last()
                        suspended.clear()
                    }
                }
                // Android a veces emite STOPPED sin PAUSED: sin esto la sesión seguía abierta hasta apagar la pantalla.
                UsageEventType.STOPPED -> if (pkg != null) release(pkg, event.className, event.timestamp)
                UsageEventType.SCREEN_OFF -> {
                    open.keys.toList().forEach { close(it, event.timestamp) }
                    activities.clear()
                    suspended.clear()
                    foreground = null
                }
            }
        }
        open.keys.toList().forEach { close(it, end) }
        return UsageSummary(totals, foreground)
    }
}
