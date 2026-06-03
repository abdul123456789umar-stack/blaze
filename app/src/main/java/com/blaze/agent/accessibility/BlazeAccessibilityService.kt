package com.blaze.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.blaze.agent.AgentOrchestrator
import com.blaze.agent.ai.BonsaiDownloader
import com.blaze.agent.ai.LLMRouter
import com.blaze.agent.state.AppState
import com.blaze.agent.state.AppStateManager
import com.blaze.agent.voice.WakeWordDetector
import java.io.ByteArrayOutputStream

class BlazeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BlazeA11y"
        private const val EVENT_DEBOUNCE_MS = 300L
        var instance: BlazeAccessibilityService? = null
            private set
    }

    private var orchestrator: AgentOrchestrator? = null
    private var wakeWordDetector: WakeWordDetector? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastEventTime = 0L
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = EVENT_DEBOUNCE_MS
        }
        val llmRouter = LLMRouter(this, getGeminiApiKey())
        orchestrator = AgentOrchestrator(
            context = this,
            llmRouter = llmRouter,
            getScreenContent = ::getScreenContent,
            executeAction = ::executeAction
        )
        AppStateManager.init(this)
        AppStateManager.onEnterSleep = ::onEnterSleep
        AppStateManager.onEnterListening = ::onEnterListening
        AppStateManager.onEnterActive = ::onEnterActive
        AppStateManager.onEnterMonitoring = ::onEnterMonitoring
        AppStateManager.transitionTo(AppState.SLEEP)
        val downloader = BonsaiDownloader(this)
        if (!downloader.isModelReady()) {
            downloader.download(
                onComplete = { Log.d(TAG, "Bonsai model ready") },
                onFailed = { Log.w(TAG, "Bonsai download failed: $it") }
            )
        }
        getScreenDimensions()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        wakeWordDetector?.stop(); releaseScreenCapture(); instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() { Log.w(TAG, "Interrupted") }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val state = AppStateManager.current()
        if (state == AppState.SLEEP || state == AppState.LISTENING) return
        val now = System.currentTimeMillis()
        if (now - lastEventTime < EVENT_DEBOUNCE_MS) return
        lastEventTime = now
        Log.v(TAG, "Screen changed: ${event?.eventType}")
    }

    fun getScreenContent(): String {
        val treeText = getAccessibilityTreeText()
        if (treeText.length > 50) {
            return buildString {
                appendLine("=== SCREEN CONTENT (accessibility tree) ===")
                appendLine("Package: ${rootInActiveWindow?.packageName}")
                appendLine(treeText)
            }
        }
        return "=== SCREEN (visual) — use completeWithImage() for this ==="
    }

    fun getScreenshot(): ByteArray? {
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val bitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            bitmap.recycle()
            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 75, out)
            cropped.recycle()
            out.toByteArray()
        } finally { image.close() }
    }

    private fun getAccessibilityTreeText(): String {
        val root = rootInActiveWindow ?: return ""
        val builder = StringBuilder()
        try { walkNode(root, builder, 0) } finally { root.recycle() }
        return builder.toString()
    }

    private fun walkNode(node: AccessibilityNodeInfo?, builder: StringBuilder, depth: Int) {
        if (node == null || depth > 15) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val hint = node.hintText?.toString()?.trim()
        when {
            !text.isNullOrEmpty() -> builder.appendLine(text)
            !desc.isNullOrEmpty() -> builder.appendLine("[$desc]")
            !hint.isNullOrEmpty() -> builder.appendLine("($hint)")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            try { walkNode(child, builder, depth + 1) } finally { child?.recycle() }
        }
    }

    private suspend fun executeAction(actionDescription: String) {
        Log.d(TAG, "Executing: $actionDescription")
        // TODO: Wire to Panda's existing ActionPerformer
    }

    private fun onEnterSleep() {
        releaseScreenCapture()
        wakeWordDetector = WakeWordDetector(this) { startFullSTT() }
        wakeWordDetector?.start()
    }

    private fun onEnterListening() { wakeWordDetector?.stop(); wakeWordDetector = null }
    private fun onEnterActive() { Log.d(TAG, "ACTIVE") }
    private fun onEnterMonitoring() { wakeWordDetector?.stop(); wakeWordDetector = null }

    private fun getScreenDimensions() {
        screenWidth = resources.displayMetrics.widthPixels
        screenHeight = resources.displayMetrics.heightPixels
    }

    fun initScreenCapture(projection: MediaProjection) {
        mediaProjection = projection
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "BlazeCapture", screenWidth, screenHeight,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun releaseScreenCapture() {
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.stop(); mediaProjection = null
    }

    private fun startFullSTT() {
        Log.d(TAG, "Full STT started")
        // TODO: Start SpeechRecognizer → orchestrator?.handleCommand(result)
    }

    private fun getGeminiApiKey(): String {
        return try {
            val clazz = Class.forName("com.blaze.agent.BuildConfig")
            clazz.getField("GEMINI_API_KEY").get(null) as String
        } catch (e: Exception) { "" }
    }
}
