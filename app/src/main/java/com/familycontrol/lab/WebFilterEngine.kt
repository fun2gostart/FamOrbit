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
        "gambling", "porn", "adult", "betting", "casino", "poker", "xxx", "sex", "hentai",
        "nsfw", "erotic", "xvideos", "xhamster", "redtube", "youporn", "brazzers", "chaturbate",
        "cam4", "onlyfans", "stripchat", "beeg", "tube8", "spankbang", "youjizz", "xnxx",
        "pornstar", "milf", "camgirl", "nude", "playboy", "penthouse"
    )

    fun isBlockedUrl(context: Context, rawText: String): Boolean {
        if (!isEnabled(context)) return false
        if (rawText.isBlank()) return false
        val text = rawText.lowercase().trim()

        // Never block search engine homepages, system tabs, or default hint text
        if (text == "google.com" || text == "www.google.com" || text == "google.co.in" ||
            text.startsWith("chrome://") || text.startsWith("about:") ||
            text == "newtab" || text.contains("search or type") || text == "about:blank") {
            return false
        }

        // Require valid URL / domain / keyword structure
        val isUrlLike = text.startsWith("http://") || text.startsWith("https://") || text.startsWith("www.") ||
                text.contains(".com") || text.contains(".net") || text.contains(".org") || text.contains(".xyz") ||
                text.contains(".bet") || text.contains(".tv") || text.contains(".site") || text.contains(".online") ||
                text.contains(".cc") || text.contains(".me") || text.contains(".live") || text.contains(".top")

        if (!isUrlLike) return false

        return DEFAULT_BLOCKED_KEYWORDS.any { keyword -> text.contains(keyword) }
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
                arrayOf(
                    "*gambling*", "*porn*", "*adult*", "*betting*", "*casino*", "*poker*", "*xxx*", "*sex*",
                    "*hentai*", "*nsfw*", "*erotic*", "*xvideos*", "*xhamster*", "*redtube*", "*youporn*",
                    "*brazzers*", "*chaturbate*", "*cam4*", "*onlyfans*", "*stripchat*", "*xnxx*", "*tube8*"
                )
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
