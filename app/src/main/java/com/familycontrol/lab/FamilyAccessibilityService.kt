package com.familycontrol.lab

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class FamilyAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var lastBlockedPkg: String? = null
    private var lastBlockedTime: Long = 0L
    private var lastCloudSyncTime: Long = 0L

    private var watchdogRunning = false
    private val watchdogLoop = object : Runnable {
        override fun run() {
            if (!watchdogRunning) return
            val currentPkg = currentObservedPackage
            if (currentPkg != null) {
                if (AccessibilityGuardEngine.isPackageBlocked(this@FamilyAccessibilityService, currentPkg)) {
                    blockAndReturnHome(currentPkg)
                    return
                }
            }
            mainHandler.postDelayed(this, 500L)
        }
    }

    private fun startWatchdog() {
        if (!watchdogRunning) {
            watchdogRunning = true
            mainHandler.removeCallbacks(watchdogLoop)
            mainHandler.post(watchdogLoop)
        }
    }

    private fun stopWatchdog() {
        watchdogRunning = false
        mainHandler.removeCallbacks(watchdogLoop)
    }

    private var currentObservedPackage: String? = null

    private fun blockAndReturnHome(pkgName: String) {
        stopWatchdog()
        currentObservedPackage = null
        AccessibilityGuardEngine.resetForegroundTracking(this)

        val now = System.currentTimeMillis()
        if (pkgName != lastBlockedPkg || (now - lastBlockedTime) > 3000L) {
            lastBlockedPkg = pkgName
            lastBlockedTime = now
            val appName = AppNameResolver.getAppName(this, pkgName)
            mainHandler.post {
                Toast.makeText(
                    applicationContext,
                    "🔒 $appName limit reached. Restricted by FamOrbit policy.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        performGlobalAction(GLOBAL_ACTION_HOME)
        EventLog.record(this, "ACCESSIBILITY_APP_BLOCKED $pkgName")

        // Immediately transmit updated usage to Cloud so Parent Device reflects the locked app instantly
        executor.execute {
            try {
                ApiClient.publishChildAppsAndTelemetry(this@FamilyAccessibilityService, force = true)
            } catch (_: Exception) {}
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val now = System.currentTimeMillis()
        if (now - lastCloudSyncTime > 15_000L && ApiClient.registered(this)) {
            lastCloudSyncTime = now
            executor.execute {
                try { PolicySyncEngine.syncAndApplyCloudPolicy(this) } catch (_: Exception) {}
                try { ApiClient.publishChildAppsAndTelemetry(this@FamilyAccessibilityService, force = false) } catch (_: Exception) {}
            }
        }

        val pkgName = event.packageName?.toString() ?: return

        // 1. Anti-Tamper Protection: Detect Settings / Uninstallation attempts
        if (isSettingsOrInstallerPkg(pkgName)) {
            if (AccessibilityGuardEngine.isParentSettingsBypassActive(this)) {
                return
            }
            checkAndBlockSettingsBypass(event)
            return
        }

        // 2. Web Content Filtering (Browser URL Inspection)
        if (WebFilterEngine.BROWSER_PACKAGES.contains(pkgName) && WebFilterEngine.isEnabled(this)) {
            checkAndBlockWebFilter(pkgName)
        }

        // 3. Track foreground package: ignore keyboards, SystemUI, and transient system overlays
        if (isTransientOrKeyboard(pkgName)) {
            // Keep existing observed package active and continue monitoring
            return
        }

        if (pkgName == packageName) {
            stopWatchdog()
            currentObservedPackage = null
            AccessibilityGuardEngine.resetForegroundTracking(this)
            return
        }

        if (isLauncherPackage(pkgName)) {
            stopWatchdog()
            currentObservedPackage = null
            AccessibilityGuardEngine.resetForegroundTracking(this)
            return
        }

        // Real user application in foreground
        if (currentObservedPackage != pkgName) {
            currentObservedPackage = pkgName
            AccessibilityGuardEngine.onForegroundPackageChanged(this, pkgName)
        }
        startWatchdog()

        // 4. Immediate App Screentime & Policy Enforcement Check
        if (AccessibilityGuardEngine.isPackageBlocked(this, pkgName)) {
            blockAndReturnHome(pkgName)
        }
    }

    private fun checkAndBlockWebFilter(pkgName: String) {
        val rootNode = rootInActiveWindow ?: return
        try {
            val addressText = findAddressBarText(rootNode)
            if (!addressText.isNullOrBlank() && WebFilterEngine.isBlockedUrl(this, addressText)) {
                val now = System.currentTimeMillis()
                if (pkgName != lastBlockedPkg || (now - lastBlockedTime) > 3000L) {
                    lastBlockedPkg = pkgName
                    lastBlockedTime = now
                    mainHandler.post {
                        Toast.makeText(
                            applicationContext,
                            "🔒 Restricted Website Blocked — Safe Search & Web Filter Active.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                performGlobalAction(GLOBAL_ACTION_HOME)
                EventLog.record(this, "WEB_FILTER_BLOCKED $pkgName url=$addressText")
            }
        } catch (_: Exception) {
        } finally {
            rootNode.recycle()
        }
    }

    private fun findAddressBarText(node: android.view.accessibility.AccessibilityNodeInfo?): String? {
        if (node == null) return null
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val className = node.className?.toString() ?: ""
        val text = node.text?.toString() ?: ""

        val isAddressNode = viewId.contains("url") || viewId.contains("location") || viewId.contains("address") ||
                viewId.contains("search") || viewId.contains("title") ||
                (className.contains("EditText") && (text.contains(".") || text.startsWith("http")))

        if (isAddressNode && text.isNotBlank()) {
            return text
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val result = findAddressBarText(child)
                child.recycle()
                if (result != null) return result
            }
        }
        return null
    }

    private fun isSettingsOrInstallerPkg(pkgName: String): Boolean {
        return pkgName == "com.android.settings" ||
                pkgName == "com.google.android.packageinstaller" ||
                pkgName == "com.android.packageinstaller"
    }

    private fun isTransientOrKeyboard(pkgName: String): Boolean {
        val lower = pkgName.lowercase()
        return lower == "com.android.systemui" ||
                lower == "android" ||
                lower == "com.google.android.gms" ||
                lower.contains("inputmethod") ||
                lower.contains("gboard") ||
                lower.contains("latin") ||
                lower.contains("keyboard") ||
                lower.contains("ime")
    }

    private fun isLauncherPackage(pkgName: String): Boolean {
        val lower = pkgName.lowercase()
        return lower.contains("launcher") ||
                lower == "com.google.android.apps.nexuslauncher" ||
                lower == "com.android.launcher3" ||
                lower == "com.sec.android.app.launcher" ||
                lower.contains("trebuchet") ||
                lower.contains("quickstep")
    }

    private fun checkAndBlockSettingsBypass(event: AccessibilityEvent) {
        val role = ApiClient.getDeviceRole(this)
        val systemGuardActive = SystemGuardEngine.isEnabled(this)
        if (role != ApiClient.ROLE_CHILD && !systemGuardActive) return

        val rootNode = rootInActiveWindow ?: return
        try {
            val textContent = StringBuilder()
            traverseNode(rootNode, textContent)
            val text = textContent.toString().lowercase()

            val isAppTargeted = text.contains("famorbit") || text.contains("familycontrol") ||
                    text.contains("accessibility") || text.contains("overlay") ||
                    text.contains("display over") || text.contains("draw over") ||
                    text.contains("usage access") || text.contains("special app access") ||
                    text.contains("device admin")

            val isActionRestricted = text.contains("uninstall") || text.contains("force stop") ||
                    text.contains("off") || text.contains("disable") || text.contains("remove") ||
                    text.contains("turn off") || text.contains("clear data") || text.contains("clear storage")

            if ((isAppTargeted && isActionRestricted) || (text.contains("accessibility") && (text.contains("famorbit") || text.contains("off")))) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                mainHandler.post {
                    Toast.makeText(
                        applicationContext,
                        "🔒 Anti-Tamper Guard Active — System settings modifications are locked on Child device.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                EventLog.record(this, "SETTINGS_BYPASS_BLOCKED")
            }
        } catch (_: Exception) {
        } finally {
            rootNode.recycle()
        }
    }

    private fun traverseNode(node: android.view.accessibility.AccessibilityNodeInfo?, builder: StringBuilder) {
        if (node == null) return
        if (node.text != null && node.text.isNotBlank()) {
            builder.append(node.text).append(" ")
        }
        if (node.contentDescription != null && node.contentDescription.isNotBlank()) {
            builder.append(node.contentDescription).append(" ")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                traverseNode(child, builder)
                child.recycle()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        EventLog.record(this, "ACCESSIBILITY_SERVICE_CONNECTED")
    }
}
