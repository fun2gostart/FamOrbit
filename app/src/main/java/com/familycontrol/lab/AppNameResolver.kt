package com.familycontrol.lab

import android.content.Context
import android.content.pm.PackageManager

object AppNameResolver {

    private val knownDictionary = mapOf(
        "com.instagram.android" to "Instagram",
        "com.jio.jioPlay.tv" to "JioPlayTV",
        "com.netflix.mediaclient" to "Netflix",
        "com.google.android.youtube" to "YouTube",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.android.chrome" to "Chrome",
        "com.whatsapp" to "WhatsApp",
        "com.roblox.client" to "Roblox",
        "com.android.camera2" to "Camera",
        "com.google.android.apps.nexuslauncher" to "Home Screen"
    )

    fun getAppName(context: Context, packageName: String?): String {
        if (packageName.isNullOrBlank()) return "Daily Screen Time"

        val known = knownDictionary[packageName]
        if (known != null) return known

        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            val parts = packageName.split('.')
            val rawName = if (parts.size >= 2) parts[parts.size - 1] else packageName
            rawName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}
