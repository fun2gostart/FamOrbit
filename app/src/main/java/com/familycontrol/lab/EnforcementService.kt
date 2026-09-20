package com.familycontrol.lab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.app.usage.UsageEvents
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

/**
 * Visible foreground enforcement loop for the managed-device test mode.
 *
 * App limits are deterministic. Approved, app-specific extra-time requests are
 * fetched from the FamilyControl API and cached locally; the approved minutes
 * are added to that app's effective limit for the current day.
 */
class EnforcementService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var running = false
    private val lastEnforced = mutableMapOf<String, Boolean>()

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            executor.execute { evaluateNow() }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= 34)
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else 0
        )
        running = true
        EventLog.record(this, "FAST_ENFORCEMENT_SERVICE_STARTED")
        handler.post(loop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        EventLog.record(this, "FAST_ENFORCEMENT_SERVICE_STOPPED")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun evaluateNow() {
        if (ApiClient.registered(this)) {
            try {
                PolicySyncEngine.syncAndApplyCloudPolicy(this)
            } catch (e: Exception) {
                EventLog.record(this, "ENFORCEMENT_SYNC_ERROR ${e.message}")
            }
        }

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(packageName)) {
            updateNotification("Protection monitoring active (Cloud Synced)")
            return
        }

        val admin = ComponentName(this, LabDeviceAdminReceiver::class.java)
        val prefs = getSharedPreferences("parent_control", Context.MODE_PRIVATE)
        val defaultTargets = setOf(
            "com.instagram.android",
            "com.jio.jioPlay.tv",
            "com.netflix.mediaclient"
        )
        val customTargets = prefs.all.keys
            .filter { it.startsWith("enabled_") }
            .map { it.removePrefix("enabled_") }
            .toSet()
        val targets = (defaultTargets + customTargets).toList()

        val usage = getTodayUsage(this)
        val foreground = currentForegroundPackage()
        var actionCount = 0

        val instantPause = prefs.getBoolean("instant_pause_enabled", false)

        val activePreset = PresetModeEngine.getActivePreset(this)
        PolicySyncEngine.auditAndUnsuspendPackages(this)

        for (pkg in targets) {
            val enabled = prefs.getBoolean("enabled_$pkg", false)
            val baseLimit = prefs.getInt("limit_$pkg", 30)
            val approvedExtra = fetchApprovedExtraMinutes(pkg)
            val effectiveLimit = baseLimit + approvedExtra
            val baseMinutes = usage.firstOrNull { it.packageName == pkg }?.minutes ?: 0L
            val liveMinutes = liveSessionMinutes(pkg, baseMinutes, foreground)

            if (liveMinutes > baseLimit && approvedExtra > 0) {
                val extraConsumed = (liveMinutes - baseLimit).toInt()
                ExtraTimeLedger.recordConsumption(this, pkg, extraConsumed)
            }

            val category = AnalyticsEngine.categorizeApp(pkg)
            val restrictedByPreset = when (activePreset) {
                PresetModeEngine.MODE_STUDY -> category == "Social" || category == "Gaming" || category == "Entertainment"
                PresetModeEngine.MODE_BEDTIME -> category != "Education"
                PresetModeEngine.MODE_DINNER -> category == "Social" || category == "Gaming" || category == "Entertainment"
                else -> false
            }

            val isLearnFirstBlocked = FeatureToggleEngine.isCategoryBudgetsEnabled(this) &&
                    category == "Gaming" &&
                    CategoryBudgetEngine.isGamingBlockedByLearnFirst(this, usage)

            val restrictedByRoutine = ScheduleEngine.isAppRestrictedByRoutine(this, pkg)
            val reached = instantPause || restrictedByPreset || (enabled && liveMinutes >= effectiveLimit) || restrictedByRoutine || isLearnFirstBlocked

            val previous = lastEnforced[pkg]
            if (previous == reached && reached) continue

            try {
                val failures = dpm.setPackagesSuspended(admin, arrayOf(pkg), reached)
                if (failures.isEmpty()) {
                    actionCount++
                    lastEnforced[pkg] = reached
                    if (reached) {
                        EventLog.record(
                            this,
                            "AUTO_SUSPENDED $pkg ${liveMinutes}m/${effectiveLimit}m base=$baseLimit extra=$approvedExtra foreground=$foreground"
                        )
                    } else if (previous == true) {
                        EventLog.record(
                            this,
                            "AUTO_RESUMED $pkg ${liveMinutes}m/${effectiveLimit}m base=$baseLimit extra=$approvedExtra"
                        )
                    }
                } else {
                    EventLog.record(this, "SUSPEND_FAILED $pkg: ${failures.joinToString()}")
                }
            } catch (e: Exception) {
                EventLog.record(this, "ENFORCEMENT_ERROR $pkg: ${e.message}")
            }
        }

        if (actionCount > 0) updateNotification("Policy enforcement action applied")
    }

    private fun fetchApprovedExtraMinutes(packageName: String): Int {
        // Asynchronously sync allowances from remote without blocking local evaluation
        executor.execute { syncAllowancesRemote(packageName) }
        return ExtraTimeLedger.getRemainingExtraMinutes(this, packageName)
    }

    private fun syncAllowancesRemote(packageName: String) {
        val response = try {
            ApiClient.getAllowances(this, packageName)
        } catch (_: Exception) {
            null
        }

        if (response?.ok == true) {
            try {
                val json = org.json.JSONObject(response.body)
                val allowances = json.optJSONArray("allowances")
                if (allowances != null) {
                    ExtraTimeLedger.updateFromRemote(this, allowances)
                }
            } catch (_: Exception) {}
        }
    }

    private fun liveSessionMinutes(
        packageName: String,
        usageMinutes: Long,
        foregroundPackage: String?
    ): Long {
        if (foregroundPackage != packageName) return usageMinutes

        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences("fast_enforcement_session", Context.MODE_PRIVATE)
        val storedPkg = prefs.getString("pkg", null)
        var start = prefs.getLong("start", 0L)
        var base = prefs.getLong("base", usageMinutes)

        if (storedPkg != packageName || start <= 0L) {
            start = now
            base = usageMinutes
            prefs.edit()
                .putString("pkg", packageName)
                .putLong("start", start)
                .putLong("base", base)
                .apply()
        }

        val elapsed = ((now - start) / 60_000L).coerceAtLeast(0L)
        return maxOf(usageMinutes, base + elapsed)
    }

    private fun currentForegroundPackage(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - 60 * 60 * 1000L
        val events = usm.queryEvents(start, end)
        val event = UsageEvents.Event()
        var current: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> current = event.packageName
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (current == event.packageName) current = null
            }
        }
        return current
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(
        text: String = "Monitoring policy and managed-device protection"
    ): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_lock_lock)
        .setContentTitle("FamilyControl protection")
        .setContentText(text)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FamilyControl Protection",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Visible device protection and enforcement status"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "familycontrol_protection"
        private const val NOTIFICATION_ID = 4201
        private const val CHECK_INTERVAL_MS = 15_000L
        private const val EXTRA_TIME_PREFS = "approved_extra_time"
    }
}
