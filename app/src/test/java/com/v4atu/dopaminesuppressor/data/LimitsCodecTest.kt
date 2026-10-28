package com.v4atu.dopaminesuppressor.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitsCodecTest {

    @Test
    fun `ida y vuelta de varios limites`() {
        val prefs = mutablePreferencesOf()
        LimitsCodec.put(prefs, AppLimit("a", 90, true))
        LimitsCodec.put(prefs, AppLimit("b.c", 5, false))

        val decoded = LimitsCodec.decode(prefs)
        assertEquals(mapOf("a" to AppLimit("a", 90, true), "b.c" to AppLimit("b.c", 5, false)), decoded)
    }

    @Test
    fun `guardar de nuevo reemplaza el limite`() {
        val prefs = mutablePreferencesOf()
        LimitsCodec.put(prefs, AppLimit("a", 10, true))
        LimitsCodec.put(prefs, AppLimit("a", 20, false))
        assertEquals(AppLimit("a", 20, false), LimitsCodec.decode(prefs)["a"])
    }

    @Test
    fun `quitar borra el limite y su interruptor`() {
        val prefs = mutablePreferencesOf()
        LimitsCodec.put(prefs, AppLimit("a", 10, true))
        LimitsCodec.remove(prefs, "a")
        assertTrue(LimitsCodec.decode(prefs).isEmpty())
        assertTrue(prefs.asMap().isEmpty())
    }

    @Test
    fun `sin preferencias no hay limites`() {
        assertTrue(LimitsCodec.decode(mutablePreferencesOf()).isEmpty())
    }

    @Test
    fun `limitMillis convierte minutos a milisegundos`() {
        assertEquals(90 * 60_000L, AppLimit("a", 90, true).limitMillis)
    }
}
