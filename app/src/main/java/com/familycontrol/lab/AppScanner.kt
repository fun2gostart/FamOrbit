package com.familycontrol.lab

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

data class InstalledAppInfo(
    val appName: String,
    val packageName: String
)

object AppScanner {

    fun isSystemStub(packageName: String, appName: String): Boolean {
        val lowerPkg = packageName.lowercase()
        val lowerName = appName.lowercase()
        if (lowerPkg.contains("developer") ||
            lowerPkg.contains("verifier") ||
            lowerPkg.contains("systemui") ||
            lowerPkg.contains("permissioncontroller") ||
            lowerPkg.contains("captiveportal") ||
            lowerPkg.contains("sdk_gphone") ||
            lowerPkg.contains("goldfish") ||
            lowerPkg.contains("emulator") ||
            lowerPkg.contains("android.switch") ||
            lowerPkg.contains("inputmethod") ||
            lowerPkg.contains("keychain") ||
            lowerPkg.contains("providers") ||
            lowerPkg.contains("overlay")
        ) return true

        if (lowerName.contains("developer verifier") ||
            lowerName.contains("android switch") ||
            lowerName.contains("system key verifier") ||
            lowerName.contains("shell") ||
            lowerName.contains("system ui") ||
            lowerName.contains("android system") ||
            lowerName.contains("sim toolkit") ||
            lowerName.contains("pacprocessor") ||
            lowerName.contains("certinstaller")
        ) return true

        return false
    }

    fun getInstalledApps(context: Context): List<InstalledAppInfo> {
        val pm = context.packageManager
        val list = mutableListOf<InstalledAppInfo>()
        val seen = mutableSetOf<String>()

        // 1. Query launcher intent activities
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = try {
            pm.queryIntentActivities(mainIntent, 0)
        } catch (_: Exception) {
            emptyList()
        }

        for (info in resolveInfos) {
            val pkg = info.activityInfo.packageName
            if (pkg != context.packageName && seen.add(pkg)) {
                val label = try {
                    info.loadLabel(pm).toString()
                } catch (_: Exception) {
                    AppNameResolver.getAppName(context, pkg)
                }
                if (!isSystemStub(pkg, label)) {
                    list.add(InstalledAppInfo(label, pkg))
                }
            }
        }

        // 2. Query installed packages to ensure complete coverage for user-installed apps
        try {
            val installedPackages = pm.getInstalledPackages(0)
            for (pkgInfo in installedPackages) {
                val pkg = pkgInfo.packageName
                if (pkg != context.packageName && seen.add(pkg)) {
                    val appInfo = pkgInfo.applicationInfo ?: continue
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    if (!isSystem) {
                        val label = try {
                            pm.getApplicationLabel(appInfo).toString()
                        } catch (_: Exception) {
                            AppNameResolver.getAppName(context, pkg)
                        }
                        if (!isSystemStub(pkg, label)) {
                            list.add(InstalledAppInfo(label, pkg))
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return list.sortedBy { it.appName.lowercase() }
    }
}
