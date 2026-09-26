package com.familycontrol.lab

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalPhone
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.concurrent.thread

class EmergencySOSActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_PARENT_PHONE = "extra_parent_phone"
        const val EXTRA_IS_SIREN = "extra_is_siren"

        fun start(
            context: Context,
            message: String = "🚨 SOS Emergency Alert from Parent",
            parentPhone: String = "",
            isSiren: Boolean = false
        ) {
            val intent = Intent(context, EmergencySOSActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_PARENT_PHONE, parentPhone)
                putExtra(EXTRA_IS_SIREN, isSiren)
            }
            context.startActivity(intent)
        }
    }

    private var isSiren = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure display turns on and displays over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "🚨 SOS Emergency Alert from Parent"
        val parentPhone = intent.getStringExtra(EXTRA_PARENT_PHONE) ?: ""
        isSiren = intent.getBooleanExtra(EXTRA_IS_SIREN, false)

        if (isSiren) {
            EmergencySirenEngine.startSiren(this)
        }

        setContent {
            EmergencySOSScreen(
                message = message,
                parentPhone = parentPhone,
                isSiren = isSiren,
                onCallParent = {
                    val dialIntent = if (parentPhone.isNotBlank()) {
                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:$parentPhone"))
                    } else {
                        Intent(Intent.ACTION_DIAL)
                    }
                    dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(dialIntent)
                },
                onSafeClicked = {
                    handleSafeConfirmation()
                },
                onCallEmergencyServices = {
                    val emIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(emIntent)
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newIsSiren = intent.getBooleanExtra(EXTRA_IS_SIREN, false)
        if (newIsSiren && !isSiren) {
            isSiren = true
            EmergencySirenEngine.startSiren(this)
        }
    }

    private fun handleSafeConfirmation() {
        EmergencySirenEngine.stopSiren(this)
        thread {
            try {
                ApiClient.acknowledgeEmergency(this@EmergencySOSActivity, "SAFE")
            } catch (_: Exception) {}
        }
        Toast.makeText(this, "✅ Safe confirmation sent to parents!", Toast.LENGTH_LONG).show()
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Prevent accidental dismissal - child must tap "I'm Safe" or "Call Parent"
        Toast.makeText(this, "Tap \"I'm Safe\" to dismiss this emergency alert.", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        if (isSiren) {
            EmergencySirenEngine.stopSiren(this)
        }
        super.onDestroy()
    }
}

@Composable
fun EmergencySOSScreen(
    message: String,
    parentPhone: String,
    isSiren: Boolean,
    onCallParent: () -> Unit,
    onSafeClicked: () -> Unit,
    onCallEmergencyServices: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0F0003) // Deep crimson dark background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .scale(pulseScale)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(Color(0xFFFF3B30), Color(0xFF8B0000))
                            ),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isSiren) Icons.Default.VolumeUp else Icons.Default.Warning,
                        contentDescription = "Emergency Alert",
                        tint = Color.White,
                        modifier = Modifier.size(54.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = if (isSiren) "🚨 URGENT SIREN ALERT" else "🚨 EMERGENCY SOS ALERT",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFFFF453A),
                    textAlign = TextAlign.Center
                )

                Text(
                    text = if (isSiren)
                        "Loud siren sounding. Your parent needs you to respond immediately."
                    else
                        "Your parent has triggered a high-priority safety alert.",
                    fontSize = 14.sp,
                    color = Color(0xFFCCCCCC),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // Message Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .border(2.dp, Color(0xFFFF453A).copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1F0D10)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Message from Parent",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF9F0A),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message.ifBlank { "Please call Mom/Dad immediately! Make sure you are safe." },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        lineHeight = 24.sp
                    )
                }
            }

            // Actions Section
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Call Parent Button (Prominent Green)
                Button(
                    onClick = onCallParent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF34C759),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(16.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = "Call",
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "📞 Call Parent Now",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (parentPhone.isNotBlank()) {
                                Text(
                                    text = parentPhone,
                                    fontSize = 12.sp,
                                    color = Color(0xFFE8F5E9)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // I'm Safe Button (White/Light)
                Button(
                    onClick = onSafeClicked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2C2C2E),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF636366))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Safe",
                            tint = Color(0xFF30D158),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "I'm Safe ✅",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Emergency Services Dial
                TextButton(
                    onClick = onCallEmergencyServices,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocalPhone,
                        contentDescription = "Emergency Services",
                        tint = Color(0xFFFF453A),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Call Emergency Services (911 / 112)",
                        color = Color(0xFFFF453A),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
