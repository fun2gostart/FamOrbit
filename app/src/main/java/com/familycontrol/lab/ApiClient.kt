package com.familycontrol.lab

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ApiResponse(
    val ok: Boolean,
    val code: Int,
    val body: String,
    val error: String? = null
)

object ApiClient {
    private const val PREFS = "api"
    private const val BASE_URL = "base_url"
    private const val FAMILY_ID = "server_family_id"
    private const val CHILD_ID = "server_child_id"
    private const val DEVICE_ID = "server_device_id"

    fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.PRODUCT.contains("sdk_gphone")
                || Build.PRODUCT.contains("google_sdk"))
    }

    fun defaultBaseUrl(): String = "https://famorbit-api.onrender.com"

    fun getBaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(BASE_URL, null)
        if (saved != null && (saved.contains("192.168") || saved.contains("10.0.2.2") || saved.contains("localhost"))) {
            setBaseUrl(context, defaultBaseUrl())
            return defaultBaseUrl()
        }
        return if (!saved.isNullOrBlank()) saved else defaultBaseUrl()
    }

    fun setBaseUrl(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(BASE_URL, value.trim().removeSuffix("/")).apply()
    }

    fun registered(context: Context): Boolean =
        serverDeviceId(context) != null

    fun serverFamilyId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(FAMILY_ID, null)

    fun serverChildId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CHILD_ID, null)

    fun setServerChildId(context: Context, childId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CHILD_ID, childId)
            .apply()
    }

    fun serverDeviceId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(DEVICE_ID, null)

    private const val DEVICE_ROLE = "device_role"
    private const val PAIRING_CODE = "pairing_code"
    private const val FCM_TOKEN = "fcm_token"

    const val ROLE_UNSET = "UNSET"
    const val ROLE_PARENT = "PARENT"
    const val ROLE_CHILD = "CHILD"

    fun getFcmToken(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(FCM_TOKEN, null)

    fun setFcmToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(FCM_TOKEN, token)
            .apply()
    }

    fun updateFcmToken(context: Context, token: String): ApiResponse {
        val deviceId = serverDeviceId(context) ?: return ApiResponse(false, 0, "", "Not registered")
        return post(
            context,
            "/api/devices/$deviceId/fcm-token",
            JSONObject().put("fcm_token", token)
        )
    }

    fun initFcmToken(context: Context) {
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    if (task.isSuccessful && task.result != null) {
                        val token = task.result
                        setFcmToken(context, token)
                        if (registered(context)) {
                            kotlin.concurrent.thread {
                                try {
                                    updateFcmToken(context, token)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            EventLog.record(context, "FCM_INIT_ERROR ${e.message}")
        }
    }

    fun getDeviceRole(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(DEVICE_ROLE, ROLE_UNSET) ?: ROLE_UNSET
    }

    fun setDeviceRole(context: Context, role: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(DEVICE_ROLE, role).apply()
    }

    fun getPairingCode(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var code = prefs.getString(PAIRING_CODE, null)
        if (code.isNullOrBlank()) {
            val familyId = serverFamilyId(context)
            code = if (!familyId.isNullOrBlank()) {
                val numericHash = Math.abs(familyId.hashCode() % 900000) + 100000
                "${numericHash.toString().substring(0, 3)}-${numericHash.toString().substring(3)}"
            } else {
                val randomCode = (100000..999999).random()
                "${randomCode.toString().substring(0, 3)}-${randomCode.toString().substring(3)}"
            }
            prefs.edit().putString(PAIRING_CODE, code).apply()
        }
        return code
    }

    fun registerChild(context: Context, displayName: String): ApiResponse {
        val familyId = serverFamilyId(context)
            ?: return ApiResponse(false, 0, "", "Family is not registered with cloud")
        val response = post(
            context,
            "/api/children",
            JSONObject()
                .put("family_id", familyId)
                .put("display_name", displayName.trim())
        )
        if (response.ok) {
            EventLog.record(context, "CHILD_REGISTERED name=$displayName")
        }
        return response
    }

    fun generatePairingCodeForChild(context: Context, childId: String): ApiResponse {
        val familyId = serverFamilyId(context)
            ?: return ApiResponse(false, 0, "", "Parent device is not registered with Cloud")

        val response = post(
            context,
            "/api/pairing/generate",
            JSONObject()
                .put("family_id", familyId)
                .put("child_id", childId)
        )
        if (response.ok) {
            try {
                val json = JSONObject(response.body)
                val code = json.getString("code")
                val formatted = if (code.length == 6) "${code.substring(0, 3)}-${code.substring(3)}" else code
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(PAIRING_CODE, formatted).apply()
                return ApiResponse(true, response.code, formatted)
            } catch (e: Exception) {
                return ApiResponse(false, 500, "", "Parsing error: ${e.message}")
            }
        }
        return response
    }

    fun generatePairingCode(context: Context): ApiResponse {
        val childId = serverChildId(context)
            ?: return ApiResponse(false, 0, "", "Parent child account is missing")
        return generatePairingCodeForChild(context, childId)
    }

    fun pairChildWithCode(context: Context, rawCode: String): ApiResponse {
        val cleanCode = rawCode.replace("-", "").trim()
        if (cleanCode.length != 6 || !cleanCode.all { it.isDigit() }) {
            return ApiResponse(false, 400, "", "Enter a valid 6-digit pairing code.")
        }
        val cloudUrl = defaultBaseUrl()
        setBaseUrl(context, cloudUrl)

        val response = post(
            context,
            "/api/pairing/pair",
            JSONObject()
                .put("code", cleanCode)
                .put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("app_version", "0.8.2")
        )
        if (response.ok) {
            try {
                val json = JSONObject(response.body)
                val familyId = json.getString("family_id")
                val childId = json.getString("child_id")
                val deviceId = json.getString("device_id")

                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(FAMILY_ID, familyId)
                    .putString(CHILD_ID, childId)
                    .putString(DEVICE_ID, deviceId)
                    .putString(DEVICE_ROLE, ROLE_CHILD)
                    .putString(PAIRING_CODE, "${cleanCode.substring(0, 3)}-${cleanCode.substring(3)}")
                    .putString(BASE_URL, cloudUrl)
                    .apply()

                try {
                    val isTab = Build.MODEL.contains("Tablet", ignoreCase = true) || Build.PRODUCT.contains("tablet", ignoreCase = true)
                    ChildDeviceManager.addOrUpdateDevice(
                        context,
                        ChildDevice(
                            deviceId = deviceId,
                            childId = childId,
                            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
                            deviceType = if (isTab) DeviceType.TABLET else DeviceType.PHONE,
                            model = Build.MODEL,
                            isOnline = true,
                            batteryPct = 90
                        )
                    )
                    publishChildAppsAndTelemetry(context, force = true)
                } catch (_: Exception) {}

                EventLog.record(context, "CHILD_PAIRED_SUCCESS family=$familyId child=$childId device=$deviceId")
                return ApiResponse(true, response.code, response.body)
            } catch (e: Exception) {
                return ApiResponse(false, 500, "", "Failed to parse cloud response: ${e.message}")
            }
        }
        val errMessage = try {
            JSONObject(response.body).optString("detail", response.error ?: "")
        } catch (_: Exception) {
            response.error
        }
        return ApiResponse(false, response.code, "", errMessage ?: "Pairing failed (${response.code})")
    }

    fun clearRegistration(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(FAMILY_ID)
            .remove(CHILD_ID)
            .remove(DEVICE_ID)
            .remove(DEVICE_ROLE)
            .remove(PAIRING_CODE)
            .apply()
    }

    fun registerDevice(context: Context): ApiResponse {
        val family = post(
            context,
            "/api/families",
            JSONObject().put("name", "FamilyControl Test Family")
        )
        if (!family.ok) return family

        val familyId = JSONObject(family.body).getString("family_id")

        val child = post(
            context,
            "/api/children",
            JSONObject()
                .put("family_id", familyId)
                .put("display_name", "Test Child")
        )
        if (!child.ok) return child

        val childId = JSONObject(child.body).getString("child_id")

        val device = post(
            context,
            "/api/devices",
            JSONObject()
                .put("child_id", childId)
                .put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("app_version", "0.8.2")
        )
        if (!device.ok) return device

        val deviceId = JSONObject(device.body).getString("device_id")

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(FAMILY_ID, familyId)
            .putString(CHILD_ID, childId)
            .putString(DEVICE_ID, deviceId)
            .apply()

        EventLog.record(context, "SERVER_DEVICE_REGISTERED")
        return ApiResponse(
            true,
            device.code,
            """{"family_id":"$familyId","child_id":"$childId","device_id":"$deviceId"}"""
        )
    }

    fun createServerPolicy(context: Context): ApiResponse {
        val rules = JSONObject()
            .put("instagram", JSONObject()
                .put("package", "com.instagram.android")
                .put("daily_limit_minutes", 30)
                .put("enabled", true))
            .put("jio_play", JSONObject()
                .put("package", "com.jio.jioPlay.tv")
                .put("daily_limit_minutes", 30)
                .put("enabled", true))
            .put("netflix", JSONObject()
                .put("package", "com.netflix.mediaclient")
                .put("daily_limit_minutes", 30)
                .put("enabled", true))
        return createServerPolicy(context, 180, rules)
    }

    fun createServerPolicy(
        context: Context,
        dailyScreenLimitMinutes: Int,
        rules: JSONObject,
        targetChildId: String? = null
    ): ApiResponse {
        val familyId = serverFamilyId(context)
            ?: return ApiResponse(false, 0, "", "Device is not registered")
        val childId = targetChildId ?: serverChildId(context)
            ?: return ApiResponse(false, 0, "", "Device is not registered")

        val activePreset = PresetModeEngine.getActivePreset(context)

        val childName = ChildProfileManager.getChildren(context).find { it.id == childId }?.name
            ?: context.getSharedPreferences("parent_control", Context.MODE_PRIVATE).getString("child_display_name", "") ?: ""

        val policy = JSONObject()
            .put("version", System.currentTimeMillis() / 1000)
            .put("parent_policy_version", System.currentTimeMillis())
            .put("source", "PARENT")
            .put("mode", "standard")
            .put("updated_at", System.currentTimeMillis())
            .put("child_display_name", childName)
            .put("daily_screen_limit_minutes", dailyScreenLimitMinutes)
            .put("active_preset", activePreset)
            .put("parent_pin", ParentSecurity.getPin(context))
            .put("rules", rules)
            .put("feature_toggles", JSONObject()
                .put("category_budgets", FeatureToggleEngine.isCategoryBudgetsEnabled(context))
                .put("piggy_bank", FeatureToggleEngine.isPiggyBankEnabled(context))
                .put("habit_badges", FeatureToggleEngine.isHabitBadgesEnabled(context))
                .put("executive_report", FeatureToggleEngine.isExecutiveReportEnabled(context))
            )

        // Preserve installed_apps and devices when parent updates policy
        val pPrefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
        val savedPackages = pPrefs.getStringSet("${childId}_app_packages", emptySet()) ?: emptySet()
        val appsArray = JSONArray()
        for (pkg in savedPackages) {
            val name = pPrefs.getString("${childId}_appname_$pkg", AppNameResolver.getAppName(context, pkg)) ?: pkg
            appsArray.put(JSONObject().put("package", pkg).put("name", name))
        }
        if (appsArray.length() > 0) {
            policy.put("installed_apps", appsArray)
        }
        val badges = BadgeEngine.getBadgesForChild(context, childId)
        val badgesArray = JSONArray()
        for (badge in badges) {
            badgesArray.put(
                JSONObject()
                    .put("id", badge.id)
                    .put("title", badge.title)
                    .put("unlocked", badge.isUnlocked)
            )
        }
        policy.put("badges", badgesArray)
        policy.put("category_budgets_config", CategoryBudgetEngine.toJson(context, childId))

        val devices = ChildDeviceManager.getDevicesForChild(context, childId)
        val devArray = JSONArray()
        devices.forEach { devArray.put(it.toJson()) }
        if (devArray.length() > 0) {
            policy.put("devices", devArray)
        }

        val response = post(
            context,
            "/api/policies",
            JSONObject()
                .put("family_id", familyId)
                .put("child_id", childId)
                .put("policy_json", policy)
        )

        if (response.ok) EventLog.record(context, "PARENT_POLICY_SAVED child=$childId name=$childName preset=$activePreset")
        return response
    }

    fun publishPolicy(context: Context, childId: String? = null): ApiResponse {
        val targetId = childId ?: serverChildId(context) ?: return ApiResponse(false, 0, "", "No target child ID")
        val prefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
        val dailyLimit = if (prefs.contains("${targetId}_daily_screen_limit")) {
            prefs.getInt("${targetId}_daily_screen_limit", 180)
        } else {
            prefs.getInt("daily_screen_limit", 180)
        }
        val savedPackages = prefs.getStringSet("${targetId}_app_packages", null)
        val rules = JSONObject()
        if (!savedPackages.isNullOrEmpty()) {
            for (pkg in savedPackages) {
                val limit = prefs.getInt("${targetId}_limit_$pkg", 30)
                val enabled = prefs.getBoolean("${targetId}_enabled_$pkg", true)
                rules.put(pkg.replace(".", "_"), JSONObject()
                    .put("package", pkg)
                    .put("daily_limit_minutes", limit)
                    .put("enabled", enabled))
            }
        }
        return createServerPolicy(context, dailyLimit, rules, targetChildId = targetId)
    }

    fun createServerPolicyForChild(
        context: Context,
        familyId: String,
        childId: String,
        childDisplayName: String,
        dailyScreenLimitMinutes: Int = 180
    ): ApiResponse {
        val policy = JSONObject()
            .put("version", System.currentTimeMillis() / 1000)
            .put("child_display_name", childDisplayName)
            .put("daily_screen_limit_minutes", dailyScreenLimitMinutes)
            .put("rules", JSONObject())
            .put("parent_pin", ParentSecurity.getPin(context))
            .put("feature_toggles", JSONObject()
                .put("category_budgets", FeatureToggleEngine.isCategoryBudgetsEnabled(context))
                .put("piggy_bank", FeatureToggleEngine.isPiggyBankEnabled(context))
                .put("habit_badges", FeatureToggleEngine.isHabitBadgesEnabled(context))
                .put("executive_report", FeatureToggleEngine.isExecutiveReportEnabled(context))
            )

        val response = post(
            context,
            "/api/policies",
            JSONObject()
                .put("family_id", familyId)
                .put("child_id", childId)
                .put("policy_json", policy)
        )
        if (response.ok) EventLog.record(context, "CHILD_INITIAL_POLICY_SAVED child=$childId name=$childDisplayName")
        return response
    }

    private const val KEY_LAST_TELEMETRY_PUBLISH = "last_telemetry_publish_ms"
    const val TELEMETRY_INTERVAL_MS = 12 * 60 * 60 * 1000L // 12 Hours

    fun publishChildAppsAndTelemetry(context: Context, force: Boolean = false): ApiResponse {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastPublished = prefs.getLong(KEY_LAST_TELEMETRY_PUBLISH, 0L)
        val now = System.currentTimeMillis()
        if (!force && (now - lastPublished < TELEMETRY_INTERVAL_MS)) {
            return ApiResponse(true, 200, "Throttled (12-hour interval active)")
        }
        prefs.edit().putLong(KEY_LAST_TELEMETRY_PUBLISH, now).apply()

        val childId = serverChildId(context) ?: return ApiResponse(false, 0, "", "No child ID")
        val deviceId = serverDeviceId(context) ?: return ApiResponse(false, 0, "", "No device ID")

        val installed = AppScanner.getInstalledApps(context)
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val batteryPct = try {
            bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 1..100 } ?: 85
        } catch (_: Exception) { 85 }

        val modelName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val isTab = Build.MODEL.contains("Tablet", ignoreCase = true) || Build.PRODUCT.contains("tablet", ignoreCase = true)
        val devType = if (isTab) "TABLET" else "PHONE"

        // Update local device cache
        ChildDeviceManager.addOrUpdateDevice(
            context,
            ChildDevice(
                deviceId = deviceId,
                childId = childId,
                deviceName = modelName,
                deviceType = if (isTab) DeviceType.TABLET else DeviceType.PHONE,
                model = Build.MODEL,
                isOnline = true,
                batteryPct = batteryPct
            )
        )

        // 1. Merge and publish complete app catalog & device profile to Cloud Policy
        val familyId = serverFamilyId(context)
        if (familyId != null) {
            try {
                val currentSync = getSync(context)
                val polJson = if (currentSync.ok) {
                    val j = JSONObject(currentSync.body)
                    j.optJSONObject("policy") ?: JSONObject()
                } else JSONObject()

                val appsArray = JSONArray()
                for (app in installed) {
                    appsArray.put(JSONObject().put("package", app.packageName).put("name", app.appName))
                }
                val devArray = JSONArray()
                devArray.put(
                    JSONObject()
                        .put("deviceId", deviceId)
                        .put("deviceName", modelName)
                        .put("deviceType", devType)
                        .put("model", Build.MODEL)
                        .put("batteryPct", batteryPct)
                        .put("isOnline", true)
                )

                polJson.put("source", "TELEMETRY")
                polJson.put("installed_apps", appsArray)
                polJson.put("devices", devArray)

                post(
                    context,
                    "/api/policies",
                    JSONObject()
                        .put("family_id", familyId)
                        .put("child_id", childId)
                        .put("policy_json", polJson)
                )
            } catch (_: Exception) {}
        }

        // 2. Transmit complete app catalog in compact chunks via telemetry requests
        val appPairs = installed.map { "${it.packageName}|${it.appName.replace("|", " ").replace("#", " ")}" }
        val chunked = appPairs.chunked(4)
        var lastRes = ApiResponse(true, 200, "OK")

        if (chunked.isEmpty()) {
            val payload = "DEV_APPS#1/1#ID:$deviceId#MDL:$modelName#TYP:$devType#BAT:$batteryPct#"
            lastRes = post(
                context,
                "/api/time-requests",
                JSONObject()
                    .put("child_id", childId)
                    .put("device_id", deviceId)
                    .put("package_name", "APP_CATALOG:$modelName")
                    .put("requested_minutes", 1)
                    .put("reason", payload.take(290))
            )
        } else {
            for ((idx, chunk) in chunked.withIndex()) {
                val encoded = chunk.joinToString(",")
                val payload = "DEV_APPS#${idx + 1}/${chunked.size}#ID:$deviceId#MDL:$modelName#TYP:$devType#BAT:$batteryPct#$encoded"
                val res = post(
                    context,
                    "/api/time-requests",
                    JSONObject()
                        .put("child_id", childId)
                        .put("device_id", deviceId)
                        .put("package_name", "APP_CATALOG:$modelName")
                        .put("requested_minutes", 1)
                        .put("reason", payload.take(290))
                )
                lastRes = res
            }
        }
        return lastRes
    }

    fun getSync(context: Context): ApiResponse {
        val deviceId = serverDeviceId(context)
            ?: return ApiResponse(false, 0, "", "Device is not registered")
        return get(context, "/api/devices/$deviceId/sync")
    }

    fun acknowledgeSync(context: Context, version: Int): ApiResponse {
        val deviceId = serverDeviceId(context)
            ?: return ApiResponse(false, 0, "", "Device is not registered")

        val response = post(
            context,
            "/api/devices/$deviceId/sync-ack",
            JSONObject()
                .put("applied_policy_version", version)
                .put("app_version", "0.8.2")
        )

        if (response.ok) EventLog.record(context, "SERVER_SYNC_ACK v$version")
        return response
    }


    fun ensureRegistration(context: Context): String? {
        var childId = serverChildId(context)
        if (childId != null) return childId
        val response = registerDevice(context)
        if (response.ok) {
            childId = serverChildId(context)
        }
        return childId
    }

    fun createTimeRequest(context: Context, minutes: Int, reason: String, packageName: String? = null): ApiResponse {
        val childId = ensureRegistration(context)
            ?: return ApiResponse(false, 0, "", "Device is not paired")
        val deviceId = serverDeviceId(context)
        return post(
            context,
            "/api/time-requests",
            JSONObject()
                .put("child_id", childId)
                .put("device_id", deviceId)
                .putOpt("package_name", packageName)
                .put("requested_minutes", minutes)
                .put("reason", reason)
        )
    }

    fun getTimeRequestsForChild(context: Context, childId: String): ApiResponse {
        if (childId.isBlank()) return ApiResponse(false, 0, "", "Invalid child ID")
        return get(context, "/api/time-requests/$childId")
    }

    fun getTimeRequests(context: Context): ApiResponse {
        val childId = ensureRegistration(context)
            ?: return ApiResponse(false, 0, "", "Device is not paired")
        return getTimeRequestsForChild(context, childId)
    }

    fun decideTimeRequest(context: Context, requestId: String, approved: Boolean): ApiResponse =
        post(
            context,
            "/api/time-requests/$requestId/decision",
            JSONObject().put("status", if (approved) "APPROVED" else "DECLINED")
        )

    fun getApprovedMinutesToday(context: Context, packageName: String? = null): ApiResponse {
        val childId = ensureRegistration(context)
            ?: return ApiResponse(false, 0, "", "Device is not paired")
        val suffix = if (packageName.isNullOrBlank()) "" else "?package_name=${java.net.URLEncoder.encode(packageName, "UTF-8")}"
        return get(context, "/api/time-requests/$childId/approved-minutes-today$suffix")
    }

    fun getAllowances(context: Context, packageName: String? = null): ApiResponse {
        val childId = ensureRegistration(context)
            ?: return ApiResponse(false, 0, "", "Device is not paired")
        val suffix = if (packageName.isNullOrBlank()) "" else "?package_name=${java.net.URLEncoder.encode(packageName, "UTF-8")}"
        return get(context, "/api/time-requests/$childId/allowances$suffix")
    }

    fun reportAllowanceConsumption(context: Context, packageName: String, consumedMinutes: Int): ApiResponse {
        val childId = ensureRegistration(context)
            ?: return ApiResponse(false, 0, "", "Device is not paired")
        return post(
            context,
            "/api/time-requests/$childId/consume-allowance",
            JSONObject()
                .put("package_name", packageName)
                .put("consumed_minutes", consumedMinutes)
        )
    }

    fun setInstantLock(context: Context, locked: Boolean, targetChildId: String? = null): ApiResponse {
        val childId = targetChildId ?: serverChildId(context)
            ?: return ApiResponse(false, 0, "", "Device is not paired")
        return post(
            context,
            "/api/children/$childId/instant-lock",
            JSONObject().put("locked", locked)
        )
    }

    fun getInstantLock(context: Context, targetChildId: String? = null): ApiResponse {
        val childId = targetChildId ?: serverChildId(context)
            ?: return ApiResponse(false, 0, "", "Device is not paired")
        return get(context, "/api/children/$childId/instant-lock")
    }

    fun health(context: Context): ApiResponse =
        get(context, "/health")

    fun getDeviceSync(context: Context, deviceId: String): ApiResponse =
        get(context, "/api/devices/$deviceId/sync")

    fun get(context: Context, path: String): ApiResponse =
        request(context, "GET", path, null)

    fun post(context: Context, path: String, json: JSONObject): ApiResponse =
        request(context, "POST", path, json)

    private fun request(
        context: Context,
        method: String,
        path: String,
        body: JSONObject?
    ): ApiResponse {
        return try {
            val url = URL(getBaseUrl(context) + path)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5000
                readTimeout = 5000
                useCaches = false
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }

            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream
            else connection.errorStream

            val responseBody = stream?.bufferedReader()?.use { it.readText() } ?: ""
            connection.disconnect()

            ApiResponse(code in 200..299, code, responseBody)
        } catch (e: Exception) {
            ApiResponse(false, 0, "", e.message ?: e.javaClass.simpleName)
        }
    }
}
