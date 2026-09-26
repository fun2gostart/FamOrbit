package com.familycontrol.lab

import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlin.concurrent.thread

class FamOrbitFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val context = applicationContext
        EventLog.record(context, "FCM_NEW_TOKEN_RECEIVED")
        ApiClient.setFcmToken(context, token)
        if (ApiClient.registered(context)) {
            thread {
                try {
                    ApiClient.updateFcmToken(context, token)
                } catch (e: Exception) {
                    EventLog.record(context, "FCM_TOKEN_UPDATE_ERROR ${e.message}")
                }
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val context = applicationContext
        val data = remoteMessage.data
        val action = data["action"] ?: data["type"] ?: ""

        EventLog.record(context, "FCM_PUSH_RECEIVED action=$action")

        when (action) {
            "SYNC_POLICY", "POLICY_UPDATED" -> {
                thread {
                    try {
                        PolicySyncEngine.syncAndApplyCloudPolicy(context)
                    } catch (e: Exception) {
                        EventLog.record(context, "FCM_POLICY_SYNC_ERROR ${e.message}")
                    }
                }
            }

            "INSTANT_LOCK" -> {
                val locked = data["locked"]?.toBooleanStrictOrNull() ?: true
                val pPrefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
                pPrefs.edit().putBoolean("instant_pause_enabled", locked).apply()
                if (locked) {
                    NotificationEngine.notify(
                        context,
                        2002,
                        "⏸️ Device Paused",
                        "Your parent has temporarily paused this device."
                    )
                }
                thread {
                    PolicySyncEngine.auditAndUnsuspendPackages(context)
                }
            }

            "TIME_REQUEST_APPROVED" -> {
                val pkg = data["package_name"] ?: ""
                val reason = data["reason"] ?: ""
                val rawAppName = data["app_name"] ?: ""
                if (pkg.startsWith("APP_CATALOG") || pkg == "com.familycontrol.lab" || pkg == "SYSTEM_ALERT" ||
                    reason.startsWith("DEV") || reason.startsWith("DEV_APPS") ||
                    rawAppName.startsWith("APP_CATALOG") || rawAppName.startsWith("DEV_APPS")) {
                    return
                }

                if (ApiClient.getDeviceRole(context) == ApiClient.ROLE_PARENT) {
                    return
                }

                val minutes = data["minutes"]?.toIntOrNull() ?: 15
                val appName = if (rawAppName.isNotBlank() && rawAppName != "null") rawAppName else AppNameResolver.getAppName(context, pkg)

                if (pkg.isNotBlank()) {
                    ExtraTimeLedger.addExtraTime(context, pkg, minutes)
                }
                val reqId = data["request_id"] ?: System.currentTimeMillis().toString()
                NotificationEngine.notify(
                    context,
                    reqId.hashCode(),
                    "🎉 Extra Time Approved!",
                    "Parent approved +${minutes}m for $appName! Enjoy!"
                )
                thread {
                    try {
                        PolicySyncEngine.syncAndApplyCloudPolicy(context)
                    } catch (_: Exception) {}
                }
            }

            "TIME_REQUEST_DECLINED" -> {
                if (ApiClient.getDeviceRole(context) == ApiClient.ROLE_PARENT) {
                    return
                }

                val pkg = data["package_name"] ?: ""
                val reason = data["reason"] ?: ""
                val rawAppName = data["app_name"] ?: ""
                if (pkg.startsWith("APP_CATALOG") || pkg == "com.familycontrol.lab" || pkg == "SYSTEM_ALERT" ||
                    reason.startsWith("DEV") || reason.startsWith("DEV_APPS") ||
                    rawAppName.startsWith("APP_CATALOG") || rawAppName.startsWith("DEV_APPS")) {
                    return
                }

                val appName = if (rawAppName.isNotBlank() && rawAppName != "null") rawAppName else AppNameResolver.getAppName(context, pkg).ifBlank { "Requested App" }
                val reqId = data["request_id"] ?: System.currentTimeMillis().toString()
                NotificationEngine.notify(
                    context,
                    reqId.hashCode(),
                    "❌ Extra Time Declined",
                    "Parent declined extra time request for $appName."
                )
            }

            "NEW_TIME_REQUEST" -> {
                val pkg = data["package_name"] ?: ""
                val reason = data["reason"] ?: ""
                val rawAppName = data["app_name"] ?: ""
                if (pkg.startsWith("APP_CATALOG") || pkg == "com.familycontrol.lab" || pkg == "SYSTEM_ALERT" ||
                    reason.startsWith("DEV") || reason.startsWith("DEV_APPS") ||
                    rawAppName.startsWith("APP_CATALOG") || rawAppName.startsWith("DEV_APPS")) {
                    return
                }

                val childName = data["child_name"] ?: "Child"
                val minutes = data["minutes"] ?: "15"
                val appName = if (rawAppName.isNotBlank() && rawAppName != "null") rawAppName else "Screen Time"
                val reqId = data["request_id"] ?: System.currentTimeMillis().toString()

                val isAlreadyNotified = RequestPollEngine.isPendingNotified(context, reqId)
                if (!isAlreadyNotified) {
                    RequestPollEngine.markPendingNotified(context, reqId)

                    NotificationEngine.notify(
                        context,
                        reqId.hashCode(),
                        "⏳ New Request from $childName",
                        "Requested +${minutes}m for $appName: \"$reason\""
                    )
                }
                try {
                    RequestPollEngine.checkOnce(context)
                } catch (_: Exception) {}
            }

            "EMERGENCY_ALERT" -> {
                val isParent = ApiClient.getDeviceRole(context) == ApiClient.ROLE_PARENT
                if (isParent) return

                val alertMsg = data["message"] ?: "🚨 SOS Emergency Alert from Parent"
                val level = data["level"] ?: "ALERT"
                val parentPhone = data["parent_phone"] ?: ""
                val isSiren = (level == "SIREN")

                // 1. Immediately trigger silent location ping
                EmergencyLocationEngine.pingLocation(context)

                // 2. If escalated to SIREN, start loud alarm
                if (isSiren) {
                    EmergencySirenEngine.startSiren(context)
                }

                // 3. Post notification
                NotificationEngine.notify(
                    context,
                    9999,
                    if (isSiren) "🔊 URGENT LOUD SIREN ALERT" else "🚨 SOS Emergency Alert",
                    alertMsg
                )

                // 4. Launch full-screen takeover activity
                try {
                    EmergencySOSActivity.start(
                        context = context,
                        message = alertMsg,
                        parentPhone = parentPhone,
                        isSiren = isSiren
                    )
                } catch (e: Exception) {
                    EventLog.record(context, "EMERGENCY_LAUNCH_ERROR ${e.message}")
                }
            }

            "EMERGENCY_ACK" -> {
                val isParent = ApiClient.getDeviceRole(context) == ApiClient.ROLE_PARENT
                if (!isParent) return

                NotificationEngine.notify(
                    context,
                    9998,
                    "Child Confirmed Safe ✅",
                    "Your child has acknowledged the emergency alert and marked themselves as safe."
                )
            }

            "EMERGENCY_LOCATION" -> {
                val isParent = ApiClient.getDeviceRole(context) == ApiClient.ROLE_PARENT
                if (!isParent) return

                val lat = data["latitude"] ?: ""
                val lng = data["longitude"] ?: ""
                if (lat.isNotBlank() && lng.isNotBlank()) {
                    NotificationEngine.notify(
                        context,
                        9997,
                        "📍 Child Emergency Location Updated",
                        "Location coordinates: $lat, $lng"
                    )
                }
            }

            else -> {
                // Fallback: sync policy on any unspecified push
                thread {
                    try {
                        PolicySyncEngine.syncAndApplyCloudPolicy(context)
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
