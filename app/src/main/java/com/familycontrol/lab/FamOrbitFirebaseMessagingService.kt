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
                val minutes = data["minutes"]?.toIntOrNull() ?: 15
                val pkg = data["package_name"] ?: ""
                val appName = data["app_name"] ?: AppNameResolver.getAppName(context, pkg)

                if (pkg.isNotBlank()) {
                    ExtraTimeLedger.addExtraTime(context, pkg, minutes)
                }
                NotificationEngine.notify(
                    context,
                    1001,
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
                val appName = data["app_name"] ?: "Requested App"
                NotificationEngine.notify(
                    context,
                    1002,
                    "❌ Extra Time Declined",
                    "Parent declined extra time request for $appName."
                )
            }

            "NEW_TIME_REQUEST" -> {
                val childName = data["child_name"] ?: "Child"
                val minutes = data["minutes"] ?: "15"
                val appName = data["app_name"] ?: "Screen Time"
                val reason = data["reason"] ?: ""
                val reqId = data["request_id"] ?: System.currentTimeMillis().toString()

                NotificationEngine.notify(
                    context,
                    reqId.hashCode(),
                    "⏳ New Request from $childName",
                    "Requested +${minutes}m for $appName: \"$reason\""
                )
                try {
                    RequestPollEngine.checkOnce(context)
                } catch (_: Exception) {}
            }

            "EMERGENCY_ALERT" -> {
                val alertMsg = data["message"] ?: "🚨 SOS Emergency Alert from Parent"
                NotificationEngine.notify(
                    context,
                    9999,
                    "🚨 SOS Emergency Alert",
                    alertMsg
                )
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
