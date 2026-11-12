package com.gemini.filemanager.core.smb

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SmbPlaybackRecord(
    val mediaKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis()
)

class SmbPlaybackStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun getSavedPosition(mediaKey: String): Long {
        if (mediaKey.isBlank()) return 0L
        val record = loadAll().find { it.mediaKey == mediaKey } ?: return 0L
        if (!isValidResumeRecord(record.positionMs, record.durationMs)) return 0L
        return record.positionMs
    }

    @Synchronized
    fun saveProgress(mediaKey: String, positionMs: Long, durationMs: Long) {
        if (mediaKey.isBlank() || durationMs <= 0L) return
        val existing = loadAll().filterNot { it.mediaKey == mediaKey }.toMutableList()
        if (isValidResumeRecord(positionMs, durationMs)) {
            existing.add(
                0,
                SmbPlaybackRecord(
                    mediaKey = mediaKey,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        val trimmed = existing
            .sortedByDescending { it.updatedAt }
            .take(MAX_RECORDS)
        persist(trimmed)
    }

    @Synchronized
    fun clearProgress(mediaKey: String) {
        if (mediaKey.isBlank()) return
        val filtered = loadAll().filterNot { it.mediaKey == mediaKey }
        persist(filtered)
    }

    private fun loadAll(): List<SmbPlaybackRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<SmbPlaybackRecord>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persist(records: List<SmbPlaybackRecord>) {
        prefs.edit().putString(KEY_RECORDS, json.encodeToString(records)).apply()
    }

    companion object {
        private const val PREFS_NAME = "smb_playback_history"
        private const val KEY_RECORDS = "records_v1"
        private const val MAX_RECORDS = 200
        private const val MIN_RESUME_POSITION_MS = 5_000L
        private const val MIN_MEDIA_DURATION_MS = 15_000L

        internal fun isValidResumeRecord(positionMs: Long, durationMs: Long): Boolean {
            if (durationMs < MIN_MEDIA_DURATION_MS) return false
            if (positionMs < MIN_RESUME_POSITION_MS) return false
            val nearEndThreshold = (durationMs * 0.95).toLong().coerceAtLeast(durationMs - 10_000L)
            return positionMs < nearEndThreshold
        }
    }
}
