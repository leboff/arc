package com.daydreamvr.player.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.daydreamvr.upnp.model.MediaServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI

/**
 * Persists the known-servers table (ARCHITECTURE.md §13). Only the fields needed
 * to re-probe a server at startup are kept; the live [MediaServer] is rebuilt by
 * `MediaServerDirectory` from a fresh description fetch.
 */
class ServerStore(context: Context) {

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("servers")
    private val store = context.applicationContext.dataStore
    private val key = stringPreferencesKey("servers.known")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class PersistedServer(
        val udn: String,
        val friendlyName: String,
        val descriptionUrl: String,
        val manual: Boolean,
        val lastSeen: Long,
    )

    val known = store.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<List<PersistedServer>>(it) }.getOrDefault(emptyList()) }
            ?: emptyList()
    }

    suspend fun descriptionUrls(): List<URI> =
        known.first().mapNotNull { runCatching { URI(it.descriptionUrl) }.getOrNull() }

    suspend fun remember(server: MediaServer, manual: Boolean) {
        val entry = PersistedServer(
            server.udn,
            server.friendlyName,
            server.descriptionUrl.toString(),
            manual,
            server.lastSeenEpochMs,
        )
        store.edit { prefs ->
            val current = prefs[key]?.let {
                runCatching { json.decodeFromString<List<PersistedServer>>(it) }.getOrDefault(emptyList())
            } ?: emptyList()
            val merged = (current.filterNot { it.udn == entry.udn } + entry).takeLast(MAX_SERVERS)
            prefs[key] = json.encodeToString(merged)
        }
    }

    suspend fun forgetAll() {
        store.edit { it.remove(key) }
    }

    private companion object {
        const val MAX_SERVERS = 32
    }
}
