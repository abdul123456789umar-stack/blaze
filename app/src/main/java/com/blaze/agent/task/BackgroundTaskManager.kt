package com.blaze.agent.task

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.blaze.agent.ai.LLMRouter
import com.blaze.agent.state.AppState
import com.blaze.agent.state.AppStateManager
import kotlinx.coroutines.*
import org.json.JSONObject

class BackgroundTaskManager(
    private val context: Context,
    private val llmRouter: LLMRouter,
    private val getScreenContent: () -> String,
    private val executeAgentAction: suspend (String) -> Unit
) {
    companion object {
        private const val TAG = "BlazeTaskManager"
        private const val CHANNEL_ID = "blaze_monitoring"
        private const val NOTIFICATION_ID = 1001
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tasks = mutableMapOf<String, Pair<MonitoringTask, Job>>()

    init { createNotificationChannel() }

    fun startTask(task: MonitoringTask) {
        if (tasks.containsKey(task.id)) return
        Log.d(TAG, "Starting monitoring task: ${task.description}")
        val job = scope.launch { runMonitoringLoop(task) }
        tasks[task.id] = Pair(task, job)
        AppStateManager.transitionTo(AppState.MONITORING)
        updateNotification()
    }

    fun stopTask(taskId: String) {
        val (task, job) = tasks[taskId] ?: return
        job.cancel()
        task.status = TaskStatus.STOPPED
        tasks.remove(taskId)
        handleTasksExhausted()
    }

    fun stopAllTasks() {
        tasks.values.forEach { (task, job) -> job.cancel(); task.status = TaskStatus.STOPPED }
        tasks.clear()
        handleTasksExhausted()
    }

    fun getActiveTasks(): List<MonitoringTask> = tasks.values.map { it.first }.filter { it.isAlive }
    fun hasActiveTasks(): Boolean = tasks.isNotEmpty()

    private suspend fun runMonitoringLoop(task: MonitoringTask) {
        while (task.status == TaskStatus.RUNNING) {
            try {
                delay(task.checkIntervalMs)
                if (task.status != TaskStatus.RUNNING) break
                task.markChecked()
                val result = checkCondition(task)
                if (result.conditionMet) {
                    task.markTriggered()
                    withContext(Dispatchers.Main) { AppStateManager.transitionTo(AppState.ACTIVE) }
                    executeAgentAction(buildActionPrompt(task, result))
                    withContext(Dispatchers.Main) { AppStateManager.transitionTo(AppState.MONITORING) }
                    if (!task.isRecurring) {
                        task.status = TaskStatus.COMPLETED
                        cleanUpTask(task.id)
                        break
                    }
                }
            } catch (e: CancellationException) { break
            } catch (e: Exception) {
                Log.e(TAG, "Error in monitoring loop for ${task.description}", e)
                delay(task.checkIntervalMs)
            }
        }
    }

    private suspend fun checkCondition(task: MonitoringTask): ConditionCheckResult {
        val screenContent = getScreenContent()
        val prompt = """
            You are monitoring a user's Android screen for a specific condition.
            TASK: ${task.description}
            CONDITION TO WATCH FOR: ${task.triggerCondition}
            SCREEN CONTENT RIGHT NOW:
            $screenContent
            Is the trigger condition currently visible or met on the screen?
            This task has triggered ${task.triggerCount} time(s) so far.
            Respond ONLY with valid JSON:
            { "conditionMet": true or false, "specificAction": "exact action or null", "reasoning": "one sentence" }
        """.trimIndent()
        return try {
            val raw = llmRouter.complete(prompt)
            parseConditionResult(raw)
        } catch (e: Exception) {
            Log.e(TAG, "Condition check failed", e)
            ConditionCheckResult(conditionMet = false, specificAction = null)
        }
    }

    private fun buildActionPrompt(task: MonitoringTask, result: ConditionCheckResult): String =
        "Execute this action: ${result.specificAction ?: task.actionToExecute}\nContext: ${task.description}\nReason triggered: ${result.reasoning}"

    private fun parseConditionResult(raw: String): ConditionCheckResult {
        return try {
            val json = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(json)
            ConditionCheckResult(
                conditionMet = obj.getBoolean("conditionMet"),
                specificAction = obj.optString("specificAction").takeIf { it != "null" && it.isNotBlank() },
                reasoning = obj.optString("reasoning", "")
            )
        } catch (e: Exception) {
            ConditionCheckResult(conditionMet = false, specificAction = null)
        }
    }

    private fun cleanUpTask(taskId: String) {
        tasks[taskId]?.second?.cancel()
        tasks.remove(taskId)
        handleTasksExhausted()
    }

    private fun handleTasksExhausted() {
        if (tasks.isEmpty()) {
            AppStateManager.transitionTo(AppState.SLEEP)
            dismissNotification()
        } else updateNotification()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Blaze Background Tasks", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows when Blaze is monitoring something in the background"
            setShowBadge(false)
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun updateNotification() {
        val activeCount = tasks.size
        val taskNames = tasks.values.take(3).joinToString("\n") { "• ${it.first.description.take(60)}" }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Blaze is watching ($activeCount task${if (activeCount != 1) "s" else ""})")
            .setStyle(NotificationCompat.BigTextStyle().bigText(taskNames))
            .setOngoing(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
    }

    private fun dismissNotification() {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
    }
}
