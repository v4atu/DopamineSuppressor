package com.v4atu.dopaminesuppressor.guard

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.v4atu.dopaminesuppressor.core.Action
import com.v4atu.dopaminesuppressor.core.ForegroundTracker
import com.v4atu.dopaminesuppressor.core.UsageCounter
import com.v4atu.dopaminesuppressor.data.AppLimit
import com.v4atu.dopaminesuppressor.data.LimitsRepository
import com.v4atu.dopaminesuppressor.data.UsageSeeder
import java.time.ZoneId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GuardService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val timer = Runnable { apply(tracker.refresh()) }
    private val counter = UsageCounter(AndroidClock)

    // Límites activos en ms por paquete. Solo se toca desde el hilo principal.
    private var limitsMs: Map<String, Long> = emptyMap()
    private var limitsLoaded = false

    // Lectura de UsageStats en curso y petición pendiente (solo hilo principal).
    private var seeding = false
    private var reseedRequested = false

    private val tracker = ForegroundTracker(
        clock = AndroidClock,
        counter = counter,
        limits = { limitsMs },
        isIgnored = { it in ignoredPackages },
        onDayRollover = ::seedFromUsageStats,
    )

    // Se llena en onServiceConnected; los teclados cambian poco, así que no se consulta por evento.
    private var ignoredPackages: Set<String> = emptySet()
    private var seeder: UsageSeeder? = null
    private var background: ExecutorService? = null
    private var scope: CoroutineScope? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    handler.removeCallbacks(timer)
                    apply(tracker.onScreenOff())
                }
                Intent.ACTION_USER_PRESENT -> resolveForeground(retry = true)
            }
        }
    }

    override fun onServiceConnected() {
        ignoredPackages = buildSet {
            add(packageName)
            add(SYSTEM_UI)
            getSystemService(InputMethodManager::class.java).enabledInputMethodList
                .forEach { add(it.packageName) }
        }
        seeder = UsageSeeder(getSystemService(UsageStatsManager::class.java))
        background = Executors.newSingleThreadExecutor()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)

        // Un único colector: la primera emisión carga los límites y siembra el conteo (SPEC §5).
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        scope.launch { LimitsRepository(this@GuardService).limits.collect(::onLimitsChanged) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        apply(tracker.onWindowChanged(pkg))
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        background?.shutdownNow()
        background = null
        super.onDestroy()
    }

    /** Se ejecuta en el hilo principal cada vez que el usuario cambia algo en la UI. */
    private fun onLimitsChanged(all: Map<String, AppLimit>) {
        val new = all.values.filter { it.enabled }.associate { it.packageName to it.limitMillis }
        val added = new.keys - limitsMs.keys
        val first = !limitsLoaded
        limitsMs = new
        limitsLoaded = true
        // Una app limitada nueva puede traer uso previo de hoy: hay que volver a sembrar.
        if (first || added.isNotEmpty()) seedFromUsageStats() else apply(tracker.refresh())
    }

    /**
     * Cancela el temporizador anterior y aplica la decisión del tracker (siempre en el hilo principal).
     * Con `reconcile`, una app limitada que pasa a primer plano se contrasta con UsageStats, que es
     * lo que muestra la UI: así el bloqueo no depende de que el contador propio esté al día.
     */
    private fun apply(action: Action, reconcile: Boolean = true) {
        when (action) {
            Action.Ignored -> return
            Action.Idle -> handler.removeCallbacks(timer)
            is Action.Block -> {
                handler.removeCallbacks(timer)
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            is Action.Schedule -> {
                handler.removeCallbacks(timer)
                handler.postDelayed(timer, action.delayMs)
                if (reconcile) seedFromUsageStats()
            }
        }
        GuardState.counted = tracker.usedSnapshot()
    }

    /**
     * Siembra el conteo de hoy desde UsageStats (SPEC §5), una vez y fuera del hilo principal.
     * El resultado ya incluye la sesión abierta, así que el tracker reinicia su sesión (`onSeeded`).
     * Solo hay una lectura a la vez; si llega otra petición mientras tanto, se repite al terminar.
     */
    private fun seedFromUsageStats() {
        val seeder = seeder ?: return
        val limited = limitsMs.keys
        if (limited.isEmpty()) {
            apply(tracker.onSeeded(null), reconcile = false)
            return
        }
        if (seeding) {
            reseedRequested = true
            return
        }
        seeding = true
        background?.execute {
            val end = System.currentTimeMillis()
            val begin = counter.day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val result = seeder.read(begin, end, limited)
            handler.post {
                seeding = false
                counter.seed(result.totals)
                apply(tracker.onSeeded(result.foreground), reconcile = false)
                if (reseedRequested) {
                    reseedRequested = false
                    seedFromUsageStats()
                }
            }
        }
    }

    /**
     * Al desbloquear no siempre llega un evento de ventana (SPEC §4): se pregunta a UsageStats.
     * Si el `ACTIVITY_RESUMED` aún no está disponible, se reintenta una vez tras 500 ms.
     */
    private fun resolveForeground(retry: Boolean) {
        val seeder = seeder ?: return
        background?.execute {
            val end = System.currentTimeMillis()
            val foreground = seeder.read(end - UNLOCK_WINDOW_MS, end, emptySet()).foreground
            handler.post {
                if (foreground == null && retry) {
                    handler.postDelayed({ resolveForeground(retry = false) }, RETRY_DELAY_MS)
                } else {
                    apply(tracker.onUserPresent(foreground))
                }
            }
        }
    }

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
        const val UNLOCK_WINDOW_MS = 10_000L
        const val RETRY_DELAY_MS = 500L
    }
}
