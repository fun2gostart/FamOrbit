package com.familycontrol.lab

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import java.util.concurrent.Executors
import androidx.core.content.ContextCompat
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.OutlinedTextField
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight

data class AppUsage(val appName: String, val packageName: String, val minutes: Long)
data class AppPolicy(val packageName: String, val appName: String, val limitMinutes: Int, val enabled: Boolean)

private enum class Screen { Dashboard, ParentHome, ChildHome, Policies, Routines, Protection, Enforcement, Events, Sync, ParentCenter }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PolicyWorker.schedule(this)
        HeartbeatWorker.schedule(this)
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, EnforcementService::class.java)
            )
        } catch (e: Exception) {
            EventLog.record(this, "ENFORCEMENT_SERVICE_START_ERROR ${e.message}")
        }
        EventLog.record(this, "APP_STARTED")
        autoRegisterCloudBackend(this)
        setContent { FamilyControlApp() }
    }
}

private fun autoRegisterCloudBackend(context: Context) {
    Executors.newSingleThreadExecutor().execute {
        try {
            if (!ApiClient.registered(context)) {
                val registerRes = ApiClient.registerDevice(context)
                if (registerRes.ok) {
                    EventLog.record(context, "AUTO_CLOUD_REGISTER_SUCCESS ${registerRes.body}")
                }
            } else {
                val check = ApiClient.health(context)
                if (check.ok) {
                    EventLog.record(context, "AUTO_CLOUD_PING_SUCCESS")
                }
            }
        } catch (e: Exception) {
            EventLog.record(context, "AUTO_CLOUD_REGISTER_ERROR ${e.message}")
        }
    }
}

@Composable
fun FamilyControlApp() {
    var darkMode by rememberSaveable { mutableStateOf(true) }
    var screen by rememberSaveable { mutableStateOf(Screen.Dashboard) }
    var isParentUnlocked by rememberSaveable { mutableStateOf(false) }
    val dashboardListState = rememberLazyListState()

    BackHandler(enabled = screen != Screen.Dashboard) {
        screen = Screen.Dashboard
    }

    FamilyControlTheme(darkMode) {
        Surface(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.ParentHome -> ParentHomeScreen(
                    onBack = { screen = Screen.Dashboard },
                    onControl = { screen = Screen.ParentCenter }
                )
                Screen.ChildHome -> ChildHomeScreen { screen = Screen.Dashboard }
                Screen.Dashboard -> DashboardScreen(
                    darkMode = darkMode,
                    isParentUnlocked = isParentUnlocked,
                    listState = dashboardListState,
                    onUnlockParent = { isParentUnlocked = true },
                    onToggleDark = { darkMode = it },
                    onParentHome = { screen = Screen.ParentHome },
                    onChildHome = { screen = Screen.ChildHome },
                    onPolicies = { screen = Screen.Policies },
                    onRoutines = { screen = Screen.Routines },
                    onProtection = { screen = Screen.Protection },
                    onEnforcement = { screen = Screen.Enforcement },
                    onSync = { screen = Screen.Sync },
                    onParentCenter = { screen = Screen.ParentCenter }
                )
                Screen.Policies -> PolicyScreen { screen = Screen.Dashboard }
                Screen.Routines -> RoutineScreen { screen = Screen.Dashboard }
                Screen.Protection -> ProtectionScreen { screen = Screen.Dashboard }
                Screen.Enforcement -> EnforcementScreen { screen = Screen.Dashboard }
                Screen.Events -> EventsScreen { screen = Screen.Dashboard }
                Screen.Sync -> SyncScreen { screen = Screen.Dashboard }
                Screen.ParentCenter -> ParentControlScreen { screen = Screen.Dashboard }
            }
        }
    }
}

val Indigo600 = androidx.compose.ui.graphics.Color(0xFF4F46E5)
val Indigo500 = androidx.compose.ui.graphics.Color(0xFF6366F1)
val Violet500 = androidx.compose.ui.graphics.Color(0xFF8B5CF6)
val Emerald500 = androidx.compose.ui.graphics.Color(0xFF10B981)
val Slate900 = androidx.compose.ui.graphics.Color(0xFF0F172A)
val Slate800 = androidx.compose.ui.graphics.Color(0xFF1E293B)
val Slate700 = androidx.compose.ui.graphics.Color(0xFF334155)

private val AppDarkColorScheme = androidx.compose.material3.darkColorScheme(
    primary = Indigo500,
    secondary = Violet500,
    tertiary = Emerald500,
    background = Slate900,
    surface = Slate800,
    surfaceVariant = Slate700,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    onBackground = androidx.compose.ui.graphics.Color(0xFFF8FAFC),
    onSurface = androidx.compose.ui.graphics.Color(0xFFF8FAFC)
)

private val AppLightColorScheme = androidx.compose.material3.lightColorScheme(
    primary = Indigo600,
    secondary = Violet500,
    tertiary = Emerald500,
    background = androidx.compose.ui.graphics.Color(0xFFF8FAFC),
    surface = androidx.compose.ui.graphics.Color.White,
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFF1F5F9),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    onBackground = androidx.compose.ui.graphics.Color(0xFF0F172A),
    onSurface = androidx.compose.ui.graphics.Color(0xFF0F172A)
)

@Composable
fun FamilyControlTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = if (darkTheme) AppDarkColorScheme else AppLightColorScheme,
        content = content
    )
}

@Composable
fun ScreenTimeProgressRing(
    usedMinutes: Long,
    limitMinutes: Long = 180L,
    modifier: Modifier = Modifier
) {
    val fraction = (usedMinutes.toFloat() / limitMinutes.toFloat()).coerceIn(0f, 1f)
    val percentage = (fraction * 100).toInt()
    val hours = usedMinutes / 60
    val mins = usedMinutes % 60
    val limitHours = limitMinutes / 60
    val limitMins = limitMinutes % 60

    val ringColor = when {
        fraction >= 0.9f -> MaterialTheme.colorScheme.error
        fraction >= 0.75f -> androidx.compose.ui.graphics.Color(0xFFF59E0B)
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(90.dp)
            ) {
                Canvas(modifier = Modifier.size(90.dp)) {
                    val strokeWidth = 9.dp.toPx()
                    drawArc(
                        color = ringColor.copy(alpha = 0.2f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = strokeWidth)
                    )
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 360f * fraction,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$percentage%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("used", style = MaterialTheme.typography.labelSmall)
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text("DAILY SCREEN TIME", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (hours > 0) "${hours}h ${mins}m" else "${mins}m",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Daily Target Limit: ${limitHours}h ${limitMins}m",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = fraction,
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = ringColor,
                    trackColor = ringColor.copy(alpha = 0.2f)
                )
            }
        }
    }
}

@Composable
fun WeeklyTrendChart(
    report: WeeklyAnalyticsReport,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("7-DAY USAGE TREND", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Daily Avg: ${report.dailyAverageMinutes / 60}h ${report.dailyAverageMinutes % 60}m", style = MaterialTheme.typography.bodySmall)
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        "Top: ${report.topCategory}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            if (report.anomalyWarning != null) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        report.anomalyWarning,
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            val maxMins = (report.weeklyTrend.maxOfOrNull { it.minutes } ?: 1L).coerceAtLeast(180L)

            Row(
                Modifier.fillMaxWidth().height(130.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                report.weeklyTrend.forEach { day ->
                    val barHeight = ((day.minutes.toFloat() / maxMins.toFloat()) * 60).dp.coerceAtLeast(8.dp)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                    ) {
                        Text(
                            "${day.minutes}m",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            maxLines = 1,
                            softWrap = false,
                            color = if (day.isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .width(16.dp)
                                .height(barHeight)
                                .background(
                                    if (day.isToday) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                                )
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            day.dayLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                            maxLines = 1,
                            softWrap = false,
                            fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Medium,
                            color = if (day.isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("CATEGORY BREAKDOWN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            report.categories.take(4).forEach { cat ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${cat.icon} ${cat.category}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(130.dp))
                    LinearProgressIndicator(
                        progress = (cat.percentage / 100f).coerceIn(0f, 1f),
                        modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = when(cat.category) {
                            "Social" -> androidx.compose.ui.graphics.Color(0xFFEC4899)
                            "Entertainment" -> androidx.compose.ui.graphics.Color(0xFF8B5CF6)
                            "Gaming" -> androidx.compose.ui.graphics.Color(0xFFEF4444)
                            "Education" -> androidx.compose.ui.graphics.Color(0xFF10B981)
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${cat.totalMinutes}m (${cat.percentage}%)", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetDurationDialog(
    mode: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedHours by remember { mutableStateOf(1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(PresetModeEngine.getPresetLabel(mode)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Select active duration (Minimum 1 Hour). Once activated, ending early requires the Parent PIN.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedHours == 1,
                        onClick = { selectedHours = 1 },
                        label = { Text("1 Hour (Min)") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedHours == 2,
                        onClick = { selectedHours = 2 },
                        label = { Text("2 Hours") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedHours == 3,
                        onClick = { selectedHours = 3 },
                        label = { Text("3 Hours") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedHours) }) {
                Text("Activate Mode")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ParentPinDeactivateDialog(
    onConfirm: (String) -> Boolean,
    onDismiss: () -> Unit
) {
    var pinText by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🔒 Enter Parent Security PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Deactivating Focus/Bedtime Mode early requires the 4-Digit Parent PIN (Default PIN: 1234).", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = pinText,
                    onValueChange = { pinText = it.filter { c -> c.isDigit() }.take(4); pinError = null },
                    label = { Text("4-Digit PIN") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (pinError != null) {
                    Text(pinError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (onConfirm(pinText)) {
                    onDismiss()
                } else {
                    pinError = "Incorrect PIN."
                }
            }) { Text("Deactivate") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneTapPresetsBar(
    currentMode: String,
    onSelectMode: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("⚡ ONE-TAP PRESET MODES (1H MINIMUM)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterChip(
                    selected = currentMode == PresetModeEngine.MODE_STUDY,
                    onClick = {
                        val next = if (currentMode == PresetModeEngine.MODE_STUDY) PresetModeEngine.MODE_NONE else PresetModeEngine.MODE_STUDY
                        onSelectMode(next)
                    },
                    label = { Text("🎓 Focus") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = currentMode == PresetModeEngine.MODE_BEDTIME,
                    onClick = {
                        val next = if (currentMode == PresetModeEngine.MODE_BEDTIME) PresetModeEngine.MODE_NONE else PresetModeEngine.MODE_BEDTIME
                        onSelectMode(next)
                    },
                    label = { Text("🌙 Bedtime") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = currentMode == PresetModeEngine.MODE_DINNER,
                    onClick = {
                        val next = if (currentMode == PresetModeEngine.MODE_DINNER) PresetModeEngine.MODE_NONE else PresetModeEngine.MODE_DINNER
                        onSelectMode(next)
                    },
                    label = { Text("🍽️ Dinner") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = currentMode == PresetModeEngine.MODE_REWARD,
                    onClick = {
                        val next = if (currentMode == PresetModeEngine.MODE_REWARD) PresetModeEngine.MODE_NONE else PresetModeEngine.MODE_REWARD
                        onSelectMode(next)
                    },
                    label = { Text("🎁 +15m") },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun PresetStatusBanner(
    mode: String,
    isChildMode: Boolean = false,
    onRequestDeactivation: (() -> Unit)? = null,
    onDeactivateRequest: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val activeMode = PresetModeEngine.getActivePreset(context)

    if (activeMode != PresetModeEngine.MODE_NONE) {
        val remMins = PresetModeEngine.getRemainingMinutes(context)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            PresetModeEngine.getPresetLabel(activeMode),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (remMins > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "(${remMins}m left)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        PresetModeEngine.getPresetDescription(activeMode),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
                if (isChildMode) {
                    TextButton(onClick = { onRequestDeactivation?.invoke() }) {
                        Text("📨 Ask Parent to End")
                    }
                } else {
                    TextButton(onClick = { onDeactivateRequest?.invoke() }) {
                        Text("🔒 Deactivate (PIN)")
                    }
                }
            }
        }
    }
}

@Composable
fun ParentInboxCard(
    context: Context,
    onRequestHandled: () -> Unit = {}
) {
    var pendingRequests by remember { mutableStateOf(ExtraTimeRequestEngine.getPendingRequests(context)) }
    var deactivationRequested by remember { mutableStateOf(PresetModeEngine.isDeactivationRequested(context)) }
    var showPinDialog by remember { mutableStateOf(false) }

    fun refresh() {
        pendingRequests = ExtraTimeRequestEngine.getPendingRequests(context)
        deactivationRequested = PresetModeEngine.isDeactivationRequested(context)
        onRequestHandled()
    }

    if (showPinDialog) {
        ParentPinDeactivateDialog(
            onConfirm = { pin ->
                val ok = PresetModeEngine.deactivateWithPin(context, pin)
                if (ok) {
                    PresetModeEngine.clearDeactivationRequest(context)
                    refresh()
                }
                ok
            },
            onDismiss = { showPinDialog = false }
        )
    }

    if (pendingRequests.isNotEmpty() || deactivationRequested) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "📨 PENDING PARENT APPROVALS (${pendingRequests.size + if (deactivationRequested) 1 else 0})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(Modifier.height(8.dp))

                if (deactivationRequested) {
                    val activeMode = PresetModeEngine.getActivePreset(context)
                    val reason = PresetModeEngine.getDeactivationReason(context)
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("⚡", style = MaterialTheme.typography.headlineMedium)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("End ${PresetModeEngine.getPresetLabel(activeMode)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Child requested parent to end active preset mode.", style = MaterialTheme.typography.bodySmall)
                                Text("\"$reason\"", style = MaterialTheme.typography.bodySmall, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(
                                    onClick = { showPinDialog = true },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("Approve & End")
                                }
                                OutlinedButton(
                                    onClick = {
                                        PresetModeEngine.clearDeactivationRequest(context)
                                        refresh()
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("Deny")
                                }
                            }
                        }
                    }
                }

                pendingRequests.forEach { req ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(req.packageName, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(if (req.appName.isBlank()) "Extra Time Request" else req.appName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Requested: +${req.requestedMinutes} mins", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text("\"${req.reason}\"", style = MaterialTheme.typography.bodySmall, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(
                                    onClick = {
                                        ExtraTimeRequestEngine.approveRequest(context, req.id)
                                        refresh()
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text("Approve")
                                }
                                OutlinedButton(
                                    onClick = {
                                        ExtraTimeRequestEngine.denyRequest(context, req.id)
                                        refresh()
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text("Deny")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FeatureTogglesCard(context: Context) {
    var catBudgets by remember { mutableStateOf(FeatureToggleEngine.isCategoryBudgetsEnabled(context)) }
    var piggyBank by remember { mutableStateOf(FeatureToggleEngine.isPiggyBankEnabled(context)) }
    var habitBadges by remember { mutableStateOf(FeatureToggleEngine.isHabitBadgesEnabled(context)) }
    var execReport by remember { mutableStateOf(FeatureToggleEngine.isExecutiveReportEnabled(context)) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("⚙️ ADVANCED PARENT FEATURE CONTROLS", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Enable or disable extra management features for your family.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("🎓 Category Budgets & Learn-First", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = catBudgets, onCheckedChange = { catBudgets = it; FeatureToggleEngine.setCategoryBudgetsEnabled(context, it) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("🏦 Screen Time Piggy Bank (Rollover)", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = piggyBank, onCheckedChange = { piggyBank = it; FeatureToggleEngine.setPiggyBankEnabled(context, it) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("🏆 Gamified Badges & Digital Pledge", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = habitBadges, onCheckedChange = { habitBadges = it; FeatureToggleEngine.setHabitBadgesEnabled(context, it) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("🤖 Weekly AI Executive Report Card", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = execReport, onCheckedChange = { execReport = it; FeatureToggleEngine.setExecutiveReportEnabled(context, it) })
            }
        }
    }
}

@Composable
fun PiggyBankCard(context: Context, isChildMode: Boolean = false) {
    var balance by remember { mutableStateOf(PiggyBankEngine.getBalanceMinutes(context)) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("🏦 SCREEN TIME PIGGY BANK", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("Unused weekday minutes saved for weekend bonus!", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        "${balance}m Saved 🏦",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
            if (isChildMode && balance >= 15) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val ok = PiggyBankEngine.redeemMinutes(context, 15)
                        if (ok) {
                            ExtraTimeLedger.addExtraTime(context, "com.instagram.android", 15)
                            balance = PiggyBankEngine.getBalanceMinutes(context)
                            Toast.makeText(context, "Redeemed 15 mins from Piggy Bank!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Redeem 15m for Weekend (+15m)")
                }
            }
        }
    }
}

@Composable
fun CategoryBudgetsCard(context: Context, usage: List<AppUsage>) {
    val summaries = remember(usage) { CategoryBudgetEngine.getCategorySummaries(context, usage) }
    val isLearnFirst = CategoryBudgetEngine.isLearnFirstEnabled(context)
    val isGamingBlocked = CategoryBudgetEngine.isGamingBlockedByLearnFirst(context, usage)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("🎓 CATEGORY TIME BUDGETS & LEARN-FIRST", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            summaries.forEach { cat ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${cat.icon} ${cat.category}", style = MaterialTheme.typography.bodyMedium)
                    Text("${cat.usedMinutes}m / ${cat.limitMinutes}m cap", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(6.dp))
            if (isLearnFirst) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = if (isGamingBlocked) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        if (isGamingBlocked) "🔒 Learn First Rule Active: Child needs 30m educational app usage to unlock Gaming."
                        else "🔓 Learn First Rule Complete: Educational goal met today!",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun BadgesCard(context: Context) {
    val badges = remember { BadgeEngine.getAllBadges(context) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("🏆 DIGITAL PLEDGE & MILESTONE BADGES", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                badges.forEach { badge ->
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(
                            containerColor = if (badge.isUnlocked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(badge.icon, style = MaterialTheme.typography.headlineMedium)
                            Text(badge.title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExecutiveReportCard(context: Context, usage: List<AppUsage>) {
    val report = remember(usage) { ExecutiveReportEngine.generateReport(context, usage) }
    var isExpanded by rememberSaveable { mutableStateOf(false) }

    Card(
        onClick = { isExpanded = !isExpanded },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        "🤖 WEEKLY AI EXECUTIVE REPORT CARD",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${report.productivityIndex}% Productive",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse Report" else "Expand Report"
                    )
                }
            }
            if (isExpanded) {
                Spacer(Modifier.height(8.dp))
                Text(report.weekOverWeekChange, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(report.aiSummaryAdvice, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                report.recommendations.forEach { rec ->
                    Text(rec, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

@Composable
fun Method2EnforcementCard(context: Context) {
    var accessibilityOn by remember { mutableStateOf(AccessibilityGuardEngine.isAccessibilityEnabled(context)) }
    var overlayOn by remember { mutableStateOf(AccessibilityGuardEngine.canDrawOverlays(context)) }
    var showDisclosureModal by remember { mutableStateOf(false) }

    if (showDisclosureModal) {
        AlertDialog(
            onDismissRequest = { showDisclosureModal = false },
            title = { Text("📋 Prominent Disclosure — Accessibility & Overlay API") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "FamOrbit uses Accessibility API and System Overlay Permission strictly to:",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text("1. Detect restricted app launches in real-time (50ms).", style = MaterialTheme.typography.bodySmall)
                    Text("2. Display parent-set screen lock overlays during Focus, Bedtime, and Dinner Time routines.", style = MaterialTheme.typography.bodySmall)
                    Text("3. Protect settings from unauthorized tampering or uninstallation by children.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "No personal data or text input is collected or shared.",
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showDisclosureModal = false
                    AccessibilityGuardEngine.openAccessibilitySettings(context)
                }) {
                    Text("I Agree — Enable Accessibility")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDisclosureModal = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("⚡ ZERO-RESET ENFORCEMENT (METHOD 2)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("Instant app blocking & tamper protection without phone reset.", style = MaterialTheme.typography.bodySmall)
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (accessibilityOn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        if (accessibilityOn) "ACTIVE 🟢" else "SETUP NEEDED ⚠️",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        accessibilityOn = AccessibilityGuardEngine.isAccessibilityEnabled(context)
                        if (!accessibilityOn) showDisclosureModal = true
                        else AccessibilityGuardEngine.openAccessibilitySettings(context)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (accessibilityOn) "Accessibility 🟢" else "1. Enable Accessibility")
                }

                Button(
                    onClick = {
                        overlayOn = AccessibilityGuardEngine.canDrawOverlays(context)
                        AccessibilityGuardEngine.openOverlaySettings(context)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (overlayOn) "Overlay 🟢" else "2. Enable Overlay")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    darkMode: Boolean,
    isParentUnlocked: Boolean,
    listState: LazyListState = rememberLazyListState(),
    onUnlockParent: () -> Unit,
    onParentHome: () -> Unit,
    onChildHome: () -> Unit,
    onToggleDark: (Boolean) -> Unit,
    onPolicies: () -> Unit,
    onRoutines: () -> Unit,
    onProtection: () -> Unit,
    onEnforcement: () -> Unit,
    onSync: () -> Unit,
    onParentCenter: () -> Unit
) {
    val context = LocalContext.current
    var usage by remember { mutableStateOf(emptyList<AppUsage>()) }
    var usageAccess by remember { mutableStateOf(hasUsageAccess(context)) }

    fun refresh() {
        usageAccess = hasUsageAccess(context)
        usage = if (usageAccess) getTodayUsage(context) else emptyList()
    }

    LaunchedEffect(Unit) { refresh() }

    var pendingTarget by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pinText by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    fun launchProtected(target: () -> Unit) {
        if (isParentUnlocked) {
            target()
        } else {
            pendingTarget = target
        }
    }

    if (pendingTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingTarget = null; pinText = ""; pinError = null },
            title = { Text("🔒 Enter Parent Security PIN") },
            text = {
                Column {
                    Text("Parent Mode is PIN-protected (Default PIN: 1234).", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pinText,
                        onValueChange = { pinText = it; pinError = null },
                        label = { Text("4-Digit PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (pinError != null) {
                        Text(pinError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (ParentSecurity.verifyPin(context, pinText)) {
                        onUnlockParent()
                        val target = pendingTarget
                        pendingTarget = null
                        pinText = ""
                        pinError = null
                        target?.invoke()
                    } else {
                        pinError = "Incorrect PIN."
                    }
                }) { Text("Unlock") }
            },
            dismissButton = {
                Button(onClick = { pendingTarget = null; pinText = ""; pinError = null }) { Text("Cancel") }
            }
        )
    }

    var activePreset by remember { mutableStateOf(PresetModeEngine.getActivePreset(context)) }
    var showDurationDialog by remember { mutableStateOf(false) }
    var pendingPresetMode by remember { mutableStateOf<String?>(null) }
    var showPinDeactivateDialog by remember { mutableStateOf(false) }

    val analyticsReport = remember(usage) { AnalyticsEngine.generateReport(context, usage) }
    val totalUsedMinutes = usage.sumOf { it.minutes }

    if (showDurationDialog && pendingPresetMode != null) {
        PresetDurationDialog(
            mode = pendingPresetMode!!,
            onConfirm = { hours ->
                val mode = pendingPresetMode!!
                activePreset = mode
                PresetModeEngine.setActivePreset(context, mode, hours)
                showDurationDialog = false
                pendingPresetMode = null
            },
            onDismiss = {
                showDurationDialog = false
                pendingPresetMode = null
            }
        )
    }

    if (showPinDeactivateDialog) {
        ParentPinDeactivateDialog(
            onConfirm = { pin ->
                val ok = PresetModeEngine.deactivateWithPin(context, pin)
                if (ok) {
                    activePreset = PresetModeEngine.MODE_NONE
                    showPinDeactivateDialog = false
                }
                ok
            },
            onDismiss = { showPinDeactivateDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FamOrbit v2.3.0") },
                actions = {
                    IconButton(onClick = { onToggleDark(!darkMode) }) {
                        Icon(
                            if (darkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                            if (darkMode) "Light mode" else "Dark mode"
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🛡️ FAMORBIT", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (isParentUnlocked) "🔓 Parent Session Unlocked" else "🔒 PIN Protected",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "🟢 Status: Device Protection Active • Offline Enforcement Enabled",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Option B: Active Preset Status Banner & One-Tap Presets Bar
            item {
                PresetStatusBanner(
                    mode = activePreset,
                    isChildMode = false,
                    onDeactivateRequest = {
                        showPinDeactivateDialog = true
                    }
                )
            }
            item {
                OneTapPresetsBar(activePreset) { mode ->
                    if (mode == PresetModeEngine.MODE_NONE) {
                        if (activePreset != PresetModeEngine.MODE_NONE) {
                            showPinDeactivateDialog = true
                        }
                    } else if (mode == PresetModeEngine.MODE_REWARD) {
                        activePreset = mode
                        PresetModeEngine.setActivePreset(context, mode, 1)
                    } else {
                        pendingPresetMode = mode
                        showDurationDialog = true
                    }
                }
            }

            // Option C: Pending Extra Time Requests Inbox Card
            item {
                ParentInboxCard(context)
            }

            // Option A: Visual Screen Time Progress Ring
            item {
                ScreenTimeProgressRing(usedMinutes = totalUsedMinutes)
            }

            // Option A: 7-Day Usage Trend Chart
            item {
                WeeklyTrendChart(report = analyticsReport)
            }

            item {
                Method2EnforcementCard(context)
            }

            if (FeatureToggleEngine.isExecutiveReportEnabled(context)) {
                item {
                    ExecutiveReportCard(context, usage)
                }
            }

            if (FeatureToggleEngine.isPiggyBankEnabled(context)) {
                item {
                    PiggyBankCard(context, isChildMode = false)
                }
            }

            if (FeatureToggleEngine.isCategoryBudgetsEnabled(context)) {
                item {
                    CategoryBudgetsCard(context, usage)
                }
            }

            if (FeatureToggleEngine.isHabitBadgesEnabled(context)) {
                item {
                    BadgesCard(context)
                }
            }

            item {
                Text("PARENT CONTROLS", style = MaterialTheme.typography.titleSmall)
            }
            item {
                Button(onClick = { launchProtected(onParentCenter) }, Modifier.fillMaxWidth()) {
                    Text(if (isParentUnlocked) "Parent Control Center 🔓" else "Parent Control Center 🔒")
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onChildHome, Modifier.weight(1f)) {
                        Text("Child Dashboard")
                    }
                    Button(onClick = onProtection, Modifier.weight(1f)) {
                        Icon(Icons.Default.Security, null)
                        Text(" Health")
                    }
                }
            }

            item {
                Text("ROUTINES & LABS", style = MaterialTheme.typography.titleSmall)
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { launchProtected(onRoutines) }, Modifier.weight(1f)) {
                        Text(if (isParentUnlocked) "Family Routines 🔓" else "Family Routines 🔒")
                    }
                    Button(onClick = { launchProtected(onSync) }, Modifier.weight(1f)) {
                        Text(if (isParentUnlocked) "Sync Lab 🔓" else "Sync Lab 🔒")
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("USAGE — TODAY", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        if (!usageAccess) {
                            Text("Usage Access is required.")
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            }) { Text("Open Usage Access") }
                        } else {
                            Button(onClick = { refresh() }) {
                                Icon(Icons.Default.Refresh, null)
                                Text(" Refresh Usage")
                            }
                        }
                    }
                }
            }

            val displayUsage = usage.filter { !AppScanner.isSystemStub(it.packageName, it.appName) }.sortedByDescending { it.minutes }

            items(displayUsage) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(item.packageName, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(item.appName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text("${item.minutes} min today", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentHomeScreen(onBack: () -> Unit, onControl: () -> Unit) {
    val context = LocalContext.current
    val parentUsage = remember { if (hasUsageAccess(context)) getTodayUsage(context) else emptyList() }
    var requests by remember { mutableStateOf(emptyList<org.json.JSONObject>()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val executor = remember { Executors.newSingleThreadExecutor() }

    fun refresh() {
        busy = true
        executor.execute {
            val response = ApiClient.getTimeRequests(context)
            context.mainExecutor.execute {
                busy = false
                if (response.ok) {
                    val json = org.json.JSONObject(response.body)
                    val arr = json.optJSONArray("requests")
                    requests = buildList {
                        if (arr != null) {
                            for (i in 0 until arr.length()) add(arr.getJSONObject(i))
                        }
                    }
                    status = if (requests.isEmpty()) "No time requests" else ""
                } else {
                    status = response.error ?: response.body
                }
            }
        }
    }

    fun decide(requestId: String, approved: Boolean) {
        if (busy) return
        busy = true
        executor.execute {
            val response = ApiClient.decideTimeRequest(context, requestId, approved)
            context.mainExecutor.execute {
                busy = false
                status = if (response.ok) {
                    val statusText = if (approved) "Approved" else "Declined"
                    NotificationEngine.notify(
                        context,
                        requestId.hashCode(),
                        "Time Request Decision",
                        "Time request was $statusText."
                    )
                    if (approved) "Request approved" else "Request declined"
                } else {
                    response.error ?: response.body
                }
                refresh()
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }
    DisposableEffect(Unit) { onDispose { executor.shutdownNow() } }

    var showChangePinDialog by remember { mutableStateOf(false) }
    var newPinText by remember { mutableStateOf("") }
    var confirmPinText by remember { mutableStateOf("") }
    var changePinError by remember { mutableStateOf<String?>(null) }

    if (showChangePinDialog) {
        AlertDialog(
            onDismissRequest = {
                showChangePinDialog = false
                newPinText = ""
                confirmPinText = ""
                changePinError = null
            },
            title = { Text("🔑 Change Parent Security PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Set a new 4-digit PIN for Parent Mode.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = newPinText,
                        onValueChange = { newPinText = it.filter { c -> c.isDigit() }.take(4); changePinError = null },
                        label = { Text("New 4-Digit PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmPinText,
                        onValueChange = { confirmPinText = it.filter { c -> c.isDigit() }.take(4); changePinError = null },
                        label = { Text("Confirm New PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (changePinError != null) {
                        Text(changePinError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newPinText.length != 4) {
                        changePinError = "PIN must be exactly 4 digits."
                    } else if (newPinText != confirmPinText) {
                        changePinError = "PINs do not match."
                    } else {
                        ParentSecurity.setPin(context, newPinText)
                        showChangePinDialog = false
                        newPinText = ""
                        confirmPinText = ""
                        changePinError = null
                        Toast.makeText(context, "Parent Security PIN updated successfully!", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Save PIN") }
            },
            dismissButton = {
                Button(onClick = {
                    showChangePinDialog = false
                    newPinText = ""
                    confirmPinText = ""
                    changePinError = null
                }) { Text("Cancel") }
            }
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Parent Mode") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            },
            actions = {
                IconButton(enabled = !busy, onClick = { refresh() }) {
                    Icon(Icons.Default.Refresh, "Refresh")
                }
            }
        )
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Family Dashboard", style = MaterialTheme.typography.headlineSmall)
                Text("Manage the child's digital routine and review requests.")
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("CHILD DEVICE & SECURITY", style = MaterialTheme.typography.titleMedium)
                        Text("Child ID: ${ApiClient.serverChildId(context) ?: "Not paired"}")
                        Text("Device ID: ${ApiClient.serverDeviceId(context) ?: "Not registered"}")
                    }
                }
            }
            item {
                Button(onClick = { showChangePinDialog = true }, Modifier.fillMaxWidth()) {
                    Text("🔑 Change Parent Security PIN")
                }
            }
            item {
                Button(onClick = onControl, Modifier.fillMaxWidth()) {
                    Text("Manage Screen Time & App Limits")
                }
            }

            item {
                ParentInboxCard(context) { refresh() }
            }

            item {
                FeatureTogglesCard(context)
            }

            if (FeatureToggleEngine.isExecutiveReportEnabled(context)) {
                item {
                    ExecutiveReportCard(context, parentUsage)
                }
            }

            if (FeatureToggleEngine.isPiggyBankEnabled(context)) {
                item {
                    PiggyBankCard(context, isChildMode = false)
                }
            }

            if (FeatureToggleEngine.isCategoryBudgetsEnabled(context)) {
                item {
                    CategoryBudgetsCard(context, parentUsage)
                }
            }

            if (FeatureToggleEngine.isHabitBadgesEnabled(context)) {
                item {
                    BadgesCard(context)
                }
            }

            item {
                Text("PARENT AI COACH", style = MaterialTheme.typography.titleMedium)
            }
            item {
                val insights = remember { AICoachEngine.generateInsights(context) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (insight in insights) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (insight.type == "ALERT") MaterialTheme.colorScheme.errorContainer
                                else MaterialTheme.colorScheme.primaryContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(insight.title, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                Text(insight.description, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(4.dp))
                                Text("💡 Recommendation: ${insight.recommendation}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            item {
                Text("TIME REQUESTS & LEDGER", style = MaterialTheme.typography.titleMedium)
            }

            if (requests.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            status.ifBlank { "No time requests" },
                            Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(requests, key = { it.optString("request_id") }) { request ->
                    val requestId = request.optString("request_id")
                    val minutes = request.optInt("requested_minutes")
                    val packageName = request.optString("package_name").takeIf { it.isNotBlank() && it != "null" && it != "None" }
                    val appName = AppNameResolver.getAppName(context, packageName)
                    val reason = request.optString("reason").ifBlank { "No reason provided" }
                    val requestStatus = request.optString("status")
                    val approvedMins = request.optInt("approved_minutes", minutes)
                    val consumedMins = request.optInt("consumed_minutes", 0)
                    val remainingMins = (approvedMins - consumedMins).coerceAtLeast(0)

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppIcon(packageName ?: "", modifier = Modifier.size(32.dp))
                                Spacer(Modifier.width(10.dp))
                                Text("$minutes minutes • $appName", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Text(requestStatus)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("Reason: $reason")
                            if (requestStatus == "APPROVED") {
                                Spacer(Modifier.height(4.dp))
                                Text("Ledger Allowance: ${consumedMins}m used / ${approvedMins}m approved (${remainingMins}m remaining)", style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(8.dp))

                            if (requestStatus == "PENDING") {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        enabled = !busy,
                                        onClick = { decide(requestId, true) },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Approve") }

                                    Button(
                                        enabled = !busy,
                                        onClick = { decide(requestId, false) },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Decline") }
                                }
                            }
                        }
                    }
                }
            }

            if (status.isNotBlank()) {
                item {
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
            }

            item {
                HealthCard(
                    "Protection Health",
                    ProtectionMonitor.snapshot(context).state == ProtectionState.PROTECTED,
                    ProtectionMonitor.snapshot(context).detail
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildHomeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var usage by remember { mutableStateOf(emptyList<AppUsage>()) }
    var requestStatus by remember { mutableStateOf("No request submitted") }
    var requests by remember { mutableStateOf(emptyList<org.json.JSONObject>()) }
    var busy by remember { mutableStateOf(false) }
    var reasonDialogMinutes by remember { mutableStateOf<Int?>(null) }
    var reasonDialogPackage by remember { mutableStateOf<String?>(null) }
    var enforcementStatus by remember { mutableStateOf("Not evaluated") }

    val prefs = remember { context.getSharedPreferences("parent_control", Context.MODE_PRIVATE) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val installedApps = remember { AppScanner.getInstalledApps(context) }
    val appRules = remember(installedApps) {
        if (installedApps.isNotEmpty()) {
            installedApps.map { app ->
                Triple(app.packageName, app.appName, prefs.getInt("limit_${app.packageName}", 30))
            }
        } else {
            listOf(
                Triple("com.instagram.android", "Instagram", prefs.getInt("limit_com.instagram.android", 30)),
                Triple("com.jio.jioPlay.tv", "JioPlayTV", prefs.getInt("limit_com.jio.jioPlay.tv", 30)),
                Triple("com.netflix.mediaclient", "Netflix", prefs.getInt("limit_com.netflix.mediaclient", 45))
            )
        }
    }

    fun loadRequests() {
        executor.execute {
            val response = ApiClient.getTimeRequests(context)
            if (response.ok) {
                val json = org.json.JSONObject(response.body)
                val arr = json.optJSONArray("requests")
                val parsed = buildList {
                    if (arr != null) {
                        for (i in 0 until arr.length()) add(arr.getJSONObject(i))
                    }
                }
                context.mainExecutor.execute { requests = parsed }
            }
        }
    }

    fun refresh() {
        usage = if (hasUsageAccess(context)) getTodayUsage(context) else emptyList()
        loadRequests()
    }

    fun sendRequest(minutes: Int, reason: String, packageName: String? = null) {
        busy = true
        val appName = AppNameResolver.getAppName(context, packageName)
        ExtraTimeRequestEngine.submitRequest(context, packageName ?: "", appName, minutes, reason)
        executor.execute {
            val response = ApiClient.createTimeRequest(context, minutes, reason, packageName)
            context.mainExecutor.execute {
                busy = false
                requestStatus = "Request for $minutes minutes sent to parent"
                NotificationEngine.notify(
                    context,
                    System.currentTimeMillis().toInt(),
                    "New Time Request",
                    "Requested $minutes mins for $appName: $reason"
                )
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }
    DisposableEffect(Unit) { onDispose { executor.shutdownNow() } }

    val dailyLimit = prefs.getInt("daily_screen_limit", 180)
    val totalUsed = usage.sumOf { it.minutes }

    // Approved requests are treated as temporary allowances for today's child view.
    val approvedExtra = requests
        .filter { it.optString("status") == "APPROVED" && it.optString("package_name").isBlank() }
        .sumOf { it.optInt("requested_minutes") }
    val effectiveLimit = dailyLimit + approvedExtra
    val remaining = (effectiveLimit - totalUsed).coerceAtLeast(0)
    val baseRemaining = (dailyLimit - totalUsed).coerceAtLeast(0)
    val limitReached = totalUsed >= effectiveLimit

    if (reasonDialogMinutes != null) {
        var reason by remember(reasonDialogMinutes, reasonDialogPackage) { mutableStateOf("") }
        val requestedAppName = AppNameResolver.getAppName(context, reasonDialogPackage)
        AlertDialog(
            onDismissRequest = { if (!busy) { reasonDialogMinutes = null; reasonDialogPackage = null } },
            title = { Text("Request ${reasonDialogMinutes} min for $requestedAppName") },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Why do you need more time?") },
                    minLines = 2,
                    maxLines = 4
                )
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        val minutes = reasonDialogMinutes ?: return@Button
                        val packageName = reasonDialogPackage
                        reasonDialogMinutes = null
                        reasonDialogPackage = null
                        sendRequest(
                            minutes,
                            reason.ifBlank { "Child requested extra screen time" },
                            packageName
                        )
                    }
                ) { Text("Send Request") }
            },
            dismissButton = {
                Button(
                    enabled = !busy,
                    onClick = { reasonDialogMinutes = null; reasonDialogPackage = null }
                ) { Text("Cancel") }
            }
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Child Mode") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            },
            actions = {
                IconButton(onClick = { refresh() }) {
                    Icon(Icons.Default.Refresh, "Refresh")
                }
            }
        )
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("My Digital Day", style = MaterialTheme.typography.headlineSmall)
                Text("A transparent view of the family policy on this device.")
            }

            item {
                var activePreset by remember { mutableStateOf(PresetModeEngine.getActivePreset(context)) }
                PresetStatusBanner(
                    mode = activePreset,
                    isChildMode = true,
                    onRequestDeactivation = {
                        PresetModeEngine.requestDeactivation(context, "Child requested parent to end active preset mode")
                        Toast.makeText(context, "Request sent to Parent Inbox 📨", Toast.LENGTH_SHORT).show()
                        activePreset = PresetModeEngine.getActivePreset(context)
                    }
                )
            }

            item {
                ScreenTimeProgressRing(usedMinutes = totalUsed, limitMinutes = effectiveLimit.toLong())
            }

            if (FeatureToggleEngine.isPiggyBankEnabled(context)) {
                item {
                    PiggyBankCard(context, isChildMode = true)
                }
            }

            if (FeatureToggleEngine.isCategoryBudgetsEnabled(context)) {
                item {
                    CategoryBudgetsCard(context, usage)
                }
            }

            if (FeatureToggleEngine.isHabitBadgesEnabled(context)) {
                item {
                    BadgesCard(context)
                }
            }

            item {
                val activeRoutine = remember { ScheduleEngine.activeRoutine(context) }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (activeRoutine != null) MaterialTheme.colorScheme.tertiaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("SMART FAMILY ROUTINE", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        if (activeRoutine != null) {
                            Text("🌙 Active Routine: ${activeRoutine.name} (${activeRoutine.start} – ${activeRoutine.end})", style = MaterialTheme.typography.bodyLarge)
                            Text("Entertainment apps are restricted during this routine window.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("☀️ Regular Hours — No active routine restrictions", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("TODAY'S SCREEN TIME & ALLOWANCE", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("$totalUsed / $effectiveLimit minutes", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            if (limitReached) "LIMIT REACHED"
                            else "$remaining minutes remaining"
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("App Extra-Time Ledger:", style = MaterialTheme.typography.titleSmall)
                        listOf("com.instagram.android", "com.jio.jioPlay.tv", "com.netflix.mediaclient").forEach { pkg ->
                            val appName = AppNameResolver.getAppName(context, pkg)
                            val remMins = ExtraTimeLedger.getRemainingExtraMinutes(context, pkg)
                            Text("• $appName: $remMins extra mins available", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("REQUEST EXTRA TIME", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 30, 60).forEach { minutes ->
                                Button(
                                    enabled = !busy,
                                    onClick = { reasonDialogMinutes = minutes }
                                ) { Text("+$minutes") }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(requestStatus, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("🌟 EARN EXTRA TIME (HABITS)", style = MaterialTheme.typography.titleMedium)
                        Text("Complete positive daily habits to earn +15 reward minutes!", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        HabitRewardEngine.defaultHabits.forEach { habit ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${habit.icon} ${habit.title}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Button(
                                    onClick = {
                                        HabitRewardEngine.claimHabit(context, habit.id)
                                        ExtraTimeRequestEngine.submitRequest(
                                            context,
                                            "",
                                            habit.title,
                                            habit.rewardMinutes,
                                            "Habit Completed: ${habit.title}"
                                        )
                                        Toast.makeText(context, "Claimed ${habit.title}! Sent to Parent Inbox for confirmation.", Toast.LENGTH_SHORT).show()
                                    }
                                ) { Text("Claim +${habit.rewardMinutes}m") }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("📋 FAMILY DIGITAL AGREEMENT", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        FamilyAgreement.agreementRules.forEach { rule ->
                            Text(rule, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("PROTECTION STATUS", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (ApiClient.registered(context))
                                "Connected to family"
                            else
                                "Not paired"
                        )
                        Text(
                            "Policy changes are delivered through the FamilyControl sync service.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                Text("APP POLICY STATUS", style = MaterialTheme.typography.titleMedium)
            }

            val filteredAppRules = appRules
                .filter { !AppScanner.isSystemStub(it.first, it.second) }
                .sortedByDescending { rule -> usage.firstOrNull { u -> u.packageName == rule.first }?.minutes ?: 0L }

            items(filteredAppRules) { rule ->
                val appUsage = usage.firstOrNull { it.packageName == rule.first }?.minutes ?: 0L
                val enabled = prefs.getBoolean("enabled_${rule.first}", false)
                val approvedExtra = requests
                    .filter { it.optString("status") == "APPROVED" && it.optString("package_name") == rule.first }
                    .sumOf { it.optInt("requested_minutes") }
                val effectiveAppLimit = rule.third + approvedExtra
                val reached = enabled && appUsage >= effectiveAppLimit

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(rule.first, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(rule.second, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("${appUsage} / ${effectiveAppLimit} min today", style = MaterialTheme.typography.bodyMedium)
                                if (approvedExtra > 0) {
                                    Text("+$approvedExtra min approved", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Text(
                                when {
                                    !enabled -> "RULE OFF"
                                    reached -> "LIMIT REACHED"
                                    else -> "${(effectiveAppLimit - appUsage).coerceAtLeast(0)} min left"
                                },
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        enforcementStatus = evaluateEnforcement(context, usage)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Evaluate Enforcement")
                }
            }

            item {
                Text(
                    "Enforcement: $enforcementStatus",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            item { Text("RECENT USAGE", style = MaterialTheme.typography.titleMedium) }

            items(usage.take(10)) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.appName)
                            Text(item.packageName, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("${item.minutes} min")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentControlScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val usage = if (hasUsageAccess(context)) getTodayUsage(context) else emptyList()

    val defaults = listOf(
        AppPolicy("com.instagram.android", "Instagram", 30, true),
        AppPolicy("com.jio.jioPlay.tv", "JioPlayTV", 30, true),
        AppPolicy("com.netflix.mediaclient", "Netflix", 45, true)
    )

    val prefs = remember {
        context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
    }

    fun loadRules(): List<AppPolicy> {
        val scanned = AppScanner.getInstalledApps(context)
        return if (scanned.isNotEmpty()) {
            scanned.map { app ->
                AppPolicy(
                    app.packageName,
                    app.appName,
                    prefs.getInt("limit_${app.packageName}", 30),
                    prefs.getBoolean("enabled_${app.packageName}", false)
                )
            }
        } else {
            defaults.map { default ->
                AppPolicy(
                    default.packageName,
                    default.appName,
                    prefs.getInt("limit_${default.packageName}", default.limitMinutes),
                    prefs.getBoolean("enabled_${default.packageName}", default.enabled)
                )
            }
        }
    }

    var dailyLimit by remember {
        mutableStateOf(prefs.getInt("daily_screen_limit", 180))
    }
    var rules by remember { mutableStateOf(loadRules()) }
    var selectedPackages by remember { mutableStateOf(setOf<String>()) }
    var status by remember { mutableStateOf("Draft loaded from device") }
    var busy by remember { mutableStateOf(false) }
    var customRulePackage by remember { mutableStateOf<String?>(null) }
    var customMinutesText by remember { mutableStateOf("") }
    var customMinutesError by remember { mutableStateOf<String?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    var searchQuery by remember { mutableStateOf("") }
    var requests by remember { mutableStateOf(emptyList<org.json.JSONObject>()) }
    var showChangePinDialog by remember { mutableStateOf(false) }
    var newPinText by remember { mutableStateOf("") }
    var confirmPinText by remember { mutableStateOf("") }
    var changePinError by remember { mutableStateOf<String?>(null) }

    fun refreshRequests() {
        executor.execute {
            val response = ApiClient.getTimeRequests(context)
            context.mainExecutor.execute {
                if (response.ok) {
                    val json = org.json.JSONObject(response.body)
                    val arr = json.optJSONArray("requests")
                    requests = buildList {
                        if (arr != null) {
                            for (i in 0 until arr.length()) add(arr.getJSONObject(i))
                        }
                    }
                }
            }
        }
    }

    fun decide(requestId: String, approved: Boolean) {
        if (busy) return
        busy = true
        executor.execute {
            val response = ApiClient.decideTimeRequest(context, requestId, approved)
            context.mainExecutor.execute {
                busy = false
                if (response.ok) {
                    val statusText = if (approved) "Approved" else "Declined"
                    NotificationEngine.notify(
                        context,
                        requestId.hashCode(),
                        "Time Request Decision",
                        "Time request was $statusText."
                    )
                    status = if (approved) "Request approved" else "Request declined"
                } else {
                    status = response.error ?: response.body
                }
                refreshRequests()
            }
        }
    }

    LaunchedEffect(Unit) { refreshRequests() }
    DisposableEffect(Unit) { onDispose { executor.shutdownNow() } }

    fun saveDraftLocally(
        updatedRules: List<AppPolicy> = rules,
        updatedDailyLimit: Int = dailyLimit
    ) {
        prefs.edit()
            .putInt("daily_screen_limit", updatedDailyLimit)
            .apply()

        updatedRules.forEach { rule ->
            prefs.edit()
                .putInt("limit_${rule.packageName}", rule.limitMinutes)
                .putBoolean("enabled_${rule.packageName}", rule.enabled)
                .apply()
        }

        rules = updatedRules
        dailyLimit = updatedDailyLimit
        status = "Draft saved locally"
        EventLog.record(context, "PARENT_DRAFT_SAVED")
    }

    fun updateSelectedRules(minutesDelta: Int, setLimit: Int? = null, enableState: Boolean? = null) {
        val targets = if (selectedPackages.isEmpty()) rules.map { it.packageName }.toSet() else selectedPackages
        val updatedList = rules.map { r ->
            if (r.packageName in targets) {
                val newLimit = setLimit ?: (r.limitMinutes + minutesDelta).coerceIn(5, 240)
                val newEnabled = enableState ?: r.enabled
                r.copy(limitMinutes = newLimit, enabled = newEnabled)
            } else r
        }
        saveDraftLocally(updatedList, dailyLimit)
    }

    fun publishToServer() {
        if (!ApiClient.registered(context)) {
            status = "Register the device in Parent ↔ Child Sync first"
            return
        }

        // Always persist the exact values being published.
        saveDraftLocally(rules, dailyLimit)
        busy = true

        val jsonRules = org.json.JSONObject()
        rules.forEach { rule ->
            jsonRules.put(
                rule.appName.lowercase().replace(" ", "_"),
                org.json.JSONObject()
                    .put("package", rule.packageName)
                    .put("daily_limit_minutes", rule.limitMinutes)
                    .put("enabled", rule.enabled)
            )
        }

        executor.execute {
            val result = ApiClient.createServerPolicy(context, dailyLimit, jsonRules)
            context.mainExecutor.execute {
                busy = false
                status = if (result.ok) {
                    "Published to server — ${result.body}"
                } else {
                    "Publish failed — ${result.error ?: result.body}"
                }
            }
        }
    }

    if (showChangePinDialog) {
        AlertDialog(
            onDismissRequest = {
                showChangePinDialog = false
                newPinText = ""
                confirmPinText = ""
                changePinError = null
            },
            title = { Text("🔑 Change Parent Security PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Set a new 4-digit PIN for Parent Control Center.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = newPinText,
                        onValueChange = { newPinText = it.filter { c -> c.isDigit() }.take(4); changePinError = null },
                        label = { Text("New 4-Digit PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmPinText,
                        onValueChange = { confirmPinText = it.filter { c -> c.isDigit() }.take(4); changePinError = null },
                        label = { Text("Confirm New PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (changePinError != null) {
                        Text(changePinError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newPinText.length != 4) {
                        changePinError = "PIN must be exactly 4 digits."
                    } else if (newPinText != confirmPinText) {
                        changePinError = "PINs do not match."
                    } else {
                        ParentSecurity.setPin(context, newPinText)
                        showChangePinDialog = false
                        newPinText = ""
                        confirmPinText = ""
                        changePinError = null
                        Toast.makeText(context, "Parent Security PIN updated successfully!", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Save PIN") }
            },
            dismissButton = {
                Button(onClick = {
                    showChangePinDialog = false
                    newPinText = ""
                    confirmPinText = ""
                    changePinError = null
                }) { Text("Cancel") }
            }
        )
    }

    if (customRulePackage != null) {
        val selectedRule = rules.firstOrNull { it.packageName == customRulePackage }
        AlertDialog(
            onDismissRequest = {
                customRulePackage = null
                customMinutesText = ""
                customMinutesError = null
            },
            title = { Text("Custom app limit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        selectedRule?.appName ?: "App",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text("Enter the daily limit in minutes (1–240).")
                    OutlinedTextField(
                        value = customMinutesText,
                        onValueChange = { value ->
                            customMinutesText = value.filter { it.isDigit() }.take(3)
                            customMinutesError = null
                        },
                        label = { Text("Minutes") },
                        singleLine = true,
                        isError = customMinutesError != null,
                        supportingText = {
                            if (customMinutesError != null) {
                                Text(customMinutesError!!)
                            }
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val minutes = customMinutesText.toIntOrNull()
                        if (minutes == null || minutes !in 1..240) {
                            customMinutesError = "Enter a whole number from 1 to 240."
                        } else {
                            val packageName = customRulePackage
                            if (packageName != null) {
                                saveDraftLocally(
                                    rules.map {
                                        if (it.packageName == packageName) {
                                            it.copy(limitMinutes = minutes)
                                        } else {
                                            it
                                        }
                                    }
                                )
                            }
                            customRulePackage = null
                            customMinutesText = ""
                            customMinutesError = null
                        }
                    }
                ) { Text("Apply") }
            },
            dismissButton = {
                Button(
                    onClick = {
                        customRulePackage = null
                        customMinutesText = ""
                        customMinutesError = null
                    }
                ) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Parent Control Center") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshRequests() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
        ) {
            // Frozen Sticky Action Card — remains visible while scrolling down!
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !busy,
                            onClick = { publishToServer() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text(if (busy) "Publishing…" else "🚀 Publish Policy")
                        }
                        OutlinedButton(
                            onClick = { saveDraftLocally() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("💾 Save Draft")
                        }
                    }
                    Text("Status: $status", style = MaterialTheme.typography.bodySmall)
                }
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("CHILD DEVICE & SECURITY", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                Text("Child ID: ${ApiClient.serverChildId(context) ?: "Not paired"}", style = MaterialTheme.typography.bodySmall)
                                Text("Device ID: ${ApiClient.serverDeviceId(context) ?: "Not registered"}", style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = { showChangePinDialog = true }) {
                                Text("🔑 Change PIN")
                            }
                        }
                    }
                }
            }

            if (requests.isNotEmpty()) {
                item {
                    Text("PENDING TIME REQUESTS", style = MaterialTheme.typography.titleMedium)
                }
                items(requests, key = { it.optString("request_id") }) { request ->
                    val requestId = request.optString("request_id")
                    val minutes = request.optInt("requested_minutes")
                    val packageName = request.optString("package_name").takeIf { it.isNotBlank() && it != "null" && it != "None" }
                    val appName = AppNameResolver.getAppName(context, packageName)
                    val reason = request.optString("reason").ifBlank { "No reason provided" }
                    val requestStatus = request.optString("status")

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppIcon(packageName ?: "", modifier = Modifier.size(32.dp))
                                Spacer(Modifier.width(10.dp))
                                Text("$minutes mins • $appName", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Text(requestStatus, style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("Reason: $reason", style = MaterialTheme.typography.bodySmall)
                            if (requestStatus == "PENDING") {
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        enabled = !busy,
                                        onClick = { decide(requestId, true) },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Approve") }

                                    OutlinedButton(
                                        enabled = !busy,
                                        onClick = { decide(requestId, false) },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Decline") }
                                }
                            }
                        }
                    }
                }
            }

            item {
                ParentInboxCard(context) { refreshRequests() }
            }

            item {
                FeatureTogglesCard(context)
            }

            if (FeatureToggleEngine.isCategoryBudgetsEnabled(context)) {
                item {
                    CategoryBudgetsCard(context, usage)
                }
            }

            item {
                var instantLock by remember { mutableStateOf(prefs.getBoolean("instant_pause_enabled", false)) }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (instantLock) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (instantLock) "🔒 DEVICE IS PAUSED" else "⚡ INSTANT REMOTE LOCK",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                if (instantLock) "All managed apps are currently locked."
                                else "Instantly lock all target apps (Dinner Time / Bedtime).",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = instantLock,
                            onCheckedChange = { locked ->
                                instantLock = locked
                                prefs.edit().putBoolean("instant_pause_enabled", locked).apply()
                                executor.execute {
                                    ApiClient.setInstantLock(context, locked)
                                }
                            }
                        )
                    }
                }
            }

            item {
                var webFilter by remember { mutableStateOf(WebFilterEngine.isEnabled(context)) }
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("🌐 Web & SafeSearch Content Filter", style = MaterialTheme.typography.titleMedium)
                            Text("Force Google SafeSearch, YouTube Restricted Mode & adult URL blocking", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = webFilter,
                            onCheckedChange = {
                                webFilter = it
                                WebFilterEngine.setEnabled(context, it)
                            }
                        )
                    }
                }
            }

            item {
                var systemGuard by remember { mutableStateOf(SystemGuardEngine.isEnabled(context)) }
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("🛡️ System Anti-Tamper Guard", style = MaterialTheme.typography.titleMedium)
                            Text("Disallow date/time changes, APK sideloading & safe mode bypasses", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = systemGuard,
                            onCheckedChange = {
                                systemGuard = it
                                SystemGuardEngine.setEnabled(context, it)
                            }
                        )
                    }
                }
            }

            item {
                val report = remember(usage) { AnalyticsEngine.generateReport(context, usage) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("📊 SCREEN TIME ANALYTICS & CATEGORIES", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("Total Today: ${report.totalScreenTimeMinutes} mins • Top Category: ${report.topCategory}")
                        if (report.anomalyWarning != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(report.anomalyWarning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        report.categories.forEach { cat ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${cat.icon} ${cat.category}", style = MaterialTheme.typography.bodyMedium)
                                Text("${cat.totalMinutes}m (${cat.percentage}%)", style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("DAILY SCREEN-TIME", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("${dailyLimit} minutes/day")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    dailyLimit = (dailyLimit - 15).coerceAtLeast(30)
                                    saveDraftLocally(rules, dailyLimit)
                                }
                            ) { Text("−15") }

                            Button(
                                onClick = {
                                    dailyLimit = (dailyLimit + 15).coerceAtMost(480)
                                    saveDraftLocally(rules, dailyLimit)
                                }
                            ) { Text("+15") }
                        }
                    }
                }
            }

            item { Text("APP LIMITS", style = MaterialTheme.typography.titleMedium) }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (selectedPackages.isEmpty()) "ALL APPS SELECTED (${rules.size})"
                                else "SELECTED: ${selectedPackages.size} / ${rules.size} APPS",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Button(
                                onClick = {
                                    selectedPackages = if (selectedPackages.size == rules.size) emptySet()
                                    else rules.map { it.packageName }.toSet()
                                }
                            ) {
                                Text(if (selectedPackages.size == rules.size) "Deselect All" else "Select All")
                            }
                        }

                        Text("Quick Apply to Selected Apps:", style = MaterialTheme.typography.bodySmall)

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(15, 30, 45).forEach { addMins ->
                                Button(
                                    onClick = { updateSelectedRules(addMins) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("+$addMins m") }
                            }
                            Button(
                                onClick = { updateSelectedRules(-15) },
                                modifier = Modifier.weight(1f)
                            ) { Text("−15m") }
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { updateSelectedRules(0, enableState = true) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Enable Selected") }
                            Button(
                                onClick = { updateSelectedRules(0, enableState = false) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Disable Selected") }
                        }
                    }
                }
            }

            item {
                var activePreset by remember { mutableStateOf(PresetModeEngine.getActivePreset(context)) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PresetStatusBanner(activePreset) {
                        activePreset = PresetModeEngine.MODE_NONE
                        PresetModeEngine.setActivePreset(context, PresetModeEngine.MODE_NONE)
                    }
                    OneTapPresetsBar(activePreset) { mode ->
                        activePreset = mode
                        PresetModeEngine.setActivePreset(context, mode)
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("🔍 Search Installed Apps") },
                    placeholder = { Text("Type app name (e.g. Instagram, Chrome)...") },
                    leadingIcon = { Icon(Icons.Default.Search, "Search") },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, "Clear")
                            }
                        }
                    },
                    singleLine = true
                )
            }

            val displayRules = rules
                .filter {
                    !AppScanner.isSystemStub(it.packageName, it.appName) &&
                            (searchQuery.isBlank() ||
                                    it.appName.contains(searchQuery, ignoreCase = true) ||
                                    it.packageName.contains(searchQuery, ignoreCase = true))
                }
                .sortedByDescending { rule -> usage.firstOrNull { u -> u.packageName == rule.packageName }?.minutes ?: 0L }

            items(displayRules, key = { it.packageName }) { rule ->
                val used = usage.firstOrNull { it.packageName == rule.packageName }?.minutes ?: 0L
                val remaining = (rule.limitMinutes - used.toInt()).coerceAtLeast(0)
                val isSelected = selectedPackages.contains(rule.packageName)

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    selectedPackages = if (checked) selectedPackages + rule.packageName
                                    else selectedPackages - rule.packageName
                                }
                            )
                            AppIcon(rule.packageName, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(rule.appName, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "Limit: ${rule.limitMinutes}m  •  Used: ${used}m  •  Left: ${remaining}m",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = { enabled ->
                                    saveDraftLocally(
                                        rules.map {
                                            if (it.packageName == rule.packageName)
                                                it.copy(enabled = enabled)
                                            else it
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            }

            item {
                Button(
                    enabled = !busy,
                    onClick = { saveDraftLocally(rules, dailyLimit) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Draft")
                }
            }

            item {
                Button(
                    enabled = !busy,
                    onClick = { publishToServer() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Publishing…" else "Publish Policy to Child")
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("POLICY STATUS", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(status)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Draft values persist on this device. Publishing creates a new server policy version; the child must then pull and acknowledge the latest version.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                val isExempt = remember { OEMProtection.isIgnoringBatteryOptimizations(context) }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isExempt) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("OEM BACKGROUND PROTECTION", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (isExempt) "✅ Battery Optimization: EXEMPT (Protection is protected from OEM task killers)"
                            else "⚠️ Battery Optimization: NOT EXEMPT (Android OS or OEM task killer may terminate background enforcement)"
                        )
                        Spacer(Modifier.height(8.dp))
                        if (!isExempt) {
                            Button(onClick = { OEMProtection.requestBatteryOptimizationExemption(context) }) {
                                Text("Request Battery Exemption")
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor() }

    var baseUrl by remember { mutableStateOf(ApiClient.getBaseUrl(context)) }
    var serverStatus by remember { mutableStateOf("Not checked") }
    var serverResponse by remember { mutableStateOf("") }
    var registered by remember { mutableStateOf(ApiClient.registered(context)) }
    var busy by remember { mutableStateOf(false) }

    fun runApi(action: () -> ApiResponse, onResult: (ApiResponse) -> Unit) {
        if (busy) return
        busy = true
        executor.execute {
            val result = action()
            context.mainExecutor.execute {
                busy = false
                onResult(result)
            }
        }
    }

    LaunchedEffect(Unit) {
        ApiClient.setBaseUrl(context, baseUrl)
        runApi(
            action = { ApiClient.health(context) },
            onResult = {
                serverStatus = if (it.ok) "SERVER ONLINE 🟢 (Connected to Render Cloud)" else "SERVER ERROR 🔴"
                serverResponse = if (it.ok) it.body else (it.error ?: it.body)
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose { executor.shutdownNow() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Parent ↔ Child Sync") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Real API Sync", style = MaterialTheme.typography.headlineSmall)
                Text("v0.8.2 connects this Android lab to the local FastAPI backend.")
            }

            item {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Backend URL") },
                    singleLine = true
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = baseUrl.contains("192.168.1.2"),
                        onClick = {
                            baseUrl = "http://192.168.1.2:8001"
                            ApiClient.setBaseUrl(context, baseUrl)
                        },
                        label = { Text("Mac mini (192.168.1.2)") }
                    )
                    FilterChip(
                        selected = baseUrl.contains("10.0.2.2"),
                        onClick = {
                            baseUrl = "http://10.0.2.2:8001"
                            ApiClient.setBaseUrl(context, baseUrl)
                        },
                        label = { Text("Emulator") }
                    )
                    FilterChip(
                        selected = baseUrl.startsWith("https://"),
                        onClick = {
                            if (!baseUrl.startsWith("https://")) {
                                baseUrl = "https://"
                            }
                        },
                        label = { Text("Cloud (HTTPS)") }
                    )
                }
            }

            item {
                Button(
                    enabled = !busy,
                    onClick = {
                        ApiClient.setBaseUrl(context, baseUrl)
                        runApi(
                            action = { ApiClient.health(context) },
                            onResult = {
                                serverStatus = if (it.ok) "SERVER ONLINE" else "SERVER ERROR"
                                serverResponse = if (it.ok) it.body else (it.error ?: it.body)
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Working…" else "Test Backend")
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("SERVER STATUS", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(serverStatus)
                        if (serverResponse.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(serverResponse, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Button(
                    enabled = !busy,
                    onClick = {
                        ApiClient.setBaseUrl(context, baseUrl)
                        runApi(
                            action = { ApiClient.registerDevice(context) },
                            onResult = {
                                registered = ApiClient.registered(context)
                                serverStatus = if (it.ok) "DEVICE REGISTERED" else "REGISTRATION FAILED"
                                serverResponse = if (it.ok) it.body else (it.error ?: it.body)
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Register This Device")
                }
            }

            if (registered) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("SERVER IDENTITY", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text("Family: ${ApiClient.serverFamilyId(context)}")
                            Text("Child: ${ApiClient.serverChildId(context)}")
                            Text("Device: ${ApiClient.serverDeviceId(context)}")
                        }
                    }
                }

                item {
                    Button(
                        enabled = !busy,
                        onClick = {
                            runApi(
                                action = { ApiClient.createServerPolicy(context) },
                                onResult = {
                                    serverStatus = if (it.ok) "SERVER POLICY CREATED" else "POLICY CREATE FAILED"
                                    serverResponse = if (it.ok) it.body else (it.error ?: it.body)
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Create Server Policy")
                    }
                }

                item {
                    Button(
                        enabled = !busy,
                        onClick = {
                            runApi(
                                action = { ApiClient.getSync(context) },
                                onResult = {
                                    serverStatus = if (it.ok) "SERVER SYNC CHECKED" else "SYNC CHECK FAILED"
                                    serverResponse = if (it.ok) it.body else (it.error ?: it.body)
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Check Server Sync")
                    }
                }

                item {
                    Button(
                        enabled = !busy,
                        onClick = {
                            runApi(
                                action = {
                                    val response = ApiClient.getSync(context)
                                    if (!response.ok) {
                                        response
                                    } else {
                                        val json = org.json.JSONObject(response.body)
                                        val version = when {
                                            json.has("available_policy_version") ->
                                                json.getInt("available_policy_version")
                                            json.has("applied_policy_version") ->
                                                json.getInt("applied_policy_version")
                                            else -> 0
                                        }
                                        if (version <= 0) {
                                            ApiResponse(false, 0, response.body, "No policy version available")
                                        } else {
                                            ApiClient.acknowledgeSync(context, version)
                                        }
                                    }
                                },
                                onResult = {
                                    serverStatus = if (it.ok) "SERVER SYNC ACKNOWLEDGED" else "SYNC ACK FAILED"
                                    serverResponse = if (it.ok) it.body else (it.error ?: it.body)
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Pull Latest Policy + Acknowledge")
                    }
                }

                item {
                    Button(
                        enabled = !busy,
                        onClick = {
                            ApiClient.clearRegistration(context)
                            registered = false
                            serverStatus = "Local server registration cleared"
                            serverResponse = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear Local Server Registration")
                    }
                }
            }

            item {
                Text(
                    "For a physical phone, replace the default 10.0.2.2 URL with your Mac's LAN IP, e.g. http://192.168.x.x:8001. Keep the Mac and phone on the same network.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PolicyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val usage = if (hasUsageAccess(context)) getTodayUsage(context) else emptyList()
    var policies by remember { mutableStateOf(loadPolicies(context, usage)) }
    var selectedPackages by remember { mutableStateOf(setOf<String>()) }

    fun updateSelected(minutesDelta: Int, setLimit: Int? = null, enableState: Boolean? = null) {
        val targets = if (selectedPackages.isEmpty()) policies.map { it.packageName }.toSet() else selectedPackages
        val updatedList = policies.map { p ->
            if (p.packageName in targets) {
                val newLimit = setLimit ?: (p.limitMinutes + minutesDelta).coerceIn(5, 240)
                val newEnabled = enableState ?: p.enabled
                p.copy(limitMinutes = newLimit, enabled = newEnabled)
            } else p
        }
        policies = updatedList
        updatedList.forEach { savePolicy(context, it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Local Policies") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("Policy Engine", style = MaterialTheme.typography.headlineSmall)
                Text("Local rules persist on the child device.")
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (selectedPackages.isEmpty()) "ALL APPS SELECTED (${policies.size})"
                                else "SELECTED: ${selectedPackages.size} / ${policies.size} APPS",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Button(
                                onClick = {
                                    selectedPackages = if (selectedPackages.size == policies.size) emptySet()
                                    else policies.map { it.packageName }.toSet()
                                }
                            ) {
                                Text(if (selectedPackages.size == policies.size) "Deselect All" else "Select All")
                            }
                        }

                        Text("Quick Apply to Selected Apps:", style = MaterialTheme.typography.bodySmall)

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(15, 30, 45).forEach { addMins ->
                                Button(
                                    onClick = { updateSelected(addMins) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("+$addMins m") }
                            }
                            Button(
                                onClick = { updateSelected(-15) },
                                modifier = Modifier.weight(1f)
                            ) { Text("−15m") }
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { updateSelected(0, enableState = true) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Enable Selected") }
                            Button(
                                onClick = { updateSelected(0, enableState = false) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Disable Selected") }
                        }

                        Button(
                            onClick = {
                                policies.forEach { savePolicy(context, it) }
                                android.widget.Toast.makeText(context, "✅ Local Policies Saved & Applied!", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("💾 Save & Apply All Policies")
                        }
                    }
                }
            }

            val displayPolicies = policies
                .filter { !AppScanner.isSystemStub(it.packageName, it.appName) }
                .sortedByDescending { policy -> usage.firstOrNull { u -> u.packageName == policy.packageName }?.minutes ?: 0L }

            items(displayPolicies, key = { it.packageName }) { policy ->
                val used = usage.firstOrNull { it.packageName == policy.packageName }?.minutes ?: 0L
                val remaining = (policy.limitMinutes - used.toInt()).coerceAtLeast(0)
                val isSelected = selectedPackages.contains(policy.packageName)

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    selectedPackages = if (checked) selectedPackages + policy.packageName
                                    else selectedPackages - policy.packageName
                                }
                            )
                            AppIcon(policy.packageName, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(policy.appName, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "Limit: ${policy.limitMinutes}m  •  Used: ${used}m  •  Left: ${remaining}m",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Switch(
                                checked = policy.enabled,
                                onCheckedChange = { enabled ->
                                    val updated = policy.copy(enabled = enabled)
                                    policies = policies.map { p -> if (p.packageName == policy.packageName) updated else p }
                                    savePolicy(context, updated)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtectionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    var isOwner by remember { mutableStateOf(dpm.isDeviceOwnerApp(context.packageName)) }

    val heartbeatPrefs = context.getSharedPreferences("heartbeat", Context.MODE_PRIVATE)
    val lastHeartbeat = heartbeatPrefs.getLong("last_heartbeat", 0L)
    val heartbeatAgeMinutes = if (lastHeartbeat == 0L) {
        Long.MAX_VALUE
    } else {
        (System.currentTimeMillis() - lastHeartbeat) / 60_000L
    }
    val heartbeatOk = lastHeartbeat > 0L && heartbeatAgeMinutes <= 20
    var snapshot by remember { mutableStateOf(ProtectionMonitor.snapshot(context)) }

    fun refreshProtection() {
        snapshot = ProtectionMonitor.snapshot(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Protection Health") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Protection Health", style = MaterialTheme.typography.headlineSmall)
                Text("Real-time enforcement & system protection status.")
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("PROTECTION STATE", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(snapshot.state.name)
                        Text(snapshot.detail, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        Text("Local policy version: v${snapshot.policyVersion}")
                    }
                }
            }
            item {
                HealthCard(
                    "Accessibility Protection Service",
                    AccessibilityGuardEngine.isAccessibilityEnabled(context),
                    if (AccessibilityGuardEngine.isAccessibilityEnabled(context)) "Active — real-time app limits, web filter & anti-uninstall"
                    else "Action required: Enable in Accessibility Settings"
                )
            }
            item { HealthCard("Usage Access", hasUsageAccess(context), "Required for usage monitoring & stats") }
            item { HealthCard("System Overlay Permission", AccessibilityGuardEngine.canDrawOverlays(context), "Required for lock screen displays") }
            item { HealthCard("Local Policy Cache", true, "Available offline without network") }
            item {
                HealthCard(
                    "Heartbeat",
                    heartbeatOk,
                    if (lastHeartbeat == 0L) "Waiting for first heartbeat"
                    else "Last heartbeat ${heartbeatAgeMinutes} min ago"
                )
            }
            item {
                HealthCard(
                    "Network",
                    snapshot.networkAvailable,
                    if (snapshot.networkAvailable) "Online" else "Offline — local policy remains available"
                )
            }
            item {
                HealthCard(
                    "Clock Integrity",
                    !snapshot.clockChanged,
                    if (snapshot.clockChanged) "Clock/timezone change detected"
                    else "No clock-change event detected"
                )
            }
            item {
                Button(
                    onClick = { refreshProtection() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, null)
                    Text(" Refresh Health")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnforcementScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
    val admin = ComponentName(context, LabDeviceAdminReceiver::class.java)

    var status by remember { mutableStateOf("Checking Device Owner status…") }
    var isOwner by remember { mutableStateOf(false) }
    val candidates = remember {
        val scanned = AppScanner.getInstalledApps(context)
        if (scanned.isNotEmpty()) {
            scanned.map { it.packageName to it.appName }
        } else {
            listOf(
                "com.instagram.android" to "Instagram",
                "com.jio.jioPlay.tv" to "JioPlayTV",
                "com.netflix.mediaclient" to "Netflix"
            )
        }
    }
    var selectedPackages by remember { mutableStateOf(setOf<String>()) }
    var usage by remember { mutableStateOf(emptyList<AppUsage>()) }
    var installRestricted by remember { mutableStateOf(false) }
    var suspendedMap by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var uninstallBlockedMap by remember { mutableStateOf(mapOf<String, Boolean>()) }

    fun refresh() {
        isOwner = dpm.isDeviceOwnerApp(context.packageName)
        usage = if (hasUsageAccess(context)) getTodayUsage(context) else emptyList()
        installRestricted = if (isOwner) {
            try {
                userManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS)
            } catch (_: Exception) { false }
        } else false

        if (isOwner) {
            val sMap = mutableMapOf<String, Boolean>()
            val uMap = mutableMapOf<String, Boolean>()
            candidates.forEach { (pkg, _) ->
                try {
                    uMap[pkg] = dpm.isUninstallBlocked(admin, pkg)
                } catch (_: Exception) {}
                try {
                    sMap[pkg] = dpm.isPackageSuspended(admin, pkg)
                } catch (_: Exception) {}
            }
            suspendedMap = sMap
            uninstallBlockedMap = uMap
        }

        status = if (isOwner) "Device Owner ACTIVE" else "Device Owner NOT ACTIVE"
    }

    fun setSuspended(suspend: Boolean) {
        if (!isOwner || selectedPackages.isEmpty()) {
            status = "Device Owner required and at least one target app must be selected."
            return
        }
        try {
            val pkgs = selectedPackages.toTypedArray()
            val failures = dpm.setPackagesSuspended(admin, pkgs, suspend)
            status = if (failures.isEmpty()) {
                if (suspend) "SUSPENDED ${selectedPackages.size} APPS"
                else "RESUMED ${selectedPackages.size} APPS"
            } else {
                "Failed to change state for: ${failures.joinToString()}"
            }
            selectedPackages.forEach { pkg ->
                EventLog.record(context, if (suspend) "APP_SUSPENDED $pkg" else "APP_RESUMED $pkg")
            }
            refresh()
        } catch (e: Exception) {
            status = "Suspend/resume error: ${e.message}"
        }
    }

    fun setUninstallBlock(block: Boolean) {
        if (!isOwner || selectedPackages.isEmpty()) {
            status = "Device Owner required and at least one target app must be selected."
            return
        }
        try {
            var count = 0
            selectedPackages.forEach { pkg ->
                try {
                    dpm.setUninstallBlocked(admin, pkg, block)
                    count++
                    EventLog.record(context, "UNINSTALL_BLOCK $pkg=$block")
                } catch (_: Exception) {}
            }
            status = if (block) "UNINSTALL BLOCKED FOR $count APPS" else "UNINSTALL ALLOWED FOR $count APPS"
            refresh()
        } catch (e: Exception) {
            status = "Uninstall restriction error: ${e.message}"
        }
    }

    fun setInstallRestriction(restrict: Boolean) {
        if (!isOwner) {
            status = "Device Owner required."
            return
        }
        try {
            if (restrict) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
            } else {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
            }
            installRestricted = restrict
            status = if (restrict) "INSTALL APPS BLOCKED" else "INSTALL APPS ALLOWED"
            EventLog.record(context, "INSTALL_RESTRICTION=$restrict")
            refresh()
        } catch (e: Exception) {
            status = "Install restriction error: ${e.message}"
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Enhanced Protection Lab") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header / Management Authority Card
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("MANAGEMENT AUTHORITY", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (isOwner) "✓ ACTIVE" else "○ NOT ACTIVE",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isOwner) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                        if (status.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(status, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // Global Controls (Install restrictions)
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("GLOBAL DEVICE RESTRICTIONS", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = isOwner,
                                onClick = { setInstallRestriction(true) },
                                modifier = Modifier.weight(1f),
                                colors = if (installRestricted) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()
                            ) {
                                Text("Block Installs")
                            }
                            Button(
                                enabled = isOwner,
                                onClick = { setInstallRestriction(false) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Allow Installs")
                            }
                        }
                    }
                }
            }

            // Top Bulk Quick Action Bar (Sticky / Direct Actions)
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "SELECTED: ${selectedPackages.size} / ${candidates.size} APPS",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = {
                                    selectedPackages = candidates.map { it.first }.toSet()
                                }) { Text("Select All") }
                                TextButton(onClick = {
                                    selectedPackages = emptySet()
                                }) { Text("Clear") }
                            }
                        }

                        // Action Buttons Grid (2 rows of 2 buttons)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = isOwner && selectedPackages.isNotEmpty(),
                                onClick = { setSuspended(true) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("⛔ Suspend")
                            }
                            Button(
                                enabled = isOwner && selectedPackages.isNotEmpty(),
                                onClick = { setSuspended(false) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("▶️ Resume")
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = isOwner && selectedPackages.isNotEmpty(),
                                onClick = { setUninstallBlock(true) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("🔒 Block Uninstall")
                            }
                            Button(
                                enabled = isOwner && selectedPackages.isNotEmpty(),
                                onClick = { setUninstallBlock(false) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("🔓 Allow Uninstall")
                            }
                        }
                    }
                }
            }

            item {
                Text("TARGET APPS", style = MaterialTheme.typography.titleMedium)
            }

            // Compact multi-select app list
            val displayCandidates = candidates
                .filter { !AppScanner.isSystemStub(it.first, it.second) }
                .sortedByDescending { candidate -> usage.firstOrNull { u -> u.packageName == candidate.first }?.minutes ?: 0L }

            items(displayCandidates) { candidate ->
                val pkg = candidate.first
                val appName = candidate.second
                val isSelected = selectedPackages.contains(pkg)
                val isSuspended = suspendedMap[pkg] ?: false
                val isUninstallBlocked = uninstallBlockedMap[pkg] ?: false
                val minutesUsed = usage.firstOrNull { it.packageName == pkg }?.minutes ?: 0L

                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedPackages = if (isSelected) {
                                selectedPackages - pkg
                            } else {
                                selectedPackages + pkg
                            }
                        },
                    colors = if (isSelected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)) else CardDefaults.cardColors()
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { checked ->
                                selectedPackages = if (checked) selectedPackages + pkg else selectedPackages - pkg
                            }
                        )
                        AppIcon(pkg, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(appName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Text("Usage: ${minutesUsed}m today", style = MaterialTheme.typography.bodyMedium)
                            }
                            if (isSuspended || isUninstallBlocked) {
                                Spacer(Modifier.height(2.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (isSuspended) {
                                        Text("• ⛔ Suspended", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    }
                                    if (isUninstallBlocked) {
                                        Text("• 🔒 Protected", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RoutineEditDialog(
    routine: FamilyRoutine?,
    onDismiss: () -> Unit,
    onSave: (FamilyRoutine) -> Unit,
    onDelete: ((String) -> Unit)? = null
) {
    var name by remember { mutableStateOf(routine?.name ?: "") }
    var start by remember { mutableStateOf(routine?.start ?: "09:00") }
    var end by remember { mutableStateOf(routine?.end ?: "17:00") }
    var weekdaysOnly by remember { mutableStateOf(routine?.weekdaysOnly ?: true) }
    var errorText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (routine == null) "Add New Routine" else "Edit Routine") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Routine Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = start,
                        onValueChange = { start = it },
                        label = { Text("Start (HH:mm)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = end,
                        onValueChange = { end = it },
                        label = { Text("End (HH:mm)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Weekdays Only (Mon-Fri)")
                    Switch(checked = weekdaysOnly, onCheckedChange = { weekdaysOnly = it })
                }
                if (errorText.isNotBlank()) {
                    Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.isBlank()) {
                    errorText = "Please enter a routine name"
                    return@Button
                }
                val newId = routine?.id ?: "routine_${System.currentTimeMillis()}"
                onSave(FamilyRoutine(newId, name.trim(), start.trim(), end.trim(), weekdaysOnly, routine?.enabled ?: true))
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (routine != null && onDelete != null) {
                    TextButton(onClick = { onDelete(routine.id) }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var routines by remember { mutableStateOf(ScheduleEngine.load(context)) }
    var editingRoutine by remember { mutableStateOf<FamilyRoutine?>(null) }
    var isAdding by remember { mutableStateOf(false) }

    fun refresh() {
        routines = ScheduleEngine.load(context)
    }

    if (isAdding) {
        RoutineEditDialog(
            routine = null,
            onDismiss = { isAdding = false },
            onSave = { newRoutine ->
                ScheduleEngine.save(context, newRoutine)
                EventLog.record(context, "ROUTINE_ADDED ${newRoutine.name}")
                HeartbeatWorker.schedule(context)
                isAdding = false
                refresh()
            }
        )
    }

    editingRoutine?.let { target ->
        RoutineEditDialog(
            routine = target,
            onDismiss = { editingRoutine = null },
            onSave = { updated ->
                ScheduleEngine.save(context, updated)
                EventLog.record(context, "ROUTINE_UPDATED ${updated.name}")
                HeartbeatWorker.schedule(context)
                editingRoutine = null
                refresh()
            },
            onDelete = { id ->
                ScheduleEngine.delete(context, id)
                EventLog.record(context, "ROUTINE_DELETED $id")
                HeartbeatWorker.schedule(context)
                editingRoutine = null
                refresh()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Family Routines") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { isAdding = true }) {
                        Icon(Icons.Default.Add, "Add Routine")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Smart Routines", style = MaterialTheme.typography.headlineSmall)
                        Text("Schedules are evaluated in the background.", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { isAdding = true }) {
                        Text("➕ Add Routine")
                    }
                }
                Spacer(Modifier.height(6.dp))
                val currentRoutine = ScheduleEngine.activeRoutine(context)
                Text(
                    "Current: ${currentRoutine?.name ?: "No active routine"}",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            items(routines, key = { it.id }) { routine ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(routine.name, style = MaterialTheme.typography.titleMedium)
                                Text("${routine.start} → ${routine.end}", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (routine.weekdaysOnly) "Monday–Friday"
                                    else "Every day",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(onClick = { editingRoutine = routine }) {
                                    Icon(Icons.Default.Edit, "Edit")
                                }
                                Switch(
                                    checked = routine.enabled,
                                    onCheckedChange = { enabled ->
                                        val updated = routine.copy(enabled = enabled)
                                        ScheduleEngine.save(context, updated)
                                        EventLog.record(
                                            context,
                                            "ROUTINE_CHANGED ${routine.name} enabled=$enabled"
                                        )
                                        HeartbeatWorker.schedule(context)
                                        refresh()
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        val activeRoutineId = ScheduleEngine.activeRoutine(context)?.id
                        val active = activeRoutineId == routine.id
                        Text(
                            if (active) "● ACTIVE NOW" else "○ Inactive",
                            color = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HealthCard(title: String, healthy: Boolean, detail: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(if (healthy) "✓ OK" else "○ Not active")
            }
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

fun getTodayUsage(context: Context): List<AppUsage> {
    val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val now = System.currentTimeMillis()
    val start = now - 24L * 60L * 60L * 1000L
    val totals = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
        .filter { it.totalTimeInForeground > 0L && it.packageName != context.packageName }
        .groupBy { it.packageName }
        .mapValues { (_, stats) -> stats.sumOf { it.totalTimeInForeground } }

    return totals.filterValues { it > 0L }
        .toList()
        .sortedByDescending { it.second }
        .take(20)
        .map { (pkg, ms) -> AppUsage(resolveAppName(context, pkg), pkg, ms / 60_000L) }
}

fun resolveAppName(context: Context, packageName: String): String =
    AppNameResolver.getAppName(context, packageName)

fun loadPolicies(context: Context, usage: List<AppUsage>): List<AppPolicy> {
    val prefs = context.getSharedPreferences("policies", Context.MODE_PRIVATE)
    val installed = AppScanner.getInstalledApps(context)
    val targets = if (installed.isNotEmpty()) {
        installed.map { app ->
            val min = usage.firstOrNull { u -> u.packageName == app.packageName }?.minutes ?: 0L
            AppUsage(app.appName, app.packageName, min)
        }
    } else {
        if (usage.isNotEmpty()) usage else listOf(
            AppUsage("Instagram", "com.instagram.android", 0L),
            AppUsage("JioPlayTV", "com.jio.jioPlay.tv", 0L),
            AppUsage("Netflix", "com.netflix.mediaclient", 0L)
        )
    }
    return targets.map {
        AppPolicy(
            it.packageName,
            it.appName,
            prefs.getInt("limit_${it.packageName}", 30),
            prefs.getBoolean("enabled_${it.packageName}", false)
        )
    }
}

fun savePolicy(context: Context, policy: AppPolicy) {
    context.getSharedPreferences("policies", Context.MODE_PRIVATE).edit()
        .putInt("limit_${policy.packageName}", policy.limitMinutes)
        .putBoolean("enabled_${policy.packageName}", policy.enabled)
        .apply()
}


fun evaluateEnforcement(context: Context, usage: List<AppUsage>): String {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    if (!dpm.isDeviceOwnerApp(context.packageName)) {
        return "MONITORING ONLY — Device Owner not active"
    }

    val admin = ComponentName(context, LabDeviceAdminReceiver::class.java)
    val prefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
    val candidates = listOf(
        "com.instagram.android" to "Instagram",
        "com.jio.jioPlay.tv" to "JioPlayTV",
        "com.netflix.mediaclient" to "Netflix"
    )

    var suspended = 0
    var resumed = 0

    candidates.forEach { (pkg, _) ->
        val enabled = prefs.getBoolean("enabled_$pkg", false)
        val limit = prefs.getInt("limit_$pkg", 30)
        val used = usage.firstOrNull { it.packageName == pkg }?.minutes ?: 0L
        if (enabled && used >= limit) {
            try {
                dpm.setPackagesSuspended(admin, arrayOf(pkg), true)
                suspended++
                EventLog.record(context, "APP_SUSPENDED $pkg")
            } catch (e: Exception) {
                EventLog.record(context, "APP_SUSPEND_ERROR $pkg ${e.message}")
            }
        } else {
            try {
                dpm.setPackagesSuspended(admin, arrayOf(pkg), false)
                resumed++
            } catch (_: Exception) { }
        }
    }

    return "DEVICE OWNER ACTIVE — $suspended app(s) suspended, $resumed resumed"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var events by remember { mutableStateOf(EventLog.read(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Protection Events") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { events = EventLog.read(context) }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item { Text("Last 50 events", style = MaterialTheme.typography.headlineSmall) }
            items(events) { event ->
                Card(Modifier.fillMaxWidth()) {
                    Text(event, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
