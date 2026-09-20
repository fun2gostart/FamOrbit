package com.familycontrol.lab

import android.accessibilityservice.AccessibilityService
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

    fun isPackageBlocked(context: Context, packageName: String): Boolean {
        if (packageName.isBlank() || packageName == context.packageName) return false
        if (AppScanner.isSystemStub(packageName, AppNameResolver.getAppName(context, packageName))) return false

        val prefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)

        // 1. Instant remote lock
        if (prefs.getBoolean("instant_pause_enabled", false)) {
            return true
        }

        // 2. Preset Modes
        val activePreset = PresetModeEngine.getActivePreset(context)
        if (activePreset == PresetModeEngine.MODE_DINNER ||
            activePreset == PresetModeEngine.MODE_BEDTIME ||
            activePreset == PresetModeEngine.MODE_STUDY
        ) {
            val category = CategoryBudgetEngine.getCategoryForPackage(packageName)
            if (category == "Gaming" || category == "Social" || category == "Entertainment") {
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

        // 4. App Policy Limit + Extra Time Ledger
        val enabled = prefs.getBoolean("enabled_$packageName", false)
        if (enabled) {
            val baseLimit = prefs.getInt("limit_$packageName", 30)
            val usage = getTodayUsage(context)
            val appUsage = usage.firstOrNull { it.packageName == packageName }?.minutes ?: 0L
            val extraMins = ExtraTimeLedger.getRemainingExtraMinutes(context, packageName)
            val totalLimit = baseLimit + extraMins
            if (appUsage >= totalLimit) {
                return true
            }
        }

        return false
    }
}
