package com.v4atu.dopaminesuppressor.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import com.v4atu.dopaminesuppressor.core.UsageAggregator
import com.v4atu.dopaminesuppressor.core.UsageEvent
import com.v4atu.dopaminesuppressor.core.UsageEventType
import com.v4atu.dopaminesuppressor.core.UsageSummary

/**
 * Lee `UsageStatsManager.queryEvents` una sola vez por llamada (SPEC §5) y delega el conteo en
 * `UsageAggregator`. Debe llamarse fuera del hilo principal.
 */
class UsageSeeder(private val usm: UsageStatsManager) {

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
        return UsageAggregator.summarize(relevant, end, limited)
    }
}
