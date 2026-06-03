package com.blaze.agent.state

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AppState { SLEEP, LISTENING, ACTIVE, MONITORING }

object AppStateManager {

    private const val TAG = "BlazeStateManager"
    private const val WAKE_LOCK_TAG = "Blaze:MonitoringWakeLock"

    private val _state = MutableStateFlow(AppState.SLEEP)
    val state: StateFlow<AppState> = _state

    private var wakeLock: PowerManager.WakeLock? = null
    private var context: Context? = null

    var onEnterSleep: (() -> Unit)? = null
    var onEnterListening: (() -> Unit)? = null
    var onEnterActive: (() -> Unit)? = null
    var onEnterMonitoring: (() -> Unit)? = null

    fun init(ctx: Context) {
        context = ctx.applicationContext
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        wakeLock?.setReferenceCounted(false)
    }

    fun current(): AppState = _state.value

    fun transitionTo(newState: AppState) {
        val old = _state.value
        if (old == newState) return
        Log.d(TAG, "State: $old → $newState")
        when (newState) {
            AppState.SLEEP -> { releaseWakeLock(); onEnterSleep?.invoke() }
            AppState.LISTENING -> { releaseWakeLock(); onEnterListening?.invoke() }
            AppState.ACTIVE -> { acquireWakeLock(5 * 60 * 1000L); onEnterActive?.invoke() }
            AppState.MONITORING -> { acquireWakeLock(8 * 60 * 60 * 1000L); onEnterMonitoring?.invoke() }
        }
        _state.value = newState
    }

    private fun acquireWakeLock(timeout: Long) {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock?.acquire(timeout)
        Log.d(TAG, "WakeLock acquired (timeout=${timeout}ms)")
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "WakeLock released")
        }
    }
}
