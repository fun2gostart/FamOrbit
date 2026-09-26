package com.familycontrol.lab

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

object EmergencyLocationEngine {
    private const val TAG = "EmergencyLocationEngine"

    fun pingLocation(context: Context) {
        thread {
            try {
                captureAndSendLocation(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error in pingLocation: ${e.message}", e)
                EventLog.record(context, "EMERGENCY_LOCATION_ERROR ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun captureAndSendLocation(context: Context) {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "Location permission not granted. Sending IP fallback request to backend.")
            EventLog.record(context, "EMERGENCY_LOCATION_NO_PERMISSION")
            ApiClient.updateEmergencyLocation(context, null, null, null, gpsEnabled = false)
            return
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            Log.w(TAG, "LocationManager not available.")
            ApiClient.updateEmergencyLocation(context, null, null, null, gpsEnabled = false)
            return
        }

        val isGpsEnabled = try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) { false }

        val isNetworkEnabled = try {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) { false }

        var bestLocation: Location? = null
        if (isGpsEnabled) {
            try {
                val gpsLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (gpsLoc != null) bestLocation = gpsLoc
            } catch (_: Exception) {}
        }
        if (isNetworkEnabled) {
            try {
                val netLoc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (netLoc != null && (bestLocation == null || netLoc.time > bestLocation.time)) {
                    bestLocation = netLoc
                }
            } catch (_: Exception) {}
        }

        try {
            val passLoc = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            if (passLoc != null && (bestLocation == null || passLoc.time > bestLocation.time)) {
                bestLocation = passLoc
            }
        } catch (_: Exception) {}

        if (bestLocation != null) {
            Log.i(TAG, "Dispatching best cached location: lat=${bestLocation.latitude}, lon=${bestLocation.longitude}, acc=${bestLocation.accuracy}")
            ApiClient.updateEmergencyLocation(
                context,
                bestLocation.latitude,
                bestLocation.longitude,
                bestLocation.accuracy,
                gpsEnabled = isGpsEnabled
            )
            EventLog.record(context, "EMERGENCY_LOCATION_SENT lat=${bestLocation.latitude} lon=${bestLocation.longitude}")
        } else {
            Log.w(TAG, "No cached location fix. Sending status to backend for IP fallback.")
            ApiClient.updateEmergencyLocation(
                context,
                null,
                null,
                null,
                gpsEnabled = isGpsEnabled
            )
            EventLog.record(context, "EMERGENCY_LOCATION_FALLBACK_IP gps=$isGpsEnabled")
        }

        if (isGpsEnabled || isNetworkEnabled) {
            Handler(Looper.getMainLooper()).post {
                try {
                    val provider = if (isGpsEnabled) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
                    val listener = object : LocationListener {
                        override fun onLocationChanged(loc: Location) {
                            thread {
                                try {
                                    ApiClient.updateEmergencyLocation(
                                        context,
                                        loc.latitude,
                                        loc.longitude,
                                        loc.accuracy,
                                        gpsEnabled = isGpsEnabled
                                    )
                                    EventLog.record(context, "EMERGENCY_LOCATION_FRESH lat=${loc.latitude} lon=${loc.longitude}")
                                } catch (_: Exception) {}
                            }
                            try {
                                lm.removeUpdates(this)
                            } catch (_: Exception) {}
                        }
                        override fun onProviderDisabled(provider: String) {}
                        override fun onProviderEnabled(provider: String) {}
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    }
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                } catch (e: Exception) {
                    Log.w(TAG, "requestSingleUpdate error: ${e.message}")
                }
            }
        }
    }
}
