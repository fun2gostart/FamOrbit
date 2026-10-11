package com.familycontrol.lab

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
            // Save battery: do not consume CPU cycles when screen is off
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (pm != null && !pm.isInteractive) {
                mainHandler.postDelayed(this, 3000L)
                return
            }

            val currentPkg = currentObservedPackage
            if (currentPkg != null) {
                if (AccessibilityGuardEngine.isPackageBlocked(this@FamilyAccessibilityService, currentPkg)) {
                    blockAndReturnHome(currentPkg)
                    return
                }
            }

            // Dismiss any floating Picture-in-Picture windows for blocked apps
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    val windowList = windows
                    for (w in windowList) {
                        if (w.isInPictureInPictureMode) {
                            val pipRoot = w.root
                            val pipPkg = pipRoot?.packageName?.toString()
                            if (pipPkg != null && AccessibilityGuardEngine.isPackageBlocked(this@FamilyAccessibilityService, pipPkg)) {
                                dismissPipWindow(pipRoot)
                                performGlobalAction(GLOBAL_ACTION_BACK)
                            }
                            pipRoot?.recycle()
                        }
                    }
                } catch (_: Exception) {}
            }

            // 1.5s interval is smooth enough for time limit enforcement without draining battery
            mainHandler.postDelayed(this, 1500L)
        }
    }

    private fun dismissPipWindow(root: AccessibilityNodeInfo?) {
        if (root == null) return
        val desc = (root.contentDescription?.toString() ?: "").lowercase()
        val viewId = (root.viewIdResourceName?.toString() ?: "").lowercase()
        if (desc.contains("close") || desc.contains("dismiss") || desc.contains("remove") ||
            viewId.contains("close") || viewId.contains("dismiss")) {
            root.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            dismissPipWindow(child)
            child.recycle()
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

    private var activeToast: Toast? = null

    private fun blockAndReturnHome(pkgName: String) {
        stopWatchdog()
        currentObservedPackage = null
        AccessibilityGuardEngine.resetForegroundTracking(this)

        val now = System.currentTimeMillis()
        if ((now - lastBlockedTime) > 3500L) {
            lastBlockedPkg = pkgName
            lastBlockedTime = now
            val blockReason = AccessibilityGuardEngine.getBlockReason(this, pkgName)
            mainHandler.post {
                try {
                    activeToast?.cancel()
                    activeToast = Toast.makeText(
                        applicationContext,
                        blockReason,
                        Toast.LENGTH_SHORT
                    )
                    activeToast?.show()
                } catch (_: Exception) {}
            }
        }

        // Return user to home screen cleanly without double-transition flickering
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
        val lower = pkgName.lowercase()
        return lower == "com.android.settings" ||
                lower == "com.google.android.packageinstaller" ||
                lower == "com.android.packageinstaller" ||
                lower.contains("packageinstaller")
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

            // 1. Explicit uninstallation attempt targeting FamOrbit / FamilyControl
            val isTargetingOurApp = text.contains("famorbit") || text.contains("familycontrol")
            val isUninstallAttempt = text.contains("uninstall") || text.contains("delete app") ||
                    text.contains("clear data") || text.contains("clear storage") || text.contains("force stop")

            // 2. Sensitive permission tampering: Accessibility, Device Admin, or Special App Access for FamOrbit
            val isActivationFlow = text.contains("activate") || text.contains("enable") || text.contains("turn on")
            val isAccessibilityTampering = text.contains("accessibility") && !isActivationFlow && (isTargetingOurApp || (text.contains("turn off") && isTargetingOurApp))
            val isDeviceAdminTampering = (text.contains("device admin") || text.contains("device administrator")) &&
                    (text.contains("deactivate") || text.contains("remove") || (text.contains("disable") && isTargetingOurApp))
            val isPermissionBypass = isTargetingOurApp && (text.contains("permissions") || text.contains("display over") || text.contains("usage access")) && (text.contains("deny") || text.contains("turn off") || text.contains("dont allow"))

            if ((isTargetingOurApp && isUninstallAttempt) || isAccessibilityTampering || isDeviceAdminTampering || isPermissionBypass) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                mainHandler.post {
                    Toast.makeText(
                        applicationContext,
                        "🔒 Anti-Tamper Guard Active — Cannot modify or uninstall FamOrbit on child device.",
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
