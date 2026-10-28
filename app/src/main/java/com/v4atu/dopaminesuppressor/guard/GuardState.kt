package com.v4atu.dopaminesuppressor.guard

/**
 * Copia del contador interno del servicio para que la UI (mismo proceso) la muestre junto al uso
 * de UsageStats. Diagnóstico temporal: permite ver cuándo se desfasan las dos cuentas.
 */
object GuardState {
    @Volatile
    var counted: Map<String, Long> = emptyMap()
}
