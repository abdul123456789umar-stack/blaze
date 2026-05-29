package com.blaze.agent.v2.logging

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

object TaskLogger {
    private const val PREFS_NAME = "TaskLogs"
    private const val KEY_LOGS = "logs"
    private const val MAX_LOGS = 1000

    fun log(context: Context, input: String, output: String) {
        // MVP: No-op for testing - just log
        android.util.Log.d("TaskLogger", "Log: input=$input, output=$output")
    }

    fun getLogs(context: Context): List<TaskLog> {
        return emptyList()
    }

    fun getLog(context: Context, uid: String): TaskLog? {
        return null
    }

    fun clearLogs(context: Context) {
        // MVP: No-op
    }
}

// Mock TaskLog data class for MVP
data class TaskLog(
    val uid: String = "",
    val timestamp: Long = 0,
    val input: String = "",
    val output: String = ""
)
