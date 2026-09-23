package com.familycontrol.lab

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.json.JSONObject

object RequestPollEngine {
    private var scheduler: ScheduledExecutorService? = null
    private const val PREFS = "request_poll_prefs"
    private const val KEY_NOTIFIED_PENDING = "notified_pending_ids"
    private const val KEY_NOTIFIED_RESOLVED = "notified_resolved_ids"
    @Volatile
    private var isAppForeground: Boolean = true

    fun setAppForegroundState(foreground: Boolean) {
        isAppForeground = foreground
    }

    fun isForeground(): Boolean = isAppForeground

    @Synchronized
    fun start(context: Context) {
        if (scheduler != null && !scheduler!!.isShutdown) return
        val appContext = context.applicationContext
        scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler?.scheduleWithFixedDelay({
            try {
                if (isAppForeground) {
                    checkOnce(appContext)
                }
            } catch (e: Exception) {
                // Silently handle background errors
            }
        }, 3, 10, TimeUnit.SECONDS)
    }

    fun checkOnce(context: Context) {
        if (!ApiClient.registered(context)) return
        val role = ApiClient.getDeviceRole(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (role == ApiClient.ROLE_PARENT) {
            val notifiedSet = prefs.getStringSet(KEY_NOTIFIED_PENDING, emptySet())?.toMutableSet() ?: mutableSetOf()
            var newlyNotified = false
            val children = ChildProfileManager.getChildren(context)

            for (child in children) {
                // 1. Actively refresh app catalog from all paired devices for this child
                val existingDevices = ChildDeviceManager.getDevicesForChild(context, child.id)
                for (dev in existingDevices) {
                    if (dev.deviceId.isNotBlank()) {
                        try {
                            val syncRes = ApiClient.getDeviceSync(context, dev.deviceId)
                            if (syncRes.ok) {
                                val syncJson = JSONObject(syncRes.body)
                                val pol = syncJson.optJSONObject("policy")
                                val installedApps = pol?.optJSONArray("installed_apps")
                                if (installedApps != null && installedApps.length() > 0) {
                                    val pPrefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
                                    val pkgs = pPrefs.getStringSet("${child.id}_app_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
                                    val editor = pPrefs.edit()
                                    for (j in 0 until installedApps.length()) {
                                        val appObj = installedApps.optJSONObject(j) ?: continue
                                        val p = appObj.optString("package")
                                        val n = appObj.optString("name").ifBlank { AppNameResolver.getAppName(context, p) }
                                        if (p.isNotBlank()) {
                                            pkgs.add(p)
                                            editor.putString("${child.id}_appname_$p", n)
                                        }
                                    }
                                    editor.putStringSet("${child.id}_app_packages", pkgs).apply()
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }

                // 2. Poll time requests and telemetry
                val response = ApiClient.getTimeRequestsForChild(context, child.id)
                if (!response.ok) continue
                val json = try { JSONObject(response.body) } catch (_: Exception) { continue }
                val arr = json.optJSONArray("requests") ?: continue

                for (i in 0 until arr.length()) {
                    val req = arr.optJSONObject(i) ?: continue
                    val status = req.optString("status")
                    val reqId = req.optString("request_id")
                    val minutes = req.optInt("requested_minutes")
                    val pkg = req.optString("package_name").takeIf { it.isNotBlank() && it != "null" && it != "None" }
                    val appName = AppNameResolver.getAppName(context, pkg)
                    val reason = req.optString("reason").ifBlank { "${child.name} requested extra screen time" }
                    val isInternalTelemetry = pkg?.startsWith("APP_CATALOG:") == true ||
                            reason.startsWith("DEV:") ||
                            reason.startsWith("DEV_INFO") ||
                            reason.startsWith("DEV_APPS#") ||
                            pkg == "com.familycontrol.lab" ||
                            pkg == "SYSTEM_ALERT" ||
                            reason.startsWith("Child device online")

                    if (isInternalTelemetry || reason.startsWith("DEV:") || reason.startsWith("DEV_INFO") || reason.startsWith("DEV_APPS#") || pkg?.startsWith("APP_CATALOG:") == true) {
                        var devModel = "${child.name}'s Device"
                        var devType = DeviceType.PHONE
                        var devBattery = if (minutes in 1..100) minutes else 85
                        var devId = req.optString("device_id").takeIf { it.isNotBlank() && it != "null" }

                        if (reason.startsWith("DEV_APPS#") || reason.startsWith("DEV_INFO#")) {
                            try {
                                val idPart = reason.substringAfter("#ID:").substringBefore("#")
                                if (idPart.isNotBlank()) devId = idPart
                                val mdlPart = reason.substringAfter("#MDL:").substringBefore("#")
                                if (mdlPart.isNotBlank()) devModel = mdlPart
                                val typPart = reason.substringAfter("#TYP:").substringBefore("#")
                                if (typPart == "TABLET") devType = DeviceType.TABLET
                                val batPart = reason.substringAfter("#BAT:").substringBefore("#")
                                devBattery = batPart.toIntOrNull() ?: devBattery
                            } catch (_: Exception) {}
                        } else if (reason.startsWith("DEV:")) {
                            try {
                                val devHeader = reason.substringAfter("DEV:").substringBefore("#APPS:")
                                val parts = devHeader.split(":")
                                if (parts.isNotEmpty() && parts[0].isNotBlank()) devModel = parts[0]
                                if (parts.size > 1 && parts[1] == "TABLET") devType = DeviceType.TABLET
                                if (parts.size > 2) devBattery = parts[2].toIntOrNull() ?: devBattery
                                if (parts.size > 3 && parts[3].isNotBlank()) devId = parts[3]
                            } catch (_: Exception) {}
                        } else if (pkg?.startsWith("APP_CATALOG:") == true) {
                            devModel = pkg.removePrefix("APP_CATALOG:").ifBlank { "${child.name}'s Device" }
                        }

                        val finalDevId = devId ?: "dev_${child.id}_${devModel.hashCode()}"

                        ChildDeviceManager.addOrUpdateDevice(
                            context,
                            ChildDevice(
                                deviceId = finalDevId,
                                childId = child.id,
                                deviceName = devModel,
                                deviceType = devType,
                                model = devModel,
                                isOnline = true,
                                batteryPct = devBattery,
                                lastSeenEpochMs = System.currentTimeMillis()
                            )
                        )
                        ChildDeviceManager.syncDeviceCounts(context)

                        // 1. Extract apps directly from chunked DEV_APPS# payload
                        try {
                            val rawApps = if (reason.startsWith("DEV_APPS#")) {
                                reason.substringAfterLast("#")
                            } else if (reason.contains("#APPS:")) {
                                reason.substringAfter("#APPS:")
                            } else ""

                            if (rawApps.isNotBlank()) {
                                val pPrefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
                                val pkgs = pPrefs.getStringSet("${child.id}_app_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
                                val editor = pPrefs.edit()
                                for (entry in rawApps.split(",")) {
                                    val pair = entry.split("|")
                                    if (pair.isNotEmpty() && pair[0].isNotBlank()) {
                                        val p = pair[0].trim()
                                        val a = if (pair.size > 1) pair[1].trim() else AppNameResolver.getAppName(context, p)
                                        pkgs.add(p)
                                        editor.putString("${child.id}_appname_$p", a)
                                    }
                                }
                                editor.putStringSet("${child.id}_app_packages", pkgs).apply()
                            }
                        } catch (_: Exception) {}

                        // 2. Also attempt cloud sync policy extraction
                        try {
                            if (devId != null && devId.isNotBlank()) {
                                val syncRes = ApiClient.getDeviceSync(context, devId)
                                if (syncRes.ok) {
                                    val syncJson = JSONObject(syncRes.body)
                                    val pol = syncJson.optJSONObject("policy")
                                    val installedApps = pol?.optJSONArray("installed_apps")
                                    if (installedApps != null && installedApps.length() > 0) {
                                        val pPrefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
                                        val pkgs = pPrefs.getStringSet("${child.id}_app_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
                                        val editor = pPrefs.edit()
                                        for (j in 0 until installedApps.length()) {
                                            val appObj = installedApps.optJSONObject(j) ?: continue
                                            val p = appObj.optString("package")
                                            val n = appObj.optString("name").ifBlank { AppNameResolver.getAppName(context, p) }
                                            if (p.isNotBlank()) {
                                                pkgs.add(p)
                                                editor.putString("${child.id}_appname_$p", n)
                                            }
                                        }
                                        editor.putStringSet("${child.id}_app_packages", pkgs).apply()
                                    }
                                }
                            }
                        } catch (_: Exception) {}

                        if (status == "PENDING") {
                            ApiClient.decideTimeRequest(context, reqId, false)
                        }
                    }

                    if (!isInternalTelemetry) {
                        ExtraTimeRequestEngine.syncCloudRequest(
                            context = context,
                            id = reqId,
                            packageName = pkg ?: "",
                            appName = appName,
                            requestedMinutes = minutes,
                            reason = reason,
                            status = status,
                            childId = child.id,
                            childName = child.name,
                            childAvatar = child.avatarEmoji
                        )

                        if (pkg != null && pkg.isNotBlank()) {
                            val pPrefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
                            val pkgs = pPrefs.getStringSet("${child.id}_app_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
                            if (pkgs.add(pkg)) {
                                pPrefs.edit()
                                    .putStringSet("${child.id}_app_packages", pkgs)
                                    .putString("${child.id}_appname_$pkg", appName)
                                    .apply()
                            }
                        }

                        if (status == "PENDING" && !notifiedSet.contains(reqId)) {
                            NotificationEngine.notify(
                                context,
                                reqId.hashCode(),
                                "${child.avatarEmoji} New Time Request from ${child.name}",
                                "Requested +${minutes}m for $appName: \"$reason\""
                            )
                            notifiedSet.add(reqId)
                            newlyNotified = true
                        }
                    }
                }
            }

            if (newlyNotified) {
                prefs.edit().putStringSet(KEY_NOTIFIED_PENDING, notifiedSet).apply()
            }
        } else if (role == ApiClient.ROLE_CHILD) {
            val response = ApiClient.getTimeRequests(context)
            if (!response.ok) return

            val json = try { JSONObject(response.body) } catch (e: Exception) { return }
            val arr = json.optJSONArray("requests") ?: return
            val notifiedResolved = prefs.getStringSet(KEY_NOTIFIED_RESOLVED, emptySet())?.toMutableSet() ?: mutableSetOf()
            var newlyNotified = false

            for (i in 0 until arr.length()) {
                val req = arr.optJSONObject(i) ?: continue
                val status = req.optString("status")
                val reqId = req.optString("request_id")
                val pkg = req.optString("package_name").takeIf { it.isNotBlank() && it != "null" && it != "None" }
                val reason = req.optString("reason")

                val isTelemetry = pkg?.startsWith("APP_CATALOG:") == true ||
                        pkg == "com.familycontrol.lab" ||
                        pkg == "SYSTEM_ALERT" ||
                        reason.startsWith("DEV:") ||
                        reason.startsWith("DEV_INFO") ||
                        reason.startsWith("DEV_APPS#") ||
                        reason.startsWith("Child device online")

                if (isTelemetry) {
                    notifiedResolved.add(reqId)
                    continue
                }

                if ((status == "APPROVED" || status == "DECLINED") && !notifiedResolved.contains(reqId)) {
                    val minutes = req.optInt("requested_minutes")
                    val appName = AppNameResolver.getAppName(context, pkg)

                    if (status == "APPROVED") {
                        NotificationEngine.notify(
                            context,
                            reqId.hashCode(),
                            "🎉 Extra Time Approved!",
                            "Parent approved +${minutes}m for $appName! Enjoy!"
                        )
                        try {
                            PolicySyncEngine.syncAndApplyCloudPolicy(context)
                        } catch (_: Exception) {}
                    } else {
                        NotificationEngine.notify(
                            context,
                            reqId.hashCode(),
                            "❌ Extra Time Request Declined",
                            "Parent declined request for $appName."
                        )
                    }

                    notifiedResolved.add(reqId)
                    newlyNotified = true
                }
            }

            if (newlyNotified) {
                prefs.edit().putStringSet(KEY_NOTIFIED_RESOLVED, notifiedResolved).apply()
            }
        }
    }
}
