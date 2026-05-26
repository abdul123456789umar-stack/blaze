package com.blaze.agent.task

import android.util.Log
import com.blaze.agent.ai.LLMRouter
import org.json.JSONObject

class TaskClassifier(private val llmRouter: LLMRouter) {

    companion object { private const val TAG = "BlazeTaskClassifier" }

    suspend fun classify(userCommand: String): TaskClassification {
        val prompt = buildPrompt(userCommand)
        return try {
            val raw = llmRouter.complete(prompt)
            parseResponse(raw)
        } catch (e: Exception) {
            Log.e(TAG, "Classification failed, defaulting to ONE_SHOT", e)
            TaskClassification(taskType = TaskType.ONE_SHOT)
        }
    }

    private fun buildPrompt(command: String): String = """
        You are a task router for an Android AI agent.

        Analyze the user command below and classify it as either:
        - ONE_SHOT: execute once and done. (e.g. "Send WhatsApp to Mom", "Open YouTube")
        - MONITORING: keep running in the background, watching/waiting for something.
          (e.g. "Watch my Instagram DMs and reply to business messages",
                "Alert me when my Uber arrives",
                "Keep checking my email for a reply from John")

        For MONITORING tasks, also determine:
        - triggerCondition: what specifically to watch for on screen
        - actionToExecute: what to do when the condition is met
        - isRecurring: true if the task should keep monitoring after each action
        - checkIntervalSeconds: how often to check (minimum 15, maximum 300 seconds)

        User command: "$command"

        Respond ONLY with valid JSON:
        {
          "taskType": "ONE_SHOT" or "MONITORING",
          "triggerCondition": "what to watch for, or null",
          "actionToExecute": "what to do when triggered, or null",
          "isRecurring": true or false,
          "checkIntervalSeconds": number,
          "reasoning": "one sentence"
        }
    """.trimIndent()

    private fun parseResponse(raw: String): TaskClassification {
        return try {
            val json = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(json)
            val taskType = when (obj.getString("taskType")) {
                "MONITORING" -> TaskType.MONITORING
                else -> TaskType.ONE_SHOT
            }
            TaskClassification(
                taskType = taskType,
                triggerCondition = obj.optString("triggerCondition").takeIf { it != "null" && it.isNotBlank() },
                actionToExecute = obj.optString("actionToExecute").takeIf { it != "null" && it.isNotBlank() },
                isRecurring = obj.optBoolean("isRecurring", false),
                checkIntervalMs = (obj.optInt("checkIntervalSeconds", 30).coerceIn(15, 300)) * 1000L,
                reasoning = obj.optString("reasoning", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse classification JSON: $raw", e)
            TaskClassification(taskType = TaskType.ONE_SHOT)
        }
    }
}
