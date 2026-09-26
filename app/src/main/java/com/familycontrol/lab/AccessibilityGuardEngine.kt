package com.familycontrol.lab

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils

object AccessibilityGuardEngine {

    fun isAccessibilityEnabled(context: Context): Boolean {
        val expectedService = "${context.packageName}/${FamilyAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val service = splitter.next()
            if (service.equals(expectedService, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager ?: return false
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccessSettings(context: Context) {
        grantParentSettingsBypass(context)
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun grantParentSettingsBypass(context: Context, durationMs: Long = 120_000L) {
        val prefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
        prefs.edit().putLong("parent_settings_bypass_until", System.currentTimeMillis() + durationMs).apply()
    }

    fun isParentSettingsBypassActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
        val until = prefs.getLong("parent_settings_bypass_until", 0L)
        return System.currentTimeMillis() < until
    }

    fun openAccessibilitySettings(context: Context) {
        grantParentSettingsBypass(context)
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openOverlaySettings(context: Context) {
        grantParentSettingsBypass(context)
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun isAlwaysAllowedEmergencyApp(context: Context, packageName: String): Boolean {
        if (packageName.isBlank() || packageName == context.packageName) return true
        val lowerPkg = packageName.lowercase()
        val appName = AppNameResolver.getAppName(context, packageName).lowercase()

        // 1. Phone & Dialer & Telecom / InCall
        if (lowerPkg.contains("dialer") || lowerPkg.contains("telecom") ||
            lowerPkg.contains("incallui") || lowerPkg.contains(".phone") ||
            appName.contains("phone") || appName.contains("dialer")) {
            return true
        }

        // 2. Emergency / Safety / SOS
        if (lowerPkg.contains("emergency") || lowerPkg.contains("safetyhub") ||
            lowerPkg.contains("sos") || appName.contains("emergency") || appName.contains("safety")) {
            return true
        }

        // 3. Camera
        if (lowerPkg.contains("camera") || lowerPkg.contains("camera2") ||
            appName.contains("camera")) {
            return true
        }

        // 4. Voice Recorder / Sound Recorder
        if (lowerPkg.contains("recorder") || lowerPkg.contains("voicenote") ||
            lowerPkg.contains("soundrecorder") || appName.contains("recorder") || appName.contains("voice note")) {
            return true
        }

        // 5. SMS / Messages
        if (lowerPkg.contains("messaging") || lowerPkg.contains("mms") ||
            appName.contains("messages") || appName.contains("messaging")) {
            return true
        }

        // 6. Utility Tools: Calculator & Clock / Alarm
        if (lowerPkg.contains("calculator") || lowerPkg.contains("deskclock") ||
            lowerPkg.contains("alarmclock") || appName.contains("calculator") || appName.contains("clock")) {
            return true
        }

        // 7. System Stubs & System UI
        if (AppScanner.isSystemStub(packageName, appName)) {
            return true
        }

        return false
    }

    private const val PREFS_ACCUMULATED = "famorbit_app_usage_accumulated"

    private fun getTodayDateKey(): String {
        val cal = java.util.Calendar.getInstance()
        return "${cal.get(java.util.Calendar.YEAR)}_${cal.get(java.util.Calendar.DAY_OF_YEAR)}"
    }

    fun getAccumulatedAppMs(context: Context, packageName: String): Long {
        val prefs = context.getSharedPreferences(PREFS_ACCUMULATED, Context.MODE_PRIVATE)
        val todayKey = getTodayDateKey()
        val savedDay = prefs.getString("current_day_key", "")
        if (savedDay != todayKey) {
            prefs.edit().clear().putString("current_day_key", todayKey).apply()
            return 0L
        }
        return prefs.getLong("ms_$packageName", 0L)
    }

    fun setAccumulatedAppMs(context: Context, packageName: String, totalMs: Long) {
        if (packageName.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS_ACCUMULATED, Context.MODE_PRIVATE)
        val todayKey = getTodayDateKey()
        val editor = prefs.edit()
        val savedDay = prefs.getString("current_day_key", "")
        if (savedDay != todayKey) {
            editor.clear().putString("current_day_key", todayKey)
        }
        val current = if (savedDay == todayKey) prefs.getLong("ms_$packageName", 0L) else 0L
        editor.putLong("ms_$packageName", maxOf(current, totalMs)).apply()
    }

    fun addAccumulatedAppMs(context: Context, packageName: String, additionalMs: Long) {
        if (additionalMs <= 0L || packageName.isBlank()) return
        val current = getAccumulatedAppMs(context, packageName)
        setAccumulatedAppMs(context, packageName, current + additionalMs)
    }

    @Volatile
    private var currentForegroundPackage: String? = null
    @Volatile
    private var currentForegroundStartTime: Long = 0L
    @Volatile
    private var currentSessionBaselineMs: Long = 0L

    fun flushCurrentForegroundSession(context: Context) {
        val pkg = currentForegroundPackage
        val start = currentForegroundStartTime
        if (!pkg.isNullOrBlank() && start > 0L) {
            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(0L)
            val usageStatsMs = getUsageStatsForegroundMs(context, pkg)
            val totalCalculatedMs = currentSessionBaselineMs + elapsed
            val finalMs = maxOf(totalCalculatedMs, usageStatsMs)
            setAccumulatedAppMs(context, pkg, finalMs)
            currentForegroundStartTime = System.currentTimeMillis()
            currentSessionBaselineMs = finalMs
        }
    }

    fun onForegroundPackageChanged(context: Context, newPackageName: String) {
        if (newPackageName.isBlank() || isAlwaysAllowedEmergencyApp(context, newPackageName) || isSettingsOrInstallerPkg(newPackageName)) {
            flushCurrentForegroundSession(context)
            currentForegroundPackage = null
            currentForegroundStartTime = 0L
            currentSessionBaselineMs = 0L
            return
        }
        if (currentForegroundPackage != newPackageName) {
            flushCurrentForegroundSession(context)
            currentForegroundPackage = newPackageName
            currentForegroundStartTime = System.currentTimeMillis()
            val usageStatsMs = getUsageStatsForegroundMs(context, newPackageName)
            val accumulatedMs = getAccumulatedAppMs(context, newPackageName)
            currentSessionBaselineMs = maxOf(usageStatsMs, accumulatedMs)
        }
    }

    fun resetForegroundTracking(context: Context? = null) {
        if (context != null) {
            flushCurrentForegroundSession(context)
        }
        currentForegroundPackage = null
        currentForegroundStartTime = 0L
        currentSessionBaselineMs = 0L
    }

    private fun isSettingsOrInstallerPkg(pkgName: String): Boolean {
        return pkgName == "com.android.settings" ||
                pkgName == "com.google.android.packageinstaller" ||
                pkgName == "com.android.packageinstaller" ||
                pkgName.contains("launcher")
    }

    fun getUsageStatsForegroundMs(context: Context, packageName: String): Long {
        if (!hasUsageAccess(context)) return 0L
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return 0L
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        return try {
            val statsMap = manager.queryAndAggregateUsageStats(start, now)
            statsMap[packageName]?.totalTimeInForeground ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun getEffectiveAppUsageMs(context: Context, packageName: String): Long {
        val usageStatsMs = getUsageStatsForegroundMs(context, packageName)
        val accumulatedMs = getAccumulatedAppMs(context, packageName)
        val now = System.currentTimeMillis()
        val liveSessionMs = if (currentForegroundPackage == packageName && currentForegroundStartTime > 0L) {
            val elapsed = (now - currentForegroundStartTime).coerceAtLeast(0L)
            currentSessionBaselineMs + elapsed
        } else {
            0L
        }
        return maxOf(usageStatsMs, accumulatedMs, liveSessionMs)
    }

    fun getEffectiveAppUsageMinutes(context: Context, packageName: String): Long {
        return getEffectiveAppUsageMs(context, packageName) / 60_000L
    }

    fun isPackageBlocked(context: Context, packageName: String): Boolean {
        if (packageName.isBlank() || packageName == context.packageName) return false
        if (ApiClient.getDeviceRole(context) == ApiClient.ROLE_PARENT) return false
        if (isAlwaysAllowedEmergencyApp(context, packageName)) return false

        val prefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)

        // 1. Instant remote lock / pause
        if (prefs.getBoolean("instant_pause_enabled", false)) {
            return true
        }

        // 2. Preset Modes
        val activePreset = PresetModeEngine.getActivePreset(context)
        if (activePreset == PresetModeEngine.MODE_DINNER || activePreset == PresetModeEngine.MODE_STUDY) {
            val category = CategoryBudgetEngine.getCategoryForPackage(packageName)
            if (category == "Gaming" || category == "Social" || category == "Entertainment") {
                return true
            }
        } else if (activePreset == PresetModeEngine.MODE_BEDTIME) {
            val category = CategoryBudgetEngine.getCategoryForPackage(packageName)
            if (category != "Education & Productivity") {
                return true
            }
        }

        // 3. Learn-First Rule (Gaming apps blocked if 30m educational usage not met)
        if (FeatureToggleEngine.isCategoryBudgetsEnabled(context)) {
            val usage = getTodayUsage(context)
            if (CategoryBudgetEngine.isGamingBlockedByLearnFirst(context, usage)) {
                val category = CategoryBudgetEngine.getCategoryForPackage(packageName)
                if (category == "Gaming" || category == "Entertainment") {
                    return true
                }
            }
        }

        // 4. App Policy Limit + Extra Time Ledger (Millisecond Precision)
        // Check parent_control.xml first (cloud-synced rules),
        // then multi-profile childId-prefixed rules,
        // then policies.xml (locally-set rules) as fallback.
        val apiPrefs = context.getSharedPreferences("api", Context.MODE_PRIVATE)
        val childId = apiPrefs.getString("server_child_id", null)
            ?: ChildProfileManager.getActiveChild(context).id

        var ruleFound = false
        var enabled = false
        var baseLimit = 30

        // Source 1: parent_control.xml un-prefixed keys (cloud-synced via PolicySyncEngine)
        if (prefs.contains("limit_$packageName") || prefs.contains("enabled_$packageName")) {
            ruleFound = true
            baseLimit = prefs.getInt("limit_$packageName", 30)
            enabled = if (prefs.contains("enabled_$packageName")) {
                prefs.getBoolean("enabled_$packageName", true)
            } else {
                baseLimit > 0
            }
        }

        // Source 2: parent_control.xml childId-prefixed keys (multi-profile)
        if ((!ruleFound || !enabled) && childId.isNotEmpty()) {
            val prefixedEnabledKey = "${childId}_enabled_$packageName"
            val prefixedLimitKey = "${childId}_limit_$packageName"
            if (prefs.contains(prefixedLimitKey) || prefs.contains(prefixedEnabledKey)) {
                val pLimit = prefs.getInt(prefixedLimitKey, 30)
                val pEnabled = if (prefs.contains(prefixedEnabledKey)) {
                    prefs.getBoolean(prefixedEnabledKey, true)
                } else {
                    pLimit > 0
                }
                if (pEnabled) {
                    ruleFound = true
                    baseLimit = pLimit
                    enabled = true
                }
            }
        }

        // Source 3: policies.xml (locally-set rules from the Local Policies screen)
        if (!ruleFound || !enabled) {
            val policyPrefs = context.getSharedPreferences("policies", Context.MODE_PRIVATE)
            if (policyPrefs.contains("limit_$packageName") || policyPrefs.contains("enabled_$packageName")) {
                val locLimit = policyPrefs.getInt("limit_$packageName", 30)
                val locEnabled = if (policyPrefs.contains("enabled_$packageName")) {
                    policyPrefs.getBoolean("enabled_$packageName", true)
                } else {
                    locLimit > 0
                }
                if (locEnabled) {
                    ruleFound = true
                    baseLimit = locLimit
                    enabled = true
                }
            }
        }

        // Enforce if enabled or if an explicit non-default limit was configured
        if (baseLimit > 0 && (enabled || baseLimit != 30)) {
            val totalUsedMs = getEffectiveAppUsageMs(context, packageName)
            val extraMins = ExtraTimeLedger.getRemainingExtraMinutes(context, packageName)
            val totalLimitMs = (baseLimit + extraMins) * 60_000L

            if (totalUsedMs >= totalLimitMs) {
                return true
            }
        }

        return false
    }
}
