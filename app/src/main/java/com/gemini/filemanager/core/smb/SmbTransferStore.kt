package com.gemini.filemanager.core.smb

import android.content.Context
import com.gemini.filemanager.data.model.SmbTransferStatus
import com.gemini.filemanager.data.model.SmbTransferTask
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SmbTransferStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun loadTasks(): List<SmbTransferTask> {
        val raw = prefs.getString(KEY_TASKS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<SmbTransferTask>>(raw).map { task ->
                if (task.status == SmbTransferStatus.TRANSFERRING || task.status == SmbTransferStatus.WAITING) {
                    task.copy(
                        status = SmbTransferStatus.PAUSED,
                        speed = "",
                        errorMessage = null
                    )
                } else {
                    task
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveTasks(tasks: List<SmbTransferTask>) {
        // 保留最近 100 条任务记录，防止历史无限膨胀
        val bounded = if (tasks.size > 100) tasks.takeLast(100) else tasks
        prefs.edit().putString(KEY_TASKS, json.encodeToString(bounded)).apply()
    }

    companion object {
        private const val PREFS_NAME = "qmanager_smb_transfer_prefs"
        private const val KEY_TASKS = "smb_transfer_tasks"
    }
}

