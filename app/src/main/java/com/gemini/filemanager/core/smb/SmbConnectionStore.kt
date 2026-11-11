package com.gemini.filemanager.core.smb

import android.content.Context
import com.gemini.filemanager.data.model.SmbConnectionConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SmbConnectionStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun getConnections(): List<SmbConnectionConfig> {
        val raw = prefs.getString(KEY_CONNECTIONS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<SmbConnectionConfig>>(raw)
                .sortedByDescending { it.lastConnectedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveConnection(config: SmbConnectionConfig): List<SmbConnectionConfig> {
        val current = getConnections().toMutableList()
        val index = current.indexOfFirst { it.id == config.id }
        if (index >= 0) {
            current[index] = config
        } else {
            current.add(0, config)
        }
        persist(current)
        return current.sortedByDescending { it.lastConnectedAt }
    }

    fun markConnected(id: String): List<SmbConnectionConfig> {
        val current = getConnections().map {
            if (it.id == id) it.copy(lastConnectedAt = System.currentTimeMillis()) else it
        }
        persist(current)
        return current.sortedByDescending { it.lastConnectedAt }
    }

    fun deleteConnection(id: String): List<SmbConnectionConfig> {
        val updated = getConnections().filterNot { it.id == id }
        persist(updated)
        return updated
    }

    private fun persist(list: List<SmbConnectionConfig>) {
        prefs.edit().putString(KEY_CONNECTIONS, json.encodeToString(list)).apply()
    }

    companion object {
        private const val PREFS_NAME = "qmanager_smb_prefs"
        private const val KEY_CONNECTIONS = "saved_smb_connections"
    }
}

