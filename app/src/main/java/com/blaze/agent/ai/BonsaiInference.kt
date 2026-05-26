package com.blaze.agent.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

class BonsaiInference(private val context: Context) {

    companion object {
        private const val TAG = "BlazeBonsai"
        private const val LIB_NAME = "blaze_llama"
        private const val N_CTX = 512
        private const val N_THREADS = 4
        private const val MAX_TOKENS = 512
        private const val UNLOAD_TIMEOUT_MS = 30_000L
        private var libLoaded = false

        fun tryLoadLibrary(): Boolean {
            if (libLoaded) return true
            return try { System.loadLibrary(LIB_NAME); libLoaded = true; true }
            catch (e: UnsatisfiedLinkError) { false }
        }
    }

    private val inferenceDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(inferenceDispatcher + SupervisorJob())
    private var unloadJob: Job? = null
    private var modelPath: String? = null

    private external fun loadModel(modelPath: String, nCtx: Int, nThreads: Int): Int
    private external fun complete(prompt: String, maxTokens: Int): String
    private external fun unloadModel()
    private external fun isLoaded(): Boolean

    suspend fun complete(prompt: String): String = withContext(inferenceDispatcher) {
        if (!tryLoadLibrary()) throw IllegalStateException("Bonsai native library not loaded")
        ensureModelLoaded()
        scheduleUnload()
        Log.d(TAG, "Running Bonsai inference (${prompt.length} chars)")
        complete(prompt, MAX_TOKENS)
    }

    fun preloadIfReady() {
        if (BonsaiDownloader.modelExists && tryLoadLibrary()) {
            scope.launch { ensureModelLoaded(); scheduleUnload() }
        }
    }

    fun forceUnload() {
        scope.launch { if (libLoaded && isLoaded()) { unloadModel(); Log.d(TAG, "Force unloaded") } }
    }

    private fun ensureModelLoaded() {
        if (!libLoaded || isLoaded()) return
        val path = BonsaiDownloader.getModelFile(context).absolutePath
        val result = loadModel(path, N_CTX, N_THREADS)
        if (result != 1) throw IllegalStateException("Failed to load Bonsai model (JNI=$result)")
        modelPath = path
        Log.d(TAG, "Bonsai model loaded into memory")
    }

    private fun scheduleUnload() {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(UNLOAD_TIMEOUT_MS)
            if (libLoaded && isLoaded()) { Log.d(TAG, "Unloading Bonsai after inactivity"); unloadModel() }
        }
    }
}
