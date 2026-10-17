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

    fun revokeParentSettingsBypass(context: Context) {
        val prefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
        prefs.edit().remove("parent_settings_bypass_until").apply()
    }

    fun getRemainingBypassMinutes(context: Context): Int {
        val prefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
        val until = prefs.getLong("parent_settings_bypass_until", 0L)
        val remainingMs = until - System.currentTimeMillis()
        return if (remainingMs > 0) ((remainingMs + 59_999L) / 60_000L).toInt() else 0
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

    fun isAlwaysAllowedEmergencyApp(context: Context?, packageName: String): Boolean {
        if (packageName.isBlank() || (context != null && packageName == context.packageName)) return true
        val lowerPkg = packageName.lowercase()
        val appName = AppNameResolver.getAppName(context, packageName).lowercase()

        // 1. Phone & Dialer & Telecom / InCall / Contacts
        if (lowerPkg.contains("dialer") || lowerPkg.contains("telecom") ||
            lowerPkg.contains("incallui") || lowerPkg.contains(".phone") ||
            lowerPkg == "com.android.phone" || lowerPkg == "com.android.contacts" ||
            lowerPkg == "com.google.android.dialer" || lowerPkg == "com.google.android.contacts" ||
            lowerPkg.contains("comms.dialer") || lowerPkg.contains("comms.contact") ||
            appName.contains("phone") || appName.contains("dialer") || appName.contains("contacts")) {
            return true
        }

        // 2. Emergency / Safety / SOS
        if (lowerPkg.contains("emergency") || lowerPkg.contains("safetyhub") ||
            lowerPkg.contains("safecenter") || lowerPkg.contains("sos") ||
            appName.contains("emergency") || appName.contains("safety") || appName.contains("sos")) {
            return true
        }

        // 3. Camera
        if (lowerPkg.contains("camera") || lowerPkg.contains("camera2") ||
            lowerPkg == "com.oplus.camera" || lowerPkg == "com.google.android.googlecamera" ||
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
            lowerPkg == "com.google.android.apps.messaging" || lowerPkg == "com.android.mms" ||
            lowerPkg == "com.truecaller" ||
            appName.contains("messages") || appName.contains("messaging") || appName.contains("sms")) {
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

    fun clearCorruptedAccumulatedUsage(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_ACCUMULATED, Context.MODE_PRIVATE)
            prefs.edit().clear().putString("current_day_key", getTodayDateKey()).apply()
        } catch (_: Exception) {}
    }

    fun getAccumulatedAppMs(context: Context, packageName: String): Long {
        if (packageName.isBlank()) return 0L
        val prefs = context.getSharedPreferences(PREFS_ACCUMULATED, Context.MODE_PRIVATE)
        val todayKey = getTodayDateKey()
        val savedDay = prefs.getString("current_day_key", "")
        if (savedDay != todayKey) {
            prefs.edit().clear().putString("current_day_key", todayKey).apply()
            return 0L
        }
        val storedMs = prefs.getLong("ms_$packageName", 0L)
        // Self-healing ground-truth reconciliation:
        // Android's UsageStatsManager is the authoritative hardware record.
        // If storedMs exceeds UsageStats by more than a 2-minute un-flushed session margin,
        // it contains corrupted phantom sleep time. Clamp it back to UsageStats ground truth!
        if (hasUsageAccess(context)) {
            val usageStatsMs = getUsageStatsForegroundMs(context, packageName)
            val maxAllowed = usageStatsMs + 120_000L
            if (storedMs > maxAllowed) {
                prefs.edit().putLong("ms_$packageName", usageStatsMs).apply()
                return usageStatsMs
            }
        }
        return storedMs
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
        val candidate = maxOf(current, totalMs)
        // Prevent storing phantom sleep time: clamp candidate against authoritative UsageStats
        val safeTotal = if (hasUsageAccess(context)) {
            val usageStatsMs = getUsageStatsForegroundMs(context, packageName)
            val maxAllowed = usageStatsMs + 120_000L
            if (candidate > maxAllowed) usageStatsMs else candidate
        } else {
            candidate
        }
        editor.putLong("ms_$packageName", safeTotal).apply()
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
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val isInteractive = pm?.isInteractive ?: true
            if (isInteractive) {
                val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(0L)
                val usageStatsMs = getUsageStatsForegroundMs(context, pkg)
                val totalCalculatedMs = currentSessionBaselineMs + elapsed
                val finalMs = maxOf(totalCalculatedMs, usageStatsMs)
                setAccumulatedAppMs(context, pkg, finalMs)
                currentForegroundStartTime = System.currentTimeMillis()
                currentSessionBaselineMs = finalMs
            } else {
                // Device screen was off: do NOT accumulate sleep time as app usage!
                currentForegroundStartTime = System.currentTimeMillis()
            }
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
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val isInteractive = pm?.isInteractive ?: true
        val liveSessionMs = if (currentForegroundPackage == packageName && currentForegroundStartTime > 0L && isInteractive) {
            val elapsed = (now - currentForegroundStartTime).coerceAtLeast(0L)
            currentSessionBaselineMs + elapsed
        } else {
            0L
        }
        val safeAccumulatedMs = if (hasUsageAccess(context)) {
            val maxAllowed = usageStatsMs + 120_000L
            minOf(accumulatedMs, maxAllowed)
        } else {
            accumulatedMs
        }
        return maxOf(usageStatsMs, safeAccumulatedMs, liveSessionMs)
    }

    fun getEffectiveAppUsageMinutes(context: Context, packageName: String): Long {
        return getEffectiveAppUsageMs(context, packageName) / 60_000L
    }

    fun getEffectiveTodayUsage(context: Context): List<AppUsage> {
        val baseList = getTodayUsage(context).toMutableList()
        val currPkg = currentForegroundPackage
        if (!currPkg.isNullOrBlank() && !isAlwaysAllowedEmergencyApp(context, currPkg)) {
            val liveMins = getEffectiveAppUsageMinutes(context, currPkg)
            val idx = baseList.indexOfFirst { it.packageName == currPkg }
            if (idx >= 0) {
                val existing = baseList[idx]
                if (liveMins > existing.minutes) {
                    baseList[idx] = existing.copy(minutes = liveMins)
                }
            } else if (liveMins > 0L) {
                val appName = AppNameResolver.getAppName(context, currPkg)
                baseList.add(AppUsage(appName, currPkg, liveMins))
            }
        }
        return baseList
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

        // 3. Routine Schedule Restrictions
        if (ScheduleEngine.isAppRestrictedByRoutine(context, packageName)) {
            return true
        }

        val childId = ApiClient.serverChildId(context)
            ?: prefs.getString("server_child_id", null)
            ?: try { ChildProfileManager.getActiveChild(context).id } catch (_: Exception) { "" }

        val todayUsage = getEffectiveTodayUsage(context)

        // 4. Tier 1: Device-Wide Daily Screen Time Allowance
        val dailyLimit = if (childId.isNotEmpty() && prefs.contains("${childId}_daily_screen_limit")) {
            prefs.getInt("${childId}_daily_screen_limit", 180)
        } else {
            prefs.getInt("daily_screen_limit", 180)
        }
        val deviceExtraMins = ExtraTimeLedger.getRemainingExtraMinutes(context, "")
        val totalDeviceUsedMins = todayUsage.sumOf { it.minutes }

        if (dailyLimit > 0 && totalDeviceUsedMins >= (dailyLimit + deviceExtraMins)) {
            return true
        }

        // 5. Tier 2: 5-Category Time Budgets & Learn-First Rule
        if (FeatureToggleEngine.isCategoryBudgetsEnabled(context)) {
            val category = CategoryBudgetEngine.getCategoryForPackage(packageName)
            // 5a. Learn-First Rule (Gaming apps blocked until study required minutes met)
            if (CategoryBudgetEngine.isGamingBlockedByLearnFirst(context, todayUsage, childId)) {
                if (category == "Gaming" || category == "Entertainment") {
                    return true
                }
            }
            // 5b. Category-wise daily budget limit
            if (CategoryBudgetEngine.isCategoryBudgetExceeded(context, category, todayUsage, childId)) {
                return true
            }
        }

        // 6. Tier 3: App Policy Limit + Extra Time Ledger (Millisecond Precision)
        // Check parent_control.xml first (cloud-synced rules),
        // then multi-profile childId-prefixed rules,
        // then policies.xml (locally-set rules) as fallback.
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

    fun getBlockReason(context: Context, packageName: String): String {
        val appName = AppNameResolver.getAppName(context, packageName)
        val prefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)

        if (prefs.getBoolean("instant_pause_enabled", false)) {
            return "🔒 Device paused by parent."
        }

        val activePreset = PresetModeEngine.getActivePreset(context)
        if (activePreset == PresetModeEngine.MODE_DINNER) return "🔒 Dinner mode active. Restricted by FamOrbit."
        if (activePreset == PresetModeEngine.MODE_STUDY) return "🔒 Study mode active. Restricted by FamOrbit."
        if (activePreset == PresetModeEngine.MODE_BEDTIME) return "🔒 Bedtime mode active. Restricted by FamOrbit."

        if (ScheduleEngine.isAppRestrictedByRoutine(context, packageName)) {
            return "🔒 Scheduled routine active. Restricted by FamOrbit."
        }

        val childId = ApiClient.serverChildId(context)
            ?: prefs.getString("server_child_id", null)
            ?: try { ChildProfileManager.getActiveChild(context).id } catch (_: Exception) { "" }

        val dailyLimit = if (childId.isNotEmpty() && prefs.contains("${childId}_daily_screen_limit")) {
            prefs.getInt("${childId}_daily_screen_limit", 180)
        } else {
            prefs.getInt("daily_screen_limit", 180)
        }
        val deviceExtraMins = ExtraTimeLedger.getRemainingExtraMinutes(context, "")
        val todayUsage = getEffectiveTodayUsage(context)
        val totalDeviceUsedMins = todayUsage.sumOf { it.minutes }

        if (dailyLimit > 0 && totalDeviceUsedMins >= (dailyLimit + deviceExtraMins)) {
            return "🔒 Daily screen time limit reached (${dailyLimit}m). Device locked."
        }

        if (FeatureToggleEngine.isCategoryBudgetsEnabled(context)) {
            val category = CategoryBudgetEngine.getCategoryForPackage(packageName)
            if (CategoryBudgetEngine.isGamingBlockedByLearnFirst(context, todayUsage, childId)) {
                if (category == "Gaming" || category == "Entertainment") {
                    val req = CategoryBudgetEngine.getLearnFirstRequiredMinutes(context, childId)
                    return "🔒 Learn-First: Complete ${req}m educational study to unlock."
                }
            }
            if (CategoryBudgetEngine.isCategoryBudgetExceeded(context, category, todayUsage, childId)) {
                val catLimit = CategoryBudgetEngine.getCategoryLimit(context, category, childId)
                return "🔒 $category daily budget reached (${catLimit}m). Category locked."
            }
        }

        return "🔒 $appName limit reached. Restricted by FamOrbit policy."
    }
}
