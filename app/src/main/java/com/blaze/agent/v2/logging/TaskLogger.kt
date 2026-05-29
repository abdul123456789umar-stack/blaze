package com.blaze.agent.v2.logging

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

object TaskLogger {
    private const val PREFS_NAME = "TaskLogs"
    private const val KEY_LOGS = "logs"
    private const val MAX_LOGS = 1000

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun log(context: Context, input: String, output: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val logs = getLogs(context).toMutableList()

        val newLog = TaskLog(
            uid = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            input = input,
            output = output
        )

        logs.add(0, newLog)

        if (logs.size > MAX_LOGS) {
            logs.removeAt(logs.lastIndex)
        }

        saveLogs(prefs, logs)
    }

    fun getLogs(context: Context): List<TaskLog> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val logsJson = prefs.getString(KEY_LOGS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<TaskLog>>(logsJson)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getLog(context: Context, uid: String): TaskLog? {
        return getLogs(context).find { it.uid == uid }
    }

    fun clearLogs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_LOGS).apply()
    }

    private fun saveLogs(prefs: SharedPreferences, logs: List<TaskLog>) {
        val logsJson = json.encodeToString(logs)
        prefs.edit().putString(KEY_LOGS, logsJson).apply()
    }
}
