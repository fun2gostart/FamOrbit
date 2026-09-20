package com.familycontrol.lab

import android.content.Context
import java.util.UUID

enum class SyncStatus {
    SYNCED,
    PENDING,
    OFFLINE,
    OUTDATED
}

data class SyncSnapshot(
    val familyId: String,
    val childId: String,
    val deviceId: String,
    val parentPolicyVersion: Int,
    val childPolicyVersion: Int,
    val status: SyncStatus,
    val lastSyncMillis: Long,
    val pendingChanges: Int,
    val online: Boolean,
    val detail: String
)

object SyncEngine {
    private const val PREFS = "sync"
    private const val FAMILY = "family_id"
    private const val CHILD = "child_id"
    private const val DEVICE = "device_id"
    private const val PARENT = "parent_version"
    private const val CHILD_VERSION = "child_version"
    private const val LAST_SYNC = "last_sync"
    private const val PENDING = "pending"
    private const val ONLINE = "online"

    fun snapshot(context: Context): SyncSnapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ensureIdentity(prefs)

        val parent = prefs.getInt(PARENT, ProtectionMonitor.policyVersion(context))
        val child = prefs.getInt(CHILD_VERSION, ProtectionMonitor.policyVersion(context))
        val online = prefs.getBoolean(ONLINE, true)
        val pending = prefs.getInt(PENDING, 0)
        val last = prefs.getLong(LAST_SYNC, 0L)

        val status = when {
            !online -> SyncStatus.OFFLINE
            parent > child -> SyncStatus.OUTDATED
            pending > 0 -> SyncStatus.PENDING
            last == 0L -> SyncStatus.PENDING
            else -> SyncStatus.SYNCED
        }

        val detail = when (status) {
            SyncStatus.SYNCED -> "Parent and child policy versions match"
            SyncStatus.PENDING -> "A policy change is waiting to synchronize"
            SyncStatus.OFFLINE -> "Offline; child keeps using its local policy"
            SyncStatus.OUTDATED -> "Child is behind the parent policy version"
        }

        return SyncSnapshot(
            prefs.getString(FAMILY, "") ?: "",
            prefs.getString(CHILD, "") ?: "",
            prefs.getString(DEVICE, "") ?: "",
            parent,
            child,
            status,
            last,
            pending,
            online,
            detail
        )
    }

    fun simulateParentChange(context: Context): SyncSnapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ensureIdentity(prefs)

        val current = maxOf(
            prefs.getInt(PARENT, ProtectionMonitor.policyVersion(context)),
            prefs.getInt(CHILD_VERSION, ProtectionMonitor.policyVersion(context))
        )
        val next = current + 1

        prefs.edit()
            .putInt(PARENT, next)
            .putInt(PENDING, prefs.getInt(PENDING, 0) + 1)
            .apply()

        ProtectionMonitor.incrementPolicyVersion(context)
        EventLog.record(context, "PARENT_POLICY_CHANGED v$next")
        return snapshot(context)
    }

    fun syncToChild(context: Context): SyncSnapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ensureIdentity(prefs)

        if (!prefs.getBoolean(ONLINE, true)) {
            EventLog.record(context, "POLICY_SYNC_SKIPPED_OFFLINE")
            return snapshot(context)
        }

        val parent = prefs.getInt(PARENT, ProtectionMonitor.policyVersion(context))
        prefs.edit()
            .putInt(CHILD_VERSION, parent)
            .putInt(PENDING, 0)
            .putLong(LAST_SYNC, System.currentTimeMillis())
            .apply()

        EventLog.record(context, "POLICY_SYNC_COMPLETED v$parent")
        return snapshot(context)
    }

    fun setOnline(context: Context, online: Boolean): SyncSnapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ensureIdentity(prefs)
        prefs.edit().putBoolean(ONLINE, online).apply()
        EventLog.record(context, if (online) "SYNC_NETWORK_ON" else "SYNC_NETWORK_OFF")
        return snapshot(context)
    }

    fun reset(context: Context): SyncSnapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ensureIdentity(prefs)
        val version = ProtectionMonitor.policyVersion(context)
        prefs.edit()
            .putInt(PARENT, version)
            .putInt(CHILD_VERSION, version)
            .putInt(PENDING, 0)
            .putLong(LAST_SYNC, 0L)
            .putBoolean(ONLINE, true)
            .apply()
        EventLog.record(context, "SYNC_TEST_RESET")
        return snapshot(context)
    }

    private fun ensureIdentity(prefs: android.content.SharedPreferences) {
        val editor = prefs.edit()
        if (!prefs.contains(FAMILY)) editor.putString(FAMILY, "FAM-${id()}")
        if (!prefs.contains(CHILD)) editor.putString(CHILD, "CH-${id()}")
        if (!prefs.contains(DEVICE)) editor.putString(DEVICE, "DEV-${id()}")
        if (!prefs.contains(ONLINE)) editor.putBoolean(ONLINE, true)
        editor.apply()
    }

    private fun id(): String =
        UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
}
