# AGENTS.md

Guía para agentes de código (Claude Code, Codex, Gemini CLI, Cursor, etc.) que trabajen en este repositorio.

## Comandos

App Android de un solo módulo (`:app`), Kotlin, AGP 9.4 (Kotlin integrado en AGP, sin plugin `kotlin-android`), minSdk 29, compile/targetSdk 37. El daemon de Gradle usa toolchain JDK 25 (`gradle/gradle-daemon-jvm.properties`); el bytecode apunta a Java 11. Configuration cache activado.

- Compilar debug: `./gradlew :app:assembleDebug`
- Instalar en dispositivo: `./gradlew :app:installDebug`
- Tests unitarios (JVM, JUnit 4): `./gradlew :app:testDebugUnitTest`
- Un solo test: `./gradlew :app:testDebugUnitTest --tests "com.v4atu.dopaminesuppressor.core.ForegroundTrackerTest"` (añadir `.nombreDelMetodo` para un método)
- Lint: `./gradlew :app:lintDebug`
- Release: `./gradlew :app:assembleRelease`. Se firma solo si existe `keystore.properties` en la raíz (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); si no, sale sin firmar. R8 activo vía `optimization { enable = true }`.

No hay tests instrumentados (`androidTest`).

## Arquitectura

Capas por paquete en `com.v4atu.dopaminesuppressor`:

- `core/` — lógica pura de Kotlin **sin APIs de Android**, toda testeable en JVM. El tiempo se inyecta con la interfaz `Clock` (`elapsedMillis()` monotónico + `now()` local); en tests se usa `FakeClock`.
  - `ForegroundTracker`: máquina de estados que recibe eventos (cambio de ventana, pantalla apagada, desbloqueo, siembra, refresco) y devuelve una `Action` (`Ignored`, `Idle`, `Block(pkg)`, `Schedule(pkg, delayMs)`). Maneja corte de medianoche, debounce tras bloquear (1,5 s) y paquetes ignorados.
  - `UsageCounter`: ms usados hoy por app limitada, solo en memoria; se reinicia al cambiar el día.
  - `UsageAggregator`: suma sesiones a partir de eventos de UsageStats ya reducidos (`RESUMED/PAUSED/SCREEN_OFF`).
- `data/` — persistencia y adaptadores Android.
  - `LimitsRepository`: Preferences DataStore `"limits"`; `LimitsCodec` mapea `AppLimit` a claves `limit.<pkg>` (minutos, Int) y `on.<pkg>` (Boolean, por defecto `true`).
  - `UsageSeeder`: lee `UsageStatsManager.queryEvents` y delega en `UsageAggregator`. Debe llamarse fuera del hilo principal.
- `guard/` — `GuardService` (AccessibilityService, escucha solo `TYPE_WINDOW_STATE_CHANGED`) traduce eventos al `ForegroundTracker` y ejecuta las `Action`: bloquear = `performGlobalAction(GLOBAL_ACTION_HOME)`; programar = `Handler.postDelayed` que llama `tracker.refresh()`.
- `ui/` — `MainActivity` (Views + RecyclerView, sin Compose) lista apps del launcher, muestra permisos (uso, accesibilidad, batería) y edita límites con `LimitEditorDialog`.

### Flujo y reglas clave

- **Dos fuentes de verdad del uso**: el contador interno del servicio y UsageStats (lo que muestra la UI). El servicio **re-siembra** el contador desde UsageStats al cargar límites, al añadirse una app limitada, al cambiar el día y cada vez que una app limitada pasa a primer plano (`apply(..., reconcile = true)`). Tras sembrar se llama `tracker.onSeeded()`, que reinicia la sesión abierta sin volver a sumarla (UsageStats ya la incluye). Solo hay una siembra en curso a la vez (`seeding`/`reseedRequested`).
- **Hilos**: todo el estado del servicio se toca en el hilo principal; las lecturas de UsageStats van a un executor de un solo hilo y vuelven con `handler.post`.
- UI y servicio corren en el **mismo proceso**: comparten el DataStore (cambios en la UI llegan al servicio por el `Flow`) y el objeto `GuardState.counted`, una copia diagnóstica temporal del contador interno que la UI muestra junto al uso de UsageStats.
- Al desbloquear (`ACTION_USER_PRESENT`) no siempre llega evento de ventana: el servicio consulta UsageStats de los últimos 10 s y reintenta una vez a los 500 ms.
- Se ignoran como cambio de app: el propio paquete, `com.android.systemui` y los teclados habilitados.
- Los comentarios citan "SPEC §4" (seguimiento de primer plano) y "SPEC §5" (siembra desde UsageStats); ese documento no está en el repo.

### Convenciones

- Comentarios KDoc y textos en español; estilo Kotlin `official`.
- Mantener `core/` libre de imports de Android para que siga siendo testeable en JVM; el código dependiente de Android va en `data/`, `guard/` o `ui/`.
- Versiones de dependencias en `gradle/libs.versions.toml`.
