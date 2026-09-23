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

    private fun addUnpairedDeviceId(context: Context, deviceId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_UNPAIRED_DEVICE_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(deviceId)
        prefs.edit().putStringSet(KEY_UNPAIRED_DEVICE_IDS, current).apply()
    }

    fun getDevicesForChild(context: Context, childId: String): List<ChildDevice> {
        val all = getAllDevices(context)
        return all.filter { it.childId == childId }
    }

    fun getAllDevices(context: Context): List<ChildDevice> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        val unpaired = getUnpairedDeviceIds(context)
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<ChildDevice>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let {
                    val dev = ChildDevice.fromJson(it)
                    if (!unpaired.contains(dev.deviceId)) {
                        list.add(dev)
                    }
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addOrUpdateDevice(context: Context, device: ChildDevice) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // If re-adding, remove from unpaired set
        val unpaired = getUnpairedDeviceIds(context).toMutableSet()
        if (unpaired.remove(device.deviceId)) {
            prefs.edit().putStringSet(KEY_UNPAIRED_DEVICE_IDS, unpaired).apply()
        }
        prefs.edit().putBoolean(KEY_SEEDED_PREFIX + device.childId, true).apply()

        val all = getAllDevices(context).toMutableList()
        val index = all.indexOfFirst { it.deviceId == device.deviceId }
        if (index != -1) {
            all[index] = device
        } else {
            all.add(device)
        }
        saveAllDevices(context, all)
        syncDeviceCounts(context)
    }

    fun removeDevice(context: Context, childId: String, deviceId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SEEDED_PREFIX + childId, true).apply()
        addUnpairedDeviceId(context, deviceId)

        val all = getAllDevices(context).filter { it.deviceId != deviceId }
        saveAllDevices(context, all)
        syncDeviceCounts(context)
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
