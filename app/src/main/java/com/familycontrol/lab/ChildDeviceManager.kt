package com.familycontrol.lab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class DeviceType {
    PHONE,
    TABLET
}

data class ChildDevice(
    val deviceId: String,
    val childId: String,
    val deviceName: String,
    val deviceType: DeviceType,
    val model: String,
    val appVersion: String = "1.3.0",
    val lastSeenEpochMs: Long = System.currentTimeMillis(),
    val isOnline: Boolean = true,
    val batteryPct: Int = 85
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("deviceId", deviceId)
        put("childId", childId)
        put("deviceName", deviceName)
        put("deviceType", deviceType.name)
        put("model", model)
        put("appVersion", appVersion)
        put("lastSeenEpochMs", lastSeenEpochMs)
        put("isOnline", isOnline)
        put("batteryPct", batteryPct)
    }

    companion object {
        fun fromJson(json: JSONObject): ChildDevice = ChildDevice(
            deviceId = json.optString("deviceId"),
            childId = json.optString("childId"),
            deviceName = json.optString("deviceName"),
            deviceType = try { DeviceType.valueOf(json.optString("deviceType", "PHONE")) } catch (_: Exception) { DeviceType.PHONE },
            model = json.optString("model"),
            appVersion = json.optString("appVersion", "1.3.0"),
            lastSeenEpochMs = json.optLong("lastSeenEpochMs", System.currentTimeMillis()),
            isOnline = json.optBoolean("isOnline", true),
            batteryPct = json.optInt("batteryPct", 85)
        )
    }
}

object ChildDeviceManager {
    private const val PREFS = "family_control_devices_prefs"
    private const val KEY_DEVICES = "child_devices_list"
    private const val KEY_UNPAIRED_DEVICE_IDS = "unpaired_device_ids_v1"
    private const val KEY_SEEDED_PREFIX = "has_seeded_devices_"

    fun getUnpairedDeviceIds(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_UNPAIRED_DEVICE_IDS, emptySet()) ?: emptySet()
    }

    fun isInitialSyncActive(context: Context, deviceId: String): Boolean {
        if (deviceId.isBlank()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("paired_at_$deviceId")) {
            return false
        }
        val pairedAt = prefs.getLong("paired_at_$deviceId", 0L)
        val elapsed = System.currentTimeMillis() - pairedAt
        return elapsed in 0 until (2 * 60 * 1000L) // 2 minutes post sync
    }

    private fun addUnpairedDeviceId(context: Context, deviceId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_UNPAIRED_DEVICE_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(deviceId)
        prefs.edit().putStringSet(KEY_UNPAIRED_DEVICE_IDS, current).apply()
    }

    fun isSelfDevice(context: Context, modelOrName: String?, devId: String?): Boolean {
        if (ApiClient.getDeviceRole(context) != ApiClient.ROLE_PARENT) return false
        val myHardware = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
        val myModel = android.os.Build.MODEL.trim()
        val myDeviceId = ApiClient.serverDeviceId(context)
        val nameMatch = modelOrName != null && (
            modelOrName.equals(myHardware, ignoreCase = true) ||
            modelOrName.equals(myModel, ignoreCase = true) ||
            modelOrName.contains(myModel, ignoreCase = true)
        )
        val idMatch = !myDeviceId.isNullOrBlank() && devId != null && devId == myDeviceId
        return nameMatch || idMatch
    }

    fun pruneSelfDevice(context: Context) {
        if (ApiClient.getDeviceRole(context) != ApiClient.ROLE_PARENT) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_DEVICES, null) ?: return
        try {
            val arr = JSONArray(raw)
            val filtered = mutableListOf<ChildDevice>()
            var removed = false
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let {
                    val dev = ChildDevice.fromJson(it)
                    if (isSelfDevice(context, dev.model, dev.deviceId) || isSelfDevice(context, dev.deviceName, dev.deviceId)) {
                        addUnpairedDeviceId(context, dev.deviceId)
                        removed = true
                    } else {
                        filtered.add(dev)
                    }
                }
            }
            if (removed) {
                saveAllDevices(context, filtered)
                syncDeviceCounts(context)
            }
        } catch (_: Exception) {}
    }

    fun getDevicesForChild(context: Context, childId: String): List<ChildDevice> {
        val all = getAllDevices(context)
        return all.filter { it.childId == childId }
    }

    fun getAllDevices(context: Context): List<ChildDevice> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        val unpaired = getUnpairedDeviceIds(context)
        val isParent = ApiClient.getDeviceRole(context) == ApiClient.ROLE_PARENT
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<ChildDevice>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let {
                    val dev = ChildDevice.fromJson(it)
                    if (!unpaired.contains(dev.deviceId)) {
                        if (!isParent || (!isSelfDevice(context, dev.model, dev.deviceId) && !isSelfDevice(context, dev.deviceName, dev.deviceId))) {
                            list.add(dev)
                        }
                    }
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addOrUpdateDevice(context: Context, device: ChildDevice) {
        if (isSelfDevice(context, device.model, device.deviceId) || isSelfDevice(context, device.deviceName, device.deviceId)) {
            // Parent controller physical device must NEVER be registered or added as a child's device
            return
        }
        val unpaired = getUnpairedDeviceIds(context)
        if (unpaired.contains(device.deviceId)) {
            // Device was explicitly unpaired by parent; do NOT auto-resurrect from background telemetry poll!
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SEEDED_PREFIX + device.childId, true).apply()

        val all = getAllDevices(context).toMutableList()
        // Enforce strictly 1 device per child profile: remove any other device registered under this childId
        all.removeAll { it.childId == device.childId && it.deviceId != device.deviceId }
        val index = all.indexOfFirst { it.deviceId == device.deviceId }
        if (index != -1) {
            all[index] = device
        } else {
            if (!prefs.contains("paired_at_${device.deviceId}")) {
                prefs.edit().putLong("paired_at_${device.deviceId}", System.currentTimeMillis()).apply()
            }
            all.add(device)
        }
        saveAllDevices(context, all)
        syncDeviceCounts(context)
    }

    fun clearChildTelemetry(context: Context, childId: String) {
        if (childId.isBlank()) return
        val pPrefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
        val editor = pPrefs.edit()

        // 1. Reset today's screen time to 0
        editor.putLong("${childId}_today_screen_time", 0L)
        editor.remove("${childId}_telemetry_date")
        editor.remove("${childId}_has_scanned_catalog")

        // 2. Remove all app packages and app usage keys
        val pkgs = pPrefs.getStringSet("${childId}_app_packages", emptySet()) ?: emptySet()
        for (pkg in pkgs) {
            editor.remove("${childId}_appname_$pkg")
            editor.remove("${childId}_appused_$pkg")
            editor.remove("${childId}_extra_$pkg")
        }
        editor.remove("${childId}_app_packages")

        // 3. Reset persistent bonus minutes cache
        editor.putInt("${childId}_bonus_minutes_today", 0)
        editor.remove("${childId}_bonus_updated_at")
        editor.remove("${childId}_extra_time_revoked_at")

        editor.apply()
    }

    fun prepareDeviceReplacement(context: Context, childId: String) {
        clearChildTelemetry(context, childId)
    }

    fun removeDevice(context: Context, childId: String, deviceId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SEEDED_PREFIX + childId, true).apply()
        addUnpairedDeviceId(context, deviceId)

        val all = getAllDevices(context).filter { it.deviceId != deviceId }
        saveAllDevices(context, all)
        syncDeviceCounts(context)

        // If no devices remain for this child profile, clear all cached telemetry immediately
        if (all.none { it.childId == childId }) {
            clearChildTelemetry(context, childId)
        }

        // Asynchronously notify backend cloud to permanently erase old device registration and telemetry
        if (deviceId.isNotBlank()) {
            kotlin.concurrent.thread {
                try {
                    ApiClient.unpairDevice(context, deviceId)
                } catch (_: Exception) {}
            }
        }
    }

    private fun saveAllDevices(context: Context, devices: List<ChildDevice>) {
        val arr = JSONArray()
        devices.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEVICES, arr.toString())
            .apply()
    }

    fun syncDeviceCounts(context: Context) {
        val allDevices = getAllDevices(context)
        val children = ChildProfileManager.getChildren(context)
        children.forEach { child ->
            val count = allDevices.count { it.childId == child.id }
            if (count != child.deviceCount) {
                ChildProfileManager.updateChild(context, child.copy(deviceCount = count))
            }
        }
    }

    private fun generateDefaultsForChild(context: Context, childId: String): List<ChildDevice> {
        val child = ChildProfileManager.getChildren(context).find { it.id == childId } ?: return emptyList()
        return if (child.name.contains("Maya", ignoreCase = true)) {
            listOf(
                ChildDevice(
                    deviceId = "6456a464-36f6-4c1e-8955-aebe980c209b",
                    childId = childId,
                    deviceName = child.name + "'s Pixel Tablet",
                    deviceType = DeviceType.TABLET,
                    model = "Google Pixel Tablet",
                    lastSeenEpochMs = System.currentTimeMillis(),
                    isOnline = true,
                    batteryPct = 94
                ),
                ChildDevice(
                    deviceId = "maya-phone-dev-1",
                    childId = childId,
                    deviceName = child.name + "'s Pixel 8a",
                    deviceType = DeviceType.PHONE,
                    model = "Google Pixel 8a",
                    lastSeenEpochMs = System.currentTimeMillis() - 1000 * 60 * 12,
                    isOnline = true,
                    batteryPct = 78
                )
            )
        } else {
            listOf(
                ChildDevice(
                    deviceId = "3b82883a-4093-445f-8214-48416c12d49a",
                    childId = childId,
                    deviceName = child.name + "'s Pixel 9a",
                    deviceType = DeviceType.PHONE,
                    model = "Google Pixel 9a",
                    lastSeenEpochMs = System.currentTimeMillis(),
                    isOnline = true,
                    batteryPct = 86
                )
            )
        }
    }
}
