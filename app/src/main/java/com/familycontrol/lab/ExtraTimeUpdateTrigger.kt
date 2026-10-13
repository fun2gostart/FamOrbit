package com.familycontrol.lab

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableLongStateOf

/**
 * Global reactive event trigger for immediate extra time UI updates.
 * Dispatches changes to the Android Main Looper so Jetpack Compose states
 * and LaunchedEffects update instantly with zero polling latency.
 */
object ExtraTimeUpdateTrigger {
    val version = mutableLongStateOf(0L)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun notifyChange() {
        val now = System.currentTimeMillis()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            version.longValue = now
        } else {
            mainHandler.post {
                version.longValue = now
            }
        }
    }
}
