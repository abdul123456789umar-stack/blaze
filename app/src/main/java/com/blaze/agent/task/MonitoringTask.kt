package com.blaze.agent.task

import java.util.UUID

enum class TaskType { ONE_SHOT, MONITORING }

enum class TaskStatus { PENDING, RUNNING, PAUSED, COMPLETED, STOPPED }

data class MonitoringTask(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val triggerCondition: String,
    val actionToExecute: String,
    val isRecurring: Boolean = true,
    val checkIntervalMs: Long = 30_000L,
    val startedAt: Long = System.currentTimeMillis(),
    var status: TaskStatus = TaskStatus.RUNNING,
    var triggerCount: Int = 0,
    var lastCheckedAt: Long = 0L,
    var lastTriggeredAt: Long = 0L
) {
    val isAlive: Boolean get() = status == TaskStatus.RUNNING || status == TaskStatus.PAUSED
    fun markTriggered() { triggerCount++; lastTriggeredAt = System.currentTimeMillis() }
    fun markChecked() { lastCheckedAt = System.currentTimeMillis() }
}

data class ConditionCheckResult(
    val conditionMet: Boolean,
    val specificAction: String?,
    val reasoning: String = ""
)

data class TaskClassification(
    val taskType: TaskType,
    val triggerCondition: String? = null,
    val actionToExecute: String? = null,
    val isRecurring: Boolean = false,
    val checkIntervalMs: Long = 30_000L,
    val reasoning: String = ""
)
