package com.blaze.agent.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.blaze.agent.state.AppState
import com.blaze.agent.state.AppStateManager
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WakeWordDetector(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {
    companion object {
        private const val TAG = "BlazeWakeWord"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_MS = 30
        private const val CHUNK_SIZE = SAMPLE_RATE * CHUNK_MS / 1000 * 2
        private const val ENERGY_THRESHOLD = 800.0f
        private const val SPEECH_FRAMES_REQUIRED = 4
        private val WAKE_PHRASES = listOf("hey blaze", "ok blaze", "blaze", "hi blaze")
    }

    private var audioRecord: AudioRecord? = null
    private var detectorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var consecutiveSpeechFrames = 0
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufSize = maxOf(minBuf, CHUNK_SIZE * 4)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, bufSize)
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) { Log.e(TAG, "AudioRecord init failed"); return }
        audioRecord?.startRecording()
        detectorJob = scope.launch {
            val buffer = ByteArray(CHUNK_SIZE)
            while (isRunning && isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read <= 0) continue
                val energy = computeEnergy(buffer, read)
                if (energy > ENERGY_THRESHOLD) {
                    consecutiveSpeechFrames++
                    if (consecutiveSpeechFrames >= SPEECH_FRAMES_REQUIRED) {
                        consecutiveSpeechFrames = 0
                        withContext(Dispatchers.Main) {
                            AppStateManager.transitionTo(AppState.LISTENING)
                            onWakeWordDetected()
                        }
                        stop()
                    }
                } else { if (consecutiveSpeechFrames > 0) consecutiveSpeechFrames-- }
            }
        }
    }

    fun stop() {
        isRunning = false; consecutiveSpeechFrames = 0
        detectorJob?.cancel(); detectorJob = null
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
    }

    private fun computeEnergy(buffer: ByteArray, length: Int): Float {
        val shortBuf = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sum = 0.0; val count = length / 2
        repeat(count) { val s = shortBuf.get().toDouble(); sum += s * s }
        return if (count > 0) Math.sqrt(sum / count).toFloat() else 0f
    }
}
