package com.blaze.agent.v2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.blaze.agent.R
import com.blaze.agent.utilities.ApiKeyManager
import com.blaze.agent.api.Eyes
import com.blaze.agent.api.Finger
import com.blaze.agent.overlay.OverlayDispatcher
import com.blaze.agent.utilities.VisualFeedbackManager
import com.blaze.agent.overlay.OverlayManager
import com.blaze.agent.v2.actions.ActionExecutor
import com.blaze.agent.v2.fs.FileSystem
import com.blaze.agent.v2.llm.GeminiApi
import com.blaze.agent.v2.message_manager.MemoryManager
import com.blaze.agent.v2.perception.Perception
import com.blaze.agent.v2.perception.SemanticParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue

class AgentService : Service() {

    private val TAG = "AgentService"
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val visualFeedbackManager by lazy { VisualFeedbackManager.getInstance(this) }

    private val taskQueue: Queue<String> = ConcurrentLinkedQueue()
    private lateinit var agent: Agent
    private lateinit var settings: AgentSettings
    private lateinit var fileSystem: FileSystem
    private lateinit var memoryManager: MemoryManager
    private lateinit var perception: Perception
    private lateinit var llmApi: GeminiApi
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var overlayManager: OverlayManager


    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "AgentServiceChannelV2"
        private const val NOTIFICATION_ID = 14
        private const val EXTRA_TASK = "com.blaze.agent.v2.EXTRA_TASK"
        private const val ACTION_STOP_SERVICE = "com.blaze.agent.v2.ACTION_STOP_SERVICE"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var currentTask: String? = null
            private set

        fun stop(context: Context) {
            Log.d("AgentService", "External stop request received.")
            val intent = Intent(context, AgentService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }

        fun start(context: Context, task: String) {
            Log.d("AgentService", "Starting service with task: $task")
            val intent = Intent(context, AgentService::class.java).apply {
                putExtra(EXTRA_TASK, task)
            }
            context.startService(intent)
        }
    }
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service is being created.")
        overlayManager = OverlayManager.getInstance(this)
        OverlayDispatcher.clearAll()
        overlayManager.startObserving()

        visualFeedbackManager.showTtsWave()
        createNotificationChannel()

        settings = AgentSettings() 
        fileSystem = FileSystem(this)
        memoryManager = MemoryManager(this, "", fileSystem, settings)
        perception = Perception(Eyes(this), SemanticParser())
        llmApi = GeminiApi(
            "gemini-2.0-flash",
            apiKeyManager = ApiKeyManager,
            context = this,
            maxRetry = 10
        )
        actionExecutor = ActionExecutor(Finger(this))
        agent = Agent(
            settings,
            memoryManager,
            perception,
            llmApi,
            actionExecutor,
            fileSystem,
            this
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        intent?.getStringExtra(EXTRA_TASK)?.let {
            if (it.isNotBlank()) {
                taskQueue.add(it)
            }
        }

        if (!isRunning && taskQueue.isNotEmpty()) {
            serviceScope.launch {
                processTaskQueue()
            }
        }
        return START_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun processTaskQueue() {
        if (isRunning) return
        isRunning = true

        startForeground(NOTIFICATION_ID, createNotification("Agent is starting..."))
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        while (taskQueue.isNotEmpty()) {
            val task = taskQueue.poll() ?: continue
            currentTask = task
            notificationManager.notify(NOTIFICATION_ID, createNotification("Agent is running task: $task"))

            try {
                agent.run(task)
            } catch (e: Exception) {
                Log.e(TAG, "Task failed: $task", e)
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        OverlayDispatcher.clearAll()
        overlayManager.stopObserving()
        isRunning = false
        currentTask = null
        taskQueue.clear()
        serviceScope.cancel()
        visualFeedbackManager.hideTtsWave()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Agent Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val stopIntent = Intent(this, AgentService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Blaze Agent Active")
            .setContentText(contentText)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }
}
