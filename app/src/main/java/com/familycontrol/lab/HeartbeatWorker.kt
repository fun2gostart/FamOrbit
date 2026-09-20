package com.familycontrol.lab

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class HeartbeatWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val context = applicationContext
        val prefs = context.getSharedPreferences("heartbeat", Context.MODE_PRIVATE)
        val connectivity =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkAvailable = connectivity.activeNetwork != null
        val previousNetwork = prefs.getBoolean("network_available", networkAvailable)

        if (previousNetwork != networkAvailable) {
            EventLog.record(
                context,
                if (networkAvailable) "NETWORK_RECOVERED" else "NETWORK_OFFLINE"
            )
        }

        val activeRoutine = ScheduleEngine.activeRoutine(context)
        var cloudSyncStatus = "Skipped (Offline)"
        if (networkAvailable && ApiClient.registered(context)) {
            try {
                val syncResponse = ApiClient.getSync(context)
                if (syncResponse.ok) {
                    cloudSyncStatus = "Synced"
                    // Parse sync response version if available
                    val json = org.json.JSONObject(syncResponse.body)
                    if (json.has("policy")) {
                        val policyObj = json.getJSONObject("policy")
                        val version = policyObj.optInt("version", 0)
                        if (version > 0) {
                            ApiClient.acknowledgeSync(context, version)
                        }
                    }
                }
                val lockResponse = ApiClient.getInstantLock(context)
                if (lockResponse.ok) {
                    val lockJson = org.json.JSONObject(lockResponse.body)
                    val isLocked = lockJson.optBoolean("locked", false)
                    context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
                        .edit().putBoolean("instant_lock_active", isLocked).apply()
                }
            } catch (e: Exception) {
                cloudSyncStatus = "Error: ${e.message}"
            }
        }

        prefs.edit()
            .putLong("last_heartbeat", System.currentTimeMillis())
            .putBoolean("network_available", networkAvailable)
            .putString("cloud_sync_status", cloudSyncStatus)
            .putString("active_routine", activeRoutine?.name ?: "None")
            .putInt("policy_version", ProtectionMonitor.policyVersion(context))
            .apply()

        EventLog.record(
            context,
            "HEARTBEAT network=$networkAvailable sync=$cloudSyncStatus routine=${activeRoutine?.name ?: "None"}"
        )
        return Result.success()
    }

    companion object {
        private const val NAME = "familycontrol_heartbeat"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
