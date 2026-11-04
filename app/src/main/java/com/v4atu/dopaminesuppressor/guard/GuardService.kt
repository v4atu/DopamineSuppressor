package com.v4atu.dopaminesuppressor.guard

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log // TEMP-DIAG
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.v4atu.dopaminesuppressor.core.Action
import com.v4atu.dopaminesuppressor.core.ForegroundTracker
import com.v4atu.dopaminesuppressor.core.UsageCounter
import com.v4atu.dopaminesuppressor.data.AppLimit
import com.v4atu.dopaminesuppressor.data.LimitsRepository
import com.v4atu.dopaminesuppressor.data.UsageSeeder
import java.io.File // TEMP-DIAG
import java.time.LocalTime // TEMP-DIAG
import java.time.ZoneId
import java.time.format.DateTimeFormatter // TEMP-DIAG
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GuardService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val timer = Runnable {
        diag("timer fired") // TEMP-DIAG
        apply(tracker.refresh())
    }
    // Confirma con UsageStats la app en primer plano tras salir de una limitada (SPEC §4).
    private val verify = Runnable {
        diag("verify fired") // TEMP-DIAG
        resolveForeground(retry = true, windowMs = LOOKBACK_MS)
    }
    // Latido con la pantalla encendida: recupera una app limitada que el seguimiento perdió (04-nov).
    private var heartbeatActive = false
    private val heartbeat = Runnable {
        readForeground(LOOKBACK_MS) { foreground ->
            diag("heartbeat fg=$foreground") // TEMP-DIAG
            if (heartbeatActive) apply(tracker.onHeartbeat(foreground))
        }
        scheduleHeartbeat()
    }
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
                    diag("screen off") // TEMP-DIAG
                    stopHeartbeat()
                    cancelPending()
                    apply(tracker.onScreenOff())
                }
                Intent.ACTION_USER_PRESENT -> {
                    diag("user present") // TEMP-DIAG
                    resolveForeground(retry = true)
                    startHeartbeat()
                }
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
        seeder = UsageSeeder(getSystemService(UsageStatsManager::class.java), UsageSeeder.homePackages(packageManager))
        background = Executors.newSingleThreadExecutor()
        diagExecutor = Executors.newSingleThreadExecutor() // TEMP-DIAG
        diagLog = DiagLog(File(filesDir, "guard-diag.log"), DIAG_MAX_BYTES) { // TEMP-DIAG
            LocalTime.now().format(DIAG_TIME)
        }
        diag("service connected") // TEMP-DIAG

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
        if (getSystemService(PowerManager::class.java).isInteractive) startHeartbeat()

        // Un único colector: la primera emisión carga los límites y siembra el conteo (SPEC §5).
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        scope.launch { LimitsRepository(this@GuardService).limits.collect(::onLimitsChanged) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()
        diag("event pkg=$pkg cls=${event.className}") // TEMP-DIAG
        apply(tracker.onWindowChanged(pkg ?: return))
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        heartbeatActive = false
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        background?.shutdownNow()
        background = null
        diag("service destroyed") // TEMP-DIAG
        diagExecutor?.shutdown() // TEMP-DIAG
        diagExecutor = null // TEMP-DIAG
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
        diag("action=$action reconcile=$reconcile") // TEMP-DIAG
        when (action) {
            Action.Ignored -> return
            Action.Idle -> cancelPending()
            is Action.Block -> {
                cancelPending()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            is Action.Schedule -> {
                cancelPending()
                handler.postDelayed(timer, action.delayMs)
                if (reconcile) seedFromUsageStats()
            }
            is Action.Verify -> {
                cancelPending()
                handler.postDelayed(verify, action.delayMs)
            }
        }
        diag("counted=${tracker.usedSnapshot()}") // TEMP-DIAG
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
                diag("seeded totals=${result.totals} foreground=${result.foreground}") // TEMP-DIAG
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
    private fun resolveForeground(retry: Boolean, windowMs: Long = UNLOCK_WINDOW_MS) {
        readForeground(windowMs) { foreground ->
            diag("resolve retry=$retry windowMs=$windowMs foreground=$foreground") // TEMP-DIAG
            if (foreground == null && retry) {
                handler.postDelayed({ resolveForeground(retry = false, windowMs = windowMs) }, RETRY_DELAY_MS)
            } else {
                apply(tracker.onUserPresent(foreground))
            }
        }
    }

    /** Lee de UsageStats la app en primer plano (fuera del hilo principal) y entrega el resultado en él. */
    private fun readForeground(windowMs: Long, onResult: (String?) -> Unit) {
        val seeder = seeder ?: return
        background?.execute {
            val end = System.currentTimeMillis()
            val foreground = seeder.read(end - windowMs, end, emptySet()).foreground
            handler.post { onResult(foreground) }
        }
    }

    private fun startHeartbeat() {
        heartbeatActive = true
        scheduleHeartbeat()
    }

    private fun scheduleHeartbeat() {
        handler.removeCallbacks(heartbeat)
        if (heartbeatActive) handler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    private fun stopHeartbeat() {
        heartbeatActive = false
        handler.removeCallbacks(heartbeat)
    }

    // TEMP-DIAG: logcat más archivo `files/guard-diag.log` (leer con `adb shell run-as <paquete> cat files/guard-diag.log`).
    private var diagLog: DiagLog? = null
    private var diagExecutor: ExecutorService? = null

    private fun diag(message: String) { // TEMP-DIAG
        Log.d(TAG, message)
        val log = diagLog ?: return
        diagExecutor?.execute { log.append(message) }
    }

    /** Cancela el temporizador de bloqueo y la confirmación de primer plano pendientes. */
    private fun cancelPending() {
        handler.removeCallbacks(timer)
        handler.removeCallbacks(verify)
    }

    private companion object {
        const val TAG = "Guard" // TEMP-DIAG
        const val DIAG_MAX_BYTES = 200_000L // TEMP-DIAG
        val DIAG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS") // TEMP-DIAG
        const val SYSTEM_UI = "com.android.systemui"
        const val UNLOCK_WINDOW_MS = 10_000L
        // Verify y el latido deben ver el RESUMED de la app aunque sea de hace horas (sesión larga sin eventos).
        // Solo calculan el primer plano (sin totales); un SCREEN_OFF en la ventana lo reinicia.
        const val LOOKBACK_MS = 24 * 60 * 60_000L
        const val HEARTBEAT_MS = 5 * 60_000L
        const val RETRY_DELAY_MS = 500L
    }
}
