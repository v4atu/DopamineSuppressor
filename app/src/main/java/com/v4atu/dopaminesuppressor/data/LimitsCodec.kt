package com.v4atu.dopaminesuppressor.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey

/** Convierte entre `AppLimit` y las claves de Preferences DataStore: `limit.<pkg>` (min) y `on.<pkg>`. */
object LimitsCodec {
    private const val LIMIT_PREFIX = "limit."
    private const val ON_PREFIX = "on."

    fun decode(prefs: Preferences): Map<String, AppLimit> {
        val limits = HashMap<String, AppLimit>()
        for (key in prefs.asMap().keys) {
            if (!key.name.startsWith(LIMIT_PREFIX)) continue
            val pkg = key.name.removePrefix(LIMIT_PREFIX)
            val minutes = prefs[intPreferencesKey(key.name)] ?: continue
            val enabled = prefs[booleanPreferencesKey(ON_PREFIX + pkg)] ?: true
            limits[pkg] = AppLimit(pkg, minutes, enabled)
        }
        return limits
    }

    fun put(prefs: MutablePreferences, limit: AppLimit) {
        prefs[intPreferencesKey(LIMIT_PREFIX + limit.packageName)] = limit.dailyLimitMinutes
        prefs[booleanPreferencesKey(ON_PREFIX + limit.packageName)] = limit.enabled
    }

    fun remove(prefs: MutablePreferences, pkg: String) {
        prefs.remove(intPreferencesKey(LIMIT_PREFIX + pkg))
        prefs.remove(booleanPreferencesKey(ON_PREFIX + pkg))
    }
}
