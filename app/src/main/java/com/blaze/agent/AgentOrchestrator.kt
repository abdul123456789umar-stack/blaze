package com.blaze.agent

import android.content.Context
import android.util.Log
import com.blaze.agent.ai.LLMRouter
import com.blaze.agent.state.AppState
import com.blaze.agent.state.AppStateManager
import com.blaze.agent.task.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AgentOrchestrator(
    private val context: Context,
    private val llmRouter: LLMRouter,
    private val getScreenContent: () -> String,
    private val executeAction: suspend (String) -> Unit
) {
    companion object {
        private const val TAG = "BlazeOrchestrator"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val classifier = TaskClassifier(llmRouter)
    private val taskManager = BackgroundTaskManager(
        context = context,
        llmRouter = llmRouter,
        getScreenContent = getScreenContent,
        executeAgentAction = executeAction
    )

    fun handleCommand(userCommand: String) {
        scope.launch {
            try {
                AppStateManager.transitionTo(AppState.ACTIVE)
                Log.d(TAG, "Handling command: $userCommand")

                if (isStopCommand(userCommand)) {
                    handleStopCommand(userCommand)
                    return@launch
                }

                val classification = classifier.classify(userCommand)
                Log.d(TAG, "Classification: ${classification.taskType} — ${classification.reasoning}")

                when (classification.taskType) {
                    TaskType.ONE_SHOT -> {
                        executeAction(userCommand)
                        AppStateManager.transitionTo(AppState.SLEEP)
                    }
                    TaskType.MONITORING -> {
                        val task = MonitoringTask(
                            description = userCommand,
                            triggerCondition = classification.triggerCondition ?: "The condition described in the task",
                            actionToExecute = classification.actionToExecute ?: userCommand,
                            isRecurring = classification.isRecurring,
                            checkIntervalMs = classification.checkIntervalMs
                        )
                        taskManager.startTask(task)
                        speakConfirmation(userCommand, classification)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle command: $userCommand", e)
                AppStateManager.transitionTo(AppState.SLEEP)
            }
        }
    }

    private fun isStopCommand(command: String): Boolean {
        val lower = command.lowercase()
        return lower.contains("stop") || lower.contains("cancel") ||
                lower.contains("stop monitoring") || lower.contains("stop watching")
    }

    private fun handleStopCommand(command: String) {
        val lower = command.lowercase()
        when {
            lower.contains("all") || lower.contains("everything") -> taskManager.stopAllTasks()
            else -> {
                val active = taskManager.getActiveTasks()
                val match = active.firstOrNull { task ->
                    command.lowercase().split(" ").any { word ->
                        word.length > 3 && task.description.lowercase().contains(word)
                    }
                }
                if (match != null) taskManager.stopTask(match.id)
                else active.lastOrNull()?.let { taskManager.stopTask(it.id) }
            }
        }
        if (!taskManager.hasActiveTasks()) AppStateManager.transitionTo(AppState.SLEEP)
    }

    private fun speakConfirmation(command: String, classification: TaskClassification) {
        val interval = classification.checkIntervalMs / 1000
        val type = if (classification.isRecurring) "continuously" else "until first match"
        Log.d(TAG, "Monitoring started: checking every ${interval}s, $type")
    }

    fun getActiveTasks(): List<MonitoringTask> = taskManager.getActiveTasks()
    fun stopAllMonitoring() = taskManager.stopAllTasks()
}
