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
                context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
                    .edit().putBoolean("instant_pause_enabled", isLocked).apply()
            }

            if (status == "OUTDATED" && json.has("policy") && !json.isNull("policy")) {
                val policyObj = json.getJSONObject("policy")
                val availableVersion = json.optInt("available_policy_version", policyObj.optInt("version", 0))

                applyPolicyObj(context, policyObj)

                if (availableVersion > 0) {
                    ApiClient.acknowledgeSync(context, availableVersion)
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

        // Disable local package rules that are not present in remote policy
        val existingKeys = prefs.all.keys.filter { it.startsWith("enabled_") }
        for (key in existingKeys) {
            val pkg = key.removePrefix("enabled_")
            if (!activePackages.contains(pkg)) {
                editor.putBoolean("enabled_$pkg", false)
            }
        }

        editor.apply()
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
                PresetModeEngine.MODE_BEDTIME -> category != "Education"
                PresetModeEngine.MODE_DINNER -> category == "Social" || category == "Gaming" || category == "Entertainment"
                else -> false
            }

            val restrictedByRoutine = ScheduleEngine.isAppRestrictedByRoutine(context, pkg)
            val isRestricted = instantPause || restrictedByPreset || (enabled && appUsage >= effectiveLimit) || restrictedByRoutine

            if (!isRestricted) {
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
