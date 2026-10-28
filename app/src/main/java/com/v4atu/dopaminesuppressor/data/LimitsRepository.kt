package com.v4atu.dopaminesuppressor.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.limitsStore: DataStore<Preferences> by preferencesDataStore(name = "limits")

/** Límites por app en disco. La UI y el servicio viven en el mismo proceso y comparten el DataStore. */
class LimitsRepository(context: Context) {
    private val store = context.applicationContext.limitsStore

    val limits: Flow<Map<String, AppLimit>> = store.data.map(LimitsCodec::decode)

    suspend fun save(limit: AppLimit) {
        store.edit { LimitsCodec.put(it, limit) }
    }

    suspend fun remove(packageName: String) {
        store.edit { LimitsCodec.remove(it, packageName) }
    }
}
