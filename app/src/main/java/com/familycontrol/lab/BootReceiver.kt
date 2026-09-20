package com.familycontrol.lab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            PolicyWorker.schedule(context)
            HeartbeatWorker.schedule(context)
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, EnforcementService::class.java)
                )
                EventLog.record(context, "FAST_ENFORCEMENT_SERVICE_STARTED_AFTER_BOOT")
            } catch (e: Exception) {
                EventLog.record(context, "FAST_ENFORCEMENT_BOOT_START_ERROR ${e.message}")
            }
            EventLog.record(context, "BOOT_COMPLETED")
            EventLog.record(context, "BOOT_RECOVERY_SCHEDULED")
        }
    }
}
