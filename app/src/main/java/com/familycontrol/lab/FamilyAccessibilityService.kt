package com.familycontrol.lab

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class FamilyAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastBlockedPkg: String? = null
    private var lastBlockedTime: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

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

        // 3. App Screentime & Policy Enforcement
        val now = System.currentTimeMillis()
        if (AccessibilityGuardEngine.isPackageBlocked(this, pkgName)) {
            // Rate limit toasts to avoid flooding
            if (pkgName != lastBlockedPkg || (now - lastBlockedTime) > 3000L) {
                lastBlockedPkg = pkgName
                lastBlockedTime = now
                val appName = AppNameResolver.getAppName(this, pkgName)
                mainHandler.post {
                    Toast.makeText(
                        applicationContext,
                        "🔒 $appName is currently restricted by FamOrbit policy.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            // Instantly return to Home Screen (Zero-Reset Method 2 Enforcement)
            performGlobalAction(GLOBAL_ACTION_HOME)
            EventLog.record(this, "ACCESSIBILITY_APP_BLOCKED $pkgName")
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

    private fun checkAndBlockSettingsBypass(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: return
        try {
            val textContent = StringBuilder()
            traverseNode(rootNode, textContent)
            val text = textContent.toString().lowercase()

            val isTamperAttempt = (text.contains("famorbit") || text.contains("familycontrol") || text.contains("accessibility")) &&
                    (text.contains("uninstall") || text.contains("force stop") || text.contains("off") || text.contains("disable"))

            if (isTamperAttempt) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                mainHandler.post {
                    Toast.makeText(
                        applicationContext,
                        "🔒 Settings Protection Active — Parent PIN required to modify FamOrbit protection.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                EventLog.record(this, "ACCESSIBILITY_SETTINGS_BYPASS_BLOCKED")
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
