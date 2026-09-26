package com.familycontrol.lab

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

object EmergencySirenEngine {
    private const val TAG = "EmergencySirenEngine"
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private var previousVolume: Int? = null

    @Synchronized
    fun startSiren(context: Context) {
        if (isPlaying) {
            Log.d(TAG, "Siren already playing")
            return
        }

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
            }

            var alertUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (alertUri == null) {
                alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }
            if (alertUri == null) {
                alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }

            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                if (alertUri != null) {
                    setDataSource(context, alertUri)
                }
                isLooping = true
                prepare()
                start()
            }
            mediaPlayer = player
            isPlaying = true
            EventLog.record(context, "EMERGENCY_SIREN_STARTED")

            triggerVibration(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing siren: ${e.message}", e)
            EventLog.record(context, "EMERGENCY_SIREN_ERROR: ${e.message}")
        }
    }

    @Synchronized
    fun stopSiren(context: Context? = null) {
        if (!isPlaying && mediaPlayer == null) return

        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media player: ${e.message}")
        } finally {
            mediaPlayer = null
            isPlaying = false
        }

        if (context != null && previousVolume != null) {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume!!, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore volume: ${e.message}")
            }
            previousVolume = null
        }

        context?.let { ctx ->
            stopVibration(ctx)
            EventLog.record(ctx, "EMERGENCY_SIREN_STOPPED")
        }
    }

    fun isSirenActive(): Boolean = isPlaying

    private fun triggerVibration(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                val pattern = longArrayOf(0, 600, 300, 600, 300)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, 0)
                }
            }
        } catch (_: Exception) {}
    }

    private fun stopVibration(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.cancel()
        } catch (_: Exception) {}
    }
}
