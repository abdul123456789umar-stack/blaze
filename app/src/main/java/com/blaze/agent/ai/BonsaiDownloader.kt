package com.blaze.agent.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class BonsaiDownloader(private val context: Context) {

    companion object {
        private const val TAG = "BlazeBonsaiDownloader"
        private const val MODEL_URL = "https://huggingface.co/PrismML/bonsai-8b-GGUF/resolve/main/bonsai-8b-q4_k_m.gguf"
        private const val MODEL_FILENAME = "bonsai-8b.gguf"
        private const val MIN_FREE_SPACE = 1_500_000_000L
        private const val BUFFER_SIZE = 8 * 1024
        val modelExists: Boolean get() = _modelFile != null && _modelFile!!.exists() && _modelFile!!.length() > 0
        private var _modelFile: File? = null
        fun getModelFile(context: Context): File {
            val dir = File(context.filesDir, "models").also { it.mkdirs() }
            return File(dir, MODEL_FILENAME).also { _modelFile = it }
        }
    }

    sealed class DownloadState {
        object Idle : DownloadState()
        object CheckingStorage : DownloadState()
        data class Downloading(val progressPercent: Int, val downloadedMb: Float, val totalMb: Float, val speedKbps: Float) : DownloadState()
        object Verifying : DownloadState()
        object Complete : DownloadState()
        data class Failed(val reason: String) : DownloadState()
        object AlreadyExists : DownloadState()
    }

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state
    private var downloadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun isModelReady(): Boolean = getModelFile(context).let { it.exists() && it.length() > 100_000_000L }

    fun download(onComplete: () -> Unit = {}, onFailed: (String) -> Unit = {}) {
        val modelFile = getModelFile(context)
        if (isModelReady()) { _state.value = DownloadState.AlreadyExists; onComplete(); return }
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            try { runDownload(modelFile, onComplete, onFailed) }
            catch (e: CancellationException) { _state.value = DownloadState.Idle }
            catch (e: Exception) { val r = e.message ?: "Unknown error"; _state.value = DownloadState.Failed(r); onFailed(r) }
        }
    }

    fun cancel() { downloadJob?.cancel(); downloadJob = null }

    private suspend fun runDownload(modelFile: File, onComplete: () -> Unit, onFailed: (String) -> Unit) {
        _state.value = DownloadState.CheckingStorage
        val freeSpace = context.filesDir.freeSpace
        if (freeSpace < MIN_FREE_SPACE) {
            val r = "Not enough storage. Need 1.5GB free, only ${freeSpace/1_000_000}MB available."
            _state.value = DownloadState.Failed(r); onFailed(r); return
        }
        val existingBytes = if (modelFile.exists()) modelFile.length() else 0L
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000; readTimeout = 60_000
            setRequestProperty("User-Agent", "BlazeAgent/1.0")
            if (existingBytes > 0) setRequestProperty("Range", "bytes=$existingBytes-")
            connect()
        }
        val responseCode = connection.responseCode
        val isResume = responseCode == HttpURLConnection.HTTP_PARTIAL
        val isFresh = responseCode == HttpURLConnection.HTTP_OK
        if (!isResume && !isFresh) {
            val r = "Server returned HTTP $responseCode"
            _state.value = DownloadState.Failed(r); onFailed(r); connection.disconnect(); return
        }
        val contentLength = connection.contentLengthLong
        val totalBytes = if (isResume) existingBytes + contentLength else contentLength
        val totalMb = totalBytes.toFloat() / 1_000_000f
        val inputStream = connection.inputStream
        val outputStream = FileOutputStream(modelFile, isResume)
        val buffer = ByteArray(BUFFER_SIZE)
        var downloadedBytes = existingBytes
        var lastUpdate = System.currentTimeMillis(); var lastBytes = existingBytes
        try {
            while (true) {
                ensureActive()
                val read = inputStream.read(buffer); if (read == -1) break
                outputStream.write(buffer, 0, read); downloadedBytes += read
                val now = System.currentTimeMillis()
                if (now - lastUpdate >= 500) {
                    val elapsed = (now - lastUpdate) / 1000f
                    val speed = ((downloadedBytes - lastBytes) / 1024f) / elapsed
                    val pct = if (totalBytes > 0) ((downloadedBytes.toFloat() / totalBytes) * 100).toInt().coerceIn(0,100) else 0
                    _state.value = DownloadState.Downloading(pct, downloadedBytes/1_000_000f, totalMb, speed)
                    lastUpdate = now; lastBytes = downloadedBytes
                }
            }
        } finally { outputStream.flush(); outputStream.close(); inputStream.close(); connection.disconnect() }
        _state.value = DownloadState.Verifying
        val finalSize = modelFile.length()
        if (finalSize < 100_000_000L) {
            val r = "Downloaded file too small (${finalSize/1_000_000}MB). Possibly corrupted."
            modelFile.delete(); _state.value = DownloadState.Failed(r); onFailed(r); return
        }
        _state.value = DownloadState.Complete; onComplete()
    }
}
