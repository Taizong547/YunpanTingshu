package com.yunpan.tingshu.player.session

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.sessionDataStore: androidx.datastore.core.DataStore<Preferences> by
preferencesDataStore(name = "play_session")

/** 真机：DataStore 持久化会话 JSON。 */
class DataStoreSessionKv(private val app: Context) : SessionKv {
    private val key = stringPreferencesKey("session_json")

    override suspend fun get(): String? =
        app.sessionDataStore.data.first()[key]

    override suspend fun set(json: String?) {
        app.sessionDataStore.edit { p -> if (json == null) p.remove(key) else p[key] = json }
    }
}
