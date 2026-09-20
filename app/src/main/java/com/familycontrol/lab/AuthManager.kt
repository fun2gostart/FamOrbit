package com.familycontrol.lab

import android.content.Context

enum class DeviceRole {
    UNCONFIGURED,
    PARENT,
    CHILD
}

object AuthManager {
    private const val PREFS = "family_control_auth"
    private const val KEY_ROLE = "device_role"
    private const val KEY_FAMILY_ID = "family_id"
    private const val KEY_CHILD_ID = "child_id"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_PAIRED = "is_paired"

    fun getRole(context: Context): DeviceRole {
        val str = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ROLE, DeviceRole.UNCONFIGURED.name)
        return try { DeviceRole.valueOf(str ?: DeviceRole.UNCONFIGURED.name) }
        catch (_: Exception) { DeviceRole.UNCONFIGURED }
    }

    fun setRole(context: Context, role: DeviceRole) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ROLE, role.name).apply()
    }

    fun isPaired(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PAIRED, ApiClient.registered(context))

    fun savePairing(context: Context, familyId: String, childId: String, deviceId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FAMILY_ID, familyId)
            .putString(KEY_CHILD_ID, childId)
            .putString(KEY_DEVICE_ID, deviceId)
            .putBoolean(KEY_PAIRED, true)
            .putString(KEY_ROLE, DeviceRole.CHILD.name)
            .apply()
    }
}
