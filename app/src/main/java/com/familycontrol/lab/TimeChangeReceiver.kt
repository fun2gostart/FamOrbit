package com.familycontrol.lab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                ProtectionMonitor.markClockChange(context)
                PolicyWorker.schedule(context)
                HeartbeatWorker.schedule(context)
            }
        }
    }
}
