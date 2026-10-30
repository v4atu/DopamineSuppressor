package com.v4atu.dopaminesuppressor.core

/** Qué debe hacer el servicio tras un evento. Siempre cancela antes el temporizador anterior. */
sealed interface Action {
    /** Evento filtrado: no cambia nada, el temporizador sigue como estaba. */
    data object Ignored : Action

    /** No hay app limitada en primer plano: no se programa nada. */
    data object Idle : Action

    /** Bloquear `pkg` ya. */
    data class Block(val pkg: String) : Action

    /** Volver a evaluar `pkg` dentro de `delayMs` (límite o medianoche, lo que llegue primero). */
    data class Schedule(val pkg: String, val delayMs: Long) : Action

    /**
     * Se salió de una app limitada hacia un paquete sin límite, lo que puede ser un evento tardío del
     * paquete anterior. Dentro de `delayMs` hay que confirmar con UsageStats qué app está delante y
     * entregarla con `onUserPresent`.
     */
    data class Verify(val delayMs: Long) : Action
}
