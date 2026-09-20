package com.familycontrol.lab

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle

object WebFilterEngine {
    private const val PREFS = "parent_control"
    private const val WEB_FILTER_KEY = "web_filter_enabled"
    private const val CHROME_PACKAGE = "com.android.chrome"

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(WEB_FILTER_KEY, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(WEB_FILTER_KEY, enabled).apply()
        applyWebRestrictions(context, enabled)
    }

    val BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser",
        "com.opera.browser",
        "com.brave.browser"
    )

    private val DEFAULT_BLOCKED_KEYWORDS = listOf(
        "gambling", "porn", "adult", "betting", "casino", "poker", "xxx"
    )

    fun isBlockedUrl(context: Context, rawUrl: String): Boolean {
        if (!isEnabled(context)) return false
        if (rawUrl.isBlank()) return false
        val url = rawUrl.lowercase().trim()
        return DEFAULT_BLOCKED_KEYWORDS.any { keyword -> url.contains(keyword) }
    }

    fun applyWebRestrictions(context: Context, enabled: Boolean) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, LabDeviceAdminReceiver::class.java)

        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        val bundle = Bundle()
        if (enabled) {
            bundle.putBoolean("ForceGoogleSafeSearch", true)
            bundle.putBoolean("ForceYouTubeRestrict", true)
            bundle.putStringArray(
                "URLBlocklist",
                arrayOf("*gambling*", "*porn*", "*adult*", "*betting*")
            )
        } else {
            bundle.putBoolean("ForceGoogleSafeSearch", false)
            bundle.putBoolean("ForceYouTubeRestrict", false)
            bundle.putStringArray("URLBlocklist", arrayOf<String>())
        }

        try {
            dpm.setApplicationRestrictions(admin, CHROME_PACKAGE, bundle)
            EventLog.record(context, "WEB_FILTER_UPDATED enabled=$enabled")
        } catch (e: Exception) {
            EventLog.record(context, "WEB_FILTER_ERROR ${e.message}")
        }
    }
}
