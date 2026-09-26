package com.familycontrol.lab

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.json.JSONObject

object RequestPollEngine {
    private var scheduler: ScheduledExecutorService? = null
    const val PREFS = "request_poll_prefs"
    const val KEY_NOTIFIED_PENDING = "notified_pending_ids"
    const val KEY_NOTIFIED_RESOLVED = "notified_resolved_ids"

    fun isPendingNotified(context: Context, reqId: String): Boolean {
        if (reqId.isBlank()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_NOTIFIED_PENDING, emptySet())?.contains(reqId) == true
    }

    fun markPendingNotified(context: Context, reqId: String) {
        if (reqId.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_NOTIFIED_PENDING, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (set.add(reqId)) {
            prefs.edit().putStringSet(KEY_NOTIFIED_PENDING, set).apply()
        }
    }

    fun isResolvedNotified(context: Context, reqId: String): Boolean {
        if (reqId.isBlank()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_NOTIFIED_RESOLVED, emptySet())?.contains(reqId) == true
    }

    fun markResolvedNotified(context: Context, reqId: String) {
        if (reqId.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_NOTIFIED_RESOLVED, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (set.add(reqId)) {
            prefs.edit().putStringSet(KEY_NOTIFIED_RESOLVED, set).apply()
        }
    }

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
                // 1. Actively refresh app catalog and screen time from all paired devices for this child
                val existingDevices = ChildDeviceManager.getDevicesForChild(context, child.id)
                for (dev in existingDevices) {
                    if (dev.deviceId.isNotBlank()) {
                        try {
                            val syncRes = ApiClient.getDeviceSync(context, dev.deviceId)
                            if (syncRes.ok) {
                                val syncJson = JSONObject(syncRes.body)
                                val pol = syncJson.optJSONObject("policy")
                                val todayScreenTime = pol?.optLong("today_screen_time_minutes", -1L) ?: -1L
                                if (todayScreenTime >= 0) {
                                    val pPrefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
                                    pPrefs.edit().putLong("${child.id}_today_screen_time", todayScreenTime).apply()
                                }
                                val installedApps = pol?.optJSONArray("installed_apps")
                                if (installedApps != null && installedApps.length() > 0) {
                                    val pPrefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
                                    val pkgs = pPrefs.getStringSet("${child.id}_app_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
                                    val editor = pPrefs.edit()
                                    for (j in 0 until installedApps.length()) {
                                        val appObj = installedApps.optJSONObject(j) ?: continue
                                        val p = appObj.optString("package")
                                        val n = appObj.optString("name").ifBlank { AppNameResolver.getAppName(context, p) }
                                        val m = appObj.optLong("minutes", 0L)
                                        if (p.isNotBlank()) {
                                            pkgs.add(p)
                                            editor.putString("${child.id}_appname_$p", n)
                                            editor.putLong("${child.id}_appused_$p", m)
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
                    val isInternalTelemetry = pkg?.startsWith("APP_CATALOG") == true ||
                            reason.startsWith("DEV:") ||
                            reason.startsWith("DEV_INFO") ||
                            reason.startsWith("DEV_APPS#") ||
                            pkg == "com.familycontrol.lab" ||
                            pkg == "SYSTEM_ALERT" ||
                            reason.startsWith("Child device online")

                    if (isInternalTelemetry || reason.startsWith("DEV:") || reason.startsWith("DEV_INFO") || reason.startsWith("DEV_APPS#") || pkg?.startsWith("APP_CATALOG") == true) {
                        var devModel = "${child.name}'s Device"
                        var devType = DeviceType.PHONE
                        var devBattery = if (minutes in 1..100) minutes else 85
                        var devId = req.optString("device_id").takeIf { it.isNotBlank() && it != "null" }

                        if (reason.startsWith("DEV_APPS#") || reason.startsWith("DEV_INFO#")) {
                            try {
                                if (reason.contains("#ID:")) {
                                    val idPart = reason.substringAfter("#ID:").substringBefore("#")
                                    if (idPart.isNotBlank()) devId = idPart
                                }
                                if (reason.contains("#MDL:")) {
                                    val mdlPart = reason.substringAfter("#MDL:").substringBefore("#")
                                    if (mdlPart.isNotBlank()) devModel = mdlPart
                                }
                                if (reason.contains("#TYP:")) {
                                    val typPart = reason.substringAfter("#TYP:").substringBefore("#")
                                    if (typPart == "TABLET") devType = DeviceType.TABLET
                                }
                                if (reason.contains("#BAT:")) {
                                    val batPart = reason.substringAfter("#BAT:").substringBefore("#")
                                    devBattery = batPart.toIntOrNull() ?: devBattery
                                }
                                if (reason.contains("#SCR:")) {
                                    val scrPart = reason.substringAfter("#SCR:").substringBefore("#")
                                    val scrMins = scrPart.toLongOrNull()
                                    if (scrMins != null && scrMins >= 0) {
                                        context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
                                            .edit().putLong("${child.id}_today_screen_time", scrMins).apply()
                                    }
                                }
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
                        } else if (pkg?.startsWith("APP_CATALOG") == true) {
                            devModel = pkg.substringAfterLast("#").ifBlank { "${child.name}'s Device" }
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

                        // 1. Extract apps directly from chunked DEV_APPS# payload with dynamic reassembly
                        try {
                            val rawApps = if (reason.contains("#APPS:")) {
                                reason.substringAfter("#APPS:")
                            } else if (reason.contains("#APPS")) {
                                reason.substringAfter("#APPS")
                            } else if (reason.startsWith("DEV_APPS#")) {
                                reason.substringAfterLast("#")
                            } else ""

                            val pPrefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)

                            if (reason.startsWith("DEV_APPS#")) {
                                val chunkHeader = reason.substringAfter("DEV_APPS#").substringBefore("#")
                                val chunkNum = chunkHeader.substringBefore("/").toIntOrNull() ?: 1
                                val totalChunks = chunkHeader.substringAfter("/").toIntOrNull() ?: 1

                                val editor = pPrefs.edit()
                                editor.putString("${child.id}_chunk_${chunkNum}_of_${totalChunks}", rawApps)
                                editor.putInt("${child.id}_catalog_total_chunks", totalChunks)
                                editor.apply()

                                // Reassemble catalog across all received chunks for this child
                                val allReceivedChunks = mutableListOf<String>()
                                for (c in 1..totalChunks) {
                                    val chunkData = pPrefs.getString("${child.id}_chunk_${c}_of_${totalChunks}", null)
                                    if (chunkData != null) {
                                        allReceivedChunks.add(chunkData)
                                    }
                                }

                                if (allReceivedChunks.isNotEmpty()) {
                                    val newPackageSet = mutableSetOf<String>()
                                    val appNameMap = mutableMapOf<String, String>()
                                    for (chunkData in allReceivedChunks) {
                                        for (entry in chunkData.split(",")) {
                                            val pair = entry.split("|")
                                            if (pair.isNotEmpty() && pair[0].isNotBlank()) {
                                                val p = pair[0].trim()
                                                val a = if (pair.size > 1 && pair[1].isNotBlank()) pair[1].trim() else AppNameResolver.getAppName(context, p)
                                                if (p.contains(".") && !AppScanner.isSystemStub(p, a)) {
                                                    newPackageSet.add(p)
                                                    appNameMap[p] = a
                                                }
                                            }
                                        }
                                    }
                                    if (newPackageSet.isNotEmpty()) {
                                        val saveEditor = pPrefs.edit()
                                        saveEditor.putStringSet("${child.id}_app_packages", newPackageSet)
                                        saveEditor.putBoolean("${child.id}_has_scanned_catalog", true)
                                        for ((p, a) in appNameMap) {
                                            saveEditor.putString("${child.id}_appname_$p", a)
                                        }
                                        saveEditor.apply()
                                    }
                                }
                            } else if (rawApps.isNotBlank()) {
                                val pkgs = pPrefs.getStringSet("${child.id}_app_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
                                val editor = pPrefs.edit()
                                for (entry in rawApps.split(",")) {
                                    val pair = entry.split("|")
                                    if (pair.isNotEmpty() && pair[0].isNotBlank()) {
                                        val p = pair[0].trim()
                                        val a = if (pair.size > 1 && pair[1].isNotBlank()) pair[1].trim() else AppNameResolver.getAppName(context, p)
                                        if (p.contains(".") && !AppScanner.isSystemStub(p, a)) {
                                            pkgs.add(p)
                                            editor.putString("${child.id}_appname_$p", a)
                                        }
                                    }
                                }
                                editor.putStringSet("${child.id}_app_packages", pkgs)
                                editor.putBoolean("${child.id}_has_scanned_catalog", true)
                                editor.apply()
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
                                            val m = appObj.optLong("minutes", 0L)
                                            if (p.isNotBlank() && p.contains(".") && !AppScanner.isSystemStub(p, n)) {
                                                pkgs.add(p)
                                                editor.putString("${child.id}_appname_$p", n)
                                                editor.putLong("${child.id}_appused_$p", m)
                                            }
                                        }
                                        editor.putStringSet("${child.id}_app_packages", pkgs)
                                        editor.putBoolean("${child.id}_has_scanned_catalog", true)
                                        editor.apply()
                                    }
                                }
                            }
                        } catch (_: Exception) {}
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

                val isTelemetry = pkg?.startsWith("APP_CATALOG") == true ||
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
