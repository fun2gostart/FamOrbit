package com.familycontrol.lab

import android.content.Context
import android.os.Build
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

    fun serverDeviceId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(DEVICE_ID, null)

    private const val DEVICE_ROLE = "device_role"
    private const val PAIRING_CODE = "pairing_code"

    const val ROLE_UNSET = "UNSET"
    const val ROLE_PARENT = "PARENT"
    const val ROLE_CHILD = "CHILD"

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

    fun generatePairingCode(context: Context): ApiResponse {
        val familyId = serverFamilyId(context)
            ?: return ApiResponse(false, 0, "", "Parent device is not registered with Cloud")
        val childId = serverChildId(context)
            ?: return ApiResponse(false, 0, "", "Parent child account is missing")

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

                EventLog.record(context, "CHILD_PAIRED_SUCCESS family=$familyId child=$childId device=$deviceId")
                return ApiResponse(true, response.code, response.body)
            } catch (e: Exception) {
                return ApiResponse(false, 500, "", "Failed to parse cloud response: ${e.message}")
            }
        }
        val errMessage = try {
            JSONObject(response.body).optString("detail", response.error)
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
        rules: JSONObject
    ): ApiResponse {
        val familyId = serverFamilyId(context)
            ?: return ApiResponse(false, 0, "", "Device is not registered")
        val childId = serverChildId(context)
            ?: return ApiResponse(false, 0, "", "Device is not registered")

        val activePreset = PresetModeEngine.getActivePreset(context)

        val policy = JSONObject()
            .put("mode", "standard")
            .put("source", "FamilyControl Parent Control Center")
            .put("updated_at", System.currentTimeMillis())
            .put("daily_screen_limit_minutes", dailyScreenLimitMinutes)
            .put("active_preset", activePreset)
            .put("rules", rules)

        val response = post(
            context,
            "/api/policies",
            JSONObject()
                .put("family_id", familyId)
                .put("child_id", childId)
                .put("policy_json", policy)
        )

        if (response.ok) EventLog.record(context, "PARENT_POLICY_SAVED preset=$activePreset")
        return response
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

    fun getTimeRequests(context: Context): ApiResponse {
        val childId = ensureRegistration(context)
            ?: return ApiResponse(false, 0, "", "Device is not paired")
        return get(context, "/api/time-requests/$childId")
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

    fun setInstantLock(context: Context, locked: Boolean): ApiResponse {
        val childId = serverChildId(context)
            ?: return ApiResponse(false, 0, "", "Device is not paired")
        return post(
            context,
            "/api/children/$childId/instant-lock",
            JSONObject().put("locked", locked)
        )
    }

    fun getInstantLock(context: Context): ApiResponse {
        val childId = serverChildId(context)
            ?: return ApiResponse(false, 0, "", "Device is not paired")
        return get(context, "/api/children/$childId/instant-lock")
    }

    fun health(context: Context): ApiResponse =
        get(context, "/health")

    private fun get(context: Context, path: String): ApiResponse =
        request(context, "GET", path, null)

    private fun post(context: Context, path: String, json: JSONObject): ApiResponse =
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
