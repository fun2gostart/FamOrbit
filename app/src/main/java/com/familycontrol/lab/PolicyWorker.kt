package com.familycontrol.lab

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class PolicyWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val context = applicationContext
        if (ApiClient.registered(context)) {
            try {
                PolicySyncEngine.syncAndApplyCloudPolicy(context)
            } catch (e: Exception) {
                EventLog.record(context, "POLICY_WORKER_SYNC_ERROR ${e.message}")
            }
        }

        val prefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
        val policyVersion = ProtectionMonitor.policyVersion(context)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, LabDeviceAdminReceiver::class.java)
        val owner = dpm.isDeviceOwnerApp(context.packageName)

        prefs.edit().putLong("last_evaluation", System.currentTimeMillis()).apply()

        PolicySyncEngine.auditAndUnsuspendPackages(context)

        val usage: List<AppUsage> = if (hasUsageAccess(context))
            getTodayUsage(context) else emptyList()

        for (item in usage) {
            val enabled = prefs.getBoolean("enabled_${item.packageName}", false)
            val limit = prefs.getInt("limit_${item.packageName}", 30)
            val approvedExtra = ExtraTimeLedger.getRemainingExtraMinutes(context, item.packageName)
            val effectiveLimit = limit + approvedExtra
            if (enabled && item.minutes >= effectiveLimit) {
                if (owner) {
                    try {
                        val failures = dpm.setPackagesSuspended(admin, arrayOf(item.packageName), true)
                        if (failures.isEmpty())
                            EventLog.record(context, "AUTO_SUSPENDED ${item.packageName} ${item.minutes}m/${effectiveLimit}m base=${limit} extra=${approvedExtra}")
                        else
                            EventLog.record(context, "SUSPEND_FAILED ${item.packageName}: ${failures.joinToString()}")
                    } catch (e: Exception) {
                        EventLog.record(context, "SUSPEND_ERROR ${item.packageName}: ${e.message}")
                    }
                } else {
                    EventLog.record(context, "LIMIT_REACHED ${item.packageName} ${item.minutes}m/${effectiveLimit}m base=${limit} extra=${approvedExtra}; owner=false")
                }
            }
        }

        EventLog.record(context, "POLICY_EVALUATION v=$policyVersion owner=$owner apps=${usage.size}")
        return Result.success()
    }

    companion object {
        private const val NAME = "familycontrol_policy_evaluator"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PolicyWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
