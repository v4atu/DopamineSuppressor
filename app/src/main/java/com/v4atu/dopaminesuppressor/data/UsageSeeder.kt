package com.v4atu.dopaminesuppressor.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.v4atu.dopaminesuppressor.core.UsageAggregator
import com.v4atu.dopaminesuppressor.core.UsageEvent
import com.v4atu.dopaminesuppressor.core.UsageEventType
import com.v4atu.dopaminesuppressor.core.UsageSummary

/**
 * Lee `UsageStatsManager.queryEvents` una sola vez por llamada (SPEC §5) y delega el conteo en
 * `UsageAggregator`. Debe llamarse fuera del hilo principal.
 *
 * @param home paquetes del launcher; ver [homePackages].
 */
class UsageSeeder(private val usm: UsageStatsManager, private val home: Set<String> = emptySet()) {

    fun read(begin: Long, end: Long, limited: Set<String>): UsageSummary {
        val events = usm.queryEvents(begin, end)
        val event = UsageEvents.Event()
        val relevant = sequence {
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val type = when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> UsageEventType.RESUMED
                    UsageEvents.Event.ACTIVITY_PAUSED -> UsageEventType.PAUSED
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageEventType.SCREEN_OFF
                    else -> continue
                }
                yield(UsageEvent(type, event.packageName, event.timeStamp))
            }
        }
        return UsageAggregator.summarize(relevant, end, limited, home)
    }

    companion object {
        /** Paquetes que pueden ser pantalla de inicio; en ellos vive también Recientes. */
        @Suppress("DEPRECATION") // La sobrecarga con ResolveInfoFlags solo existe desde API 33.
        fun homePackages(pm: PackageManager): Set<String> {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                pm.queryIntentActivities(intent, 0)
            }
            return resolved.map { it.activityInfo.packageName }.toSet()
        }
    }
}
