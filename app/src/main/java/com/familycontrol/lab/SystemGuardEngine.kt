package com.familycontrol.lab

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager

object SystemGuardEngine {
    private const val PREFS = "parent_control"
    private const val SYSTEM_GUARD_KEY = "system_guard_enabled"

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(SYSTEM_GUARD_KEY, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(SYSTEM_GUARD_KEY, enabled).apply()
        applyRestrictions(context, enabled)
    }

    fun applyRestrictions(context: Context, enabled: Boolean) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, LabDeviceAdminReceiver::class.java)

        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        val restrictions = mutableListOf(
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            UserManager.DISALLOW_UNINSTALL_APPS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            restrictions.add(UserManager.DISALLOW_SAFE_BOOT)
        }

        for (restriction in restrictions) {
            try {
                if (enabled) {
                    dpm.addUserRestriction(admin, restriction)
                } else {
                    dpm.clearUserRestriction(admin, restriction)
                }
            } catch (e: Exception) {
                EventLog.record(context, "SYSTEM_GUARD_ERROR $restriction: ${e.message}")
            }
        }
    }
}
