package com.familycontrol.lab

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EventLog {
    private const val PREFS = "events"

    fun record(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getString("items", "") ?: ""
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val lines = (listOf("$stamp | $message") + old.lines().filter { it.isNotBlank() }).take(50)
        prefs.edit().putString("items", lines.joinToString("\n")).apply()
    }

    fun read(context: Context): List<String> =
        (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("items", "") ?: "").lines().filter { it.isNotBlank() }
}
