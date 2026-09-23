package com.familycontrol.lab

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import org.json.JSONObject

object PolicySyncEngine {

    fun syncAndApplyCloudPolicy(context: Context): ApiResponse {
        if (!ApiClient.registered(context)) {
            return ApiResponse(false, 0, "", "Device not registered with Cloud")
        }

        val syncResponse = try {
            ApiClient.getSync(context)
        } catch (e: Exception) {
            return ApiResponse(false, 500, "", "Sync network error: ${e.message}")
        }

        if (!syncResponse.ok) {
            return syncResponse
        }

        try {
            val json = JSONObject(syncResponse.body)
            val status = json.optString("status", "SYNCED")

            // Sync instant lock status
            val lockResponse = try { ApiClient.getInstantLock(context) } catch (_: Exception) { null }
            if (lockResponse?.ok == true) {
                val lockJson = JSONObject(lockResponse.body)
                val isLocked = lockJson.optBoolean("locked", false)
                val pPrefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
                val prevLocked = pPrefs.getBoolean("instant_pause_enabled", false)
                pPrefs.edit().putBoolean("instant_pause_enabled", isLocked).apply()
                if (isLocked && !prevLocked) {
                    NotificationEngine.notify(
                        context,
                        2002,
                        "⏸️ Device Paused",
                        "Your parent has temporarily paused this device."
                    )
                }
            }

            if (status == "OUTDATED" && json.has("policy") && !json.isNull("policy")) {
                val policyObj = json.getJSONObject("policy")
                val availableVersion = json.optInt("available_policy_version", policyObj.optInt("version", 0))
                val parentVersion = policyObj.optLong("parent_policy_version", 0L)
                val source = policyObj.optString("source", "")

                applyPolicyObj(context, policyObj)

                if (availableVersion > 0) {
                    ApiClient.acknowledgeSync(context, availableVersion)
                    val pPrefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
                    val lastNotifiedParentVersion = pPrefs.getLong("last_notified_parent_policy_version", 0L)

                    val isFromParent = source.equals("PARENT", ignoreCase = true) || (parentVersion > 0L && parentVersion > lastNotifiedParentVersion)
                    if (ApiClient.getDeviceRole(context) == ApiClient.ROLE_CHILD && isFromParent && parentVersion > lastNotifiedParentVersion) {
                        pPrefs.edit().putLong("last_notified_parent_policy_version", parentVersion).apply()
                        NotificationEngine.notify(
                            context,
                            2001,
                            "🛡️ Family Rules Updated",
                            "Your parent has updated device rules and app limits."
                        )
                    }
                }
                EventLog.record(context, "CLOUD_POLICY_APPLIED v=$availableVersion")
                return ApiResponse(true, 200, syncResponse.body)
            } else {
                auditAndUnsuspendPackages(context)
                return syncResponse
            }
        } catch (e: Exception) {
            EventLog.record(context, "POLICY_SYNC_APPLY_ERROR ${e.message}")
            return ApiResponse(false, 500, "", "Error applying policy: ${e.message}")
        }
    }

    fun applyPolicyObj(context: Context, policyObj: JSONObject) {
        val prefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
        val editor = prefs.edit()

        val dailyLimit = policyObj.optInt("daily_screen_limit_minutes", 180)
        editor.putInt("daily_screen_limit", dailyLimit)

        if (policyObj.has("child_display_name")) {
            val cName = policyObj.optString("child_display_name", "")
            if (cName.isNotBlank()) {
                editor.putString("child_display_name", cName)
            }
        }

        val rules = policyObj.optJSONObject("rules")
        val activePackages = mutableSetOf<String>()

        if (rules != null) {
            val keys = rules.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val ruleObj = rules.optJSONObject(key) ?: continue
                val pkg = ruleObj.optString("package", key)
                val enabled = ruleObj.optBoolean("enabled", true)
                val limit = ruleObj.optInt("daily_limit_minutes", 30)

                activePackages.add(pkg)
                editor.putBoolean("enabled_$pkg", enabled)
                editor.putInt("limit_$pkg", limit)
            }
        }

        // Apply active preset from cloud policy
        if (policyObj.has("active_preset")) {
            val preset = policyObj.optString("active_preset", PresetModeEngine.MODE_NONE)
            val currentLocalPreset = PresetModeEngine.getActivePreset(context)
            if (preset != currentLocalPreset) {
                if (preset == PresetModeEngine.MODE_NONE) {
                    PresetModeEngine.forceDeactivateByParent(context)
                } else {
                    PresetModeEngine.setActivePreset(context, preset, 2, 0)
                }
            }
        }

        // Disable local package rules that are not present in remote policy
        val existingKeys = prefs.all.keys.filter { it.startsWith("enabled_") }
        for (key in existingKeys) {
            val pkg = key.removePrefix("enabled_")
            if (!activePackages.contains(pkg)) {
                editor.putBoolean("enabled_$pkg", false)
            }
        }

        editor.apply()

        // Apply synchronized parent PIN from cloud
        if (policyObj.has("parent_pin")) {
            val parentPin = policyObj.optString("parent_pin", "")
            if (parentPin.isNotBlank()) {
                ParentSecurity.setPin(context, parentPin)
            }
        }

        // Apply synchronized badges from cloud
        if (policyObj.has("badges")) {
            val badgesArr = policyObj.optJSONArray("badges")
            if (badgesArr != null) {
                val childId = ApiClient.serverChildId(context) ?: ChildProfileManager.getActiveChild(context).id
                val activeChildId = try { ChildProfileManager.getActiveChild(context).id } catch (_: Exception) { childId }
                for (bIdx in 0 until badgesArr.length()) {
                    val bObj = badgesArr.optJSONObject(bIdx) ?: continue
                    val bId = bObj.optString("id")
                    val bUnlocked = bObj.optBoolean("unlocked", false)
                    if (bId.isNotBlank()) {
                        BadgeEngine.setBadgeUnlocked(context, childId, bId, bUnlocked)
                        if (activeChildId.isNotBlank() && activeChildId != childId) {
                            BadgeEngine.setBadgeUnlocked(context, activeChildId, bId, bUnlocked)
                        }
                    }
                }
            }
        }

        // Apply synchronized category budget config from cloud
        if (policyObj.has("category_budgets_config")) {
            val catConfig = policyObj.optJSONObject("category_budgets_config")
            if (catConfig != null) {
                val childId = ApiClient.serverChildId(context) ?: ChildProfileManager.getActiveChild(context).id
                CategoryBudgetEngine.applyJson(context, catConfig, childId)
            }
        }

        // Apply synchronized feature toggles from cloud
        if (policyObj.has("feature_toggles")) {
            val toggles = policyObj.optJSONObject("feature_toggles")
            if (toggles != null) {
                if (toggles.has("category_budgets")) {
                    FeatureToggleEngine.setCategoryBudgetsEnabled(context, toggles.optBoolean("category_budgets", true))
                }
                if (toggles.has("piggy_bank")) {
                    FeatureToggleEngine.setPiggyBankEnabled(context, toggles.optBoolean("piggy_bank", true))
                }
                if (toggles.has("habit_badges")) {
                    FeatureToggleEngine.setHabitBadgesEnabled(context, toggles.optBoolean("habit_badges", true))
                }
                if (toggles.has("executive_report")) {
                    FeatureToggleEngine.setExecutiveReportEnabled(context, toggles.optBoolean("executive_report", true))
                }
            }
        }

        auditAndUnsuspendPackages(context)
    }

    fun auditAndUnsuspendPackages(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, LabDeviceAdminReceiver::class.java)

        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        val prefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
        val instantPause = prefs.getBoolean("instant_pause_enabled", false)
        val activePreset = PresetModeEngine.getActivePreset(context)

        val defaultApps = setOf(
            "com.instagram.android",
            "com.jio.jioPlay.tv",
            "com.netflix.mediaclient",
            "com.google.android.youtube",
            "com.android.chrome"
        )
        val customApps = prefs.all.keys
            .filter { it.startsWith("enabled_") || it.startsWith("limit_") }
            .map { it.removePrefix("enabled_").removePrefix("limit_") }
            .toSet()

        val allApps = (defaultApps + customApps).filter { it.isNotBlank() }
        val usage = getTodayUsage(context)

        for (pkg in allApps) {
            val enabled = prefs.getBoolean("enabled_$pkg", false)
            val baseLimit = prefs.getInt("limit_$pkg", 30)
            val extraMins = ExtraTimeLedger.getRemainingExtraMinutes(context, pkg)
            val effectiveLimit = baseLimit + extraMins
            val appUsage = usage.firstOrNull { it.packageName == pkg }?.minutes ?: 0L

            val category = CategoryBudgetEngine.getCategoryForPackage(pkg)
            val restrictedByPreset = when (activePreset) {
                PresetModeEngine.MODE_STUDY -> category == "Social" || category == "Gaming" || category == "Entertainment"
                PresetModeEngine.MODE_BEDTIME -> category != "Education & Productivity"
                PresetModeEngine.MODE_DINNER -> category == "Social" || category == "Gaming" || category == "Entertainment"
                else -> false
            }

            val isEmergency = AccessibilityGuardEngine.isAlwaysAllowedEmergencyApp(context, pkg)
            val restrictedByRoutine = ScheduleEngine.isAppRestrictedByRoutine(context, pkg)
            val isRestricted = !isEmergency && (instantPause || restrictedByPreset || (enabled && appUsage >= effectiveLimit) || restrictedByRoutine)

            if (!isRestricted || isEmergency) {
                try {
                    val failures = dpm.setPackagesSuspended(admin, arrayOf(pkg), false)
                    if (failures.isEmpty()) {
                        EventLog.record(context, "OS_APP_UNSUSPENDED $pkg")
                    }
                } catch (e: Exception) {
                    EventLog.record(context, "UNSUSPEND_ERROR $pkg: ${e.message}")
                }
            }
        }
    }
}
