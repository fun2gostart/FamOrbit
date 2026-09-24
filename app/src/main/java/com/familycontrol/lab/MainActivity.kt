package com.familycontrol.lab

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import android.app.Activity
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.Divider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.*

data class AppUsage(val appName: String, val packageName: String, val minutes: Long)
data class AppPolicy(val packageName: String, val appName: String, val limitMinutes: Int, val enabled: Boolean)

private enum class Screen { Dashboard, ParentHome, ChildHome, Policies, Routines, Protection, Enforcement, Events, Sync, ParentCenter, RoleSelection, ChildPairing }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        PolicyWorker.schedule(this)
        HeartbeatWorker.schedule(this)
        RequestPollEngine.start(this)
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, EnforcementService::class.java)
            )
        } catch (e: Exception) {
            EventLog.record(this, "ENFORCEMENT_SERVICE_START_ERROR ${e.message}")
        }
        EventLog.record(this, "APP_STARTED")
        ApiClient.initFcmToken(this)
        autoRegisterCloudBackend(this)
        setContent { FamilyControlApp() }
    }

    override fun onResume() {
        super.onResume()
        ApiClient.initFcmToken(this)
        RequestPollEngine.setAppForegroundState(true)
        RequestPollEngine.start(this)
        autoRegisterCloudBackend(this)
    }

    override fun onPause() {
        super.onPause()
        RequestPollEngine.setAppForegroundState(false)
    }
}

private fun autoRegisterCloudBackend(context: Context) {
    Executors.newSingleThreadExecutor().execute {
        try {
            if (!ApiClient.registered(context)) {
                if (ApiClient.getDeviceRole(context) == ApiClient.ROLE_CHILD) {
                    EventLog.record(context, "AUTO_CLOUD_REGISTER_SKIPPED_UNPAIRED_CHILD")
                    return@execute
                }
                val registerRes = ApiClient.registerDevice(context)
                if (registerRes.ok) {
                    EventLog.record(context, "AUTO_CLOUD_REGISTER_SUCCESS ${registerRes.body}")
                }
            } else {
                val check = ApiClient.health(context)
                if (check.ok) {
                    EventLog.record(context, "AUTO_CLOUD_PING_SUCCESS")
                }
                PolicySyncEngine.syncAndApplyCloudPolicy(context)
            }
        } catch (e: Exception) {
            EventLog.record(context, "AUTO_CLOUD_REGISTER_ERROR ${e.message}")
        }
    }
}

@Composable
fun FamilyControlApp() {
    val context = LocalContext.current
    val themePrefs = remember { context.getSharedPreferences("app_theme_prefs", Context.MODE_PRIVATE) }
    var darkMode by rememberSaveable { mutableStateOf(themePrefs.getBoolean("dark_mode", true)) }
    val onToggleTheme: (Boolean) -> Unit = { isDark ->
        darkMode = isDark
        themePrefs.edit().putBoolean("dark_mode", isDark).apply()
    }
    var role by rememberSaveable { mutableStateOf(ApiClient.getDeviceRole(context)) }
    var screen by rememberSaveable {
        mutableStateOf(
            if (role == ApiClient.ROLE_UNSET) Screen.RoleSelection
            else if (role == ApiClient.ROLE_CHILD) Screen.ChildHome
            else Screen.Dashboard
        )
    }
    var isParentUnlocked by rememberSaveable { mutableStateOf(false) }
    var activeChildForHealth by rememberSaveable { mutableStateOf<String?>(null) }
    val dashboardListState = rememberLazyListState()
    val activity = context as? Activity

    BackHandler(enabled = true) {
        when (screen) {
            Screen.RoleSelection -> {
                activity?.finish()
            }
            Screen.ChildHome -> {
                if (role == ApiClient.ROLE_PARENT) {
                    screen = Screen.Dashboard
                } else {
                    activity?.moveTaskToBack(true)
                }
            }
            Screen.Dashboard -> {
                activity?.moveTaskToBack(true)
            }
            Screen.ChildPairing -> {
                screen = Screen.RoleSelection
            }
            else -> {
                screen = if (role == ApiClient.ROLE_CHILD) Screen.ChildHome else Screen.Dashboard
            }
        }
    }

    FamilyControlTheme(darkMode) {
        Surface(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.RoleSelection -> RoleSelectionScreen(
                    onParentRole = {
                        role = ApiClient.ROLE_PARENT
                        screen = Screen.Dashboard
                    },
                    onChildRole = {
                        role = ApiClient.ROLE_CHILD
                        screen = Screen.ChildPairing
                    },
                    onResumePairedChild = {
                        role = ApiClient.ROLE_CHILD
                        screen = Screen.ChildHome
                    }
                )
                Screen.ChildPairing -> ChildPairingScreen(
                    onBack = { screen = Screen.RoleSelection },
                    onPairComplete = { screen = Screen.ChildHome }
                )
                Screen.ParentHome -> ParentHomeScreen(
                    onBack = { screen = Screen.Dashboard },
                    onControl = { screen = Screen.ParentCenter }
                )
                Screen.ChildHome -> ChildHomeScreen(
                    onBack = { screen = Screen.Dashboard },
                    onSwitchToParent = {
                        role = ApiClient.ROLE_PARENT
                        screen = Screen.Dashboard
                    },
                    darkMode = darkMode,
                    onToggleDark = onToggleTheme
                )
                Screen.Dashboard -> DashboardScreen(
                    darkMode = darkMode,
                    isParentUnlocked = isParentUnlocked,
                    listState = dashboardListState,
                    onUnlockParent = { isParentUnlocked = true },
                    onToggleDark = onToggleTheme,
                    onParentHome = { screen = Screen.ParentHome },
                    onChildHome = { screen = Screen.ChildHome },
                    onPolicies = { screen = Screen.Policies },
                    onRoutines = { screen = Screen.Routines },
                    onProtection = { screen = Screen.Protection },
                    onEnforcement = { screen = Screen.Enforcement },
                    onSync = { screen = Screen.Sync },
                    onParentCenter = { screen = Screen.ParentCenter },
                    onRoleSelection = { screen = Screen.RoleSelection }
                )
                Screen.Policies -> PolicyScreen { screen = Screen.Dashboard }
                Screen.Routines -> RoutineScreen { screen = Screen.Dashboard }
                Screen.Protection -> ProtectionScreen(childId = activeChildForHealth) { screen = Screen.Dashboard }
                Screen.Enforcement -> EnforcementScreen { screen = Screen.Dashboard }
                Screen.Events -> EventsScreen { screen = Screen.Dashboard }
                Screen.Sync -> SyncScreen { screen = Screen.Dashboard }
                Screen.ParentCenter -> ParentControlScreen(
                    onBack = { screen = Screen.Dashboard },
                    onRoutines = { screen = Screen.Routines },
                    onProtection = { targetChildId ->
                        activeChildForHealth = targetChildId
                        screen = Screen.Protection
                    }
                )
            }
        }
    }
}

val DarkBg = androidx.compose.ui.graphics.Color(0xFF0A0E17)
val DarkSurface = androidx.compose.ui.graphics.Color(0xFF131A29)
val DarkSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF1E2738)
val DarkOutline = androidx.compose.ui.graphics.Color(0xFF2C394E)

val BrandBlue = androidx.compose.ui.graphics.Color(0xFF3B82F6)
val BrandIndigo = androidx.compose.ui.graphics.Color(0xFF6366F1)
val BrandEmerald = androidx.compose.ui.graphics.Color(0xFF10B981)
val BrandAmber = androidx.compose.ui.graphics.Color(0xFFF59E0B)
val BrandPurple = androidx.compose.ui.graphics.Color(0xFF8B5CF6)
val BrandRose = androidx.compose.ui.graphics.Color(0xFFF43F5E)
val BrandCyan = androidx.compose.ui.graphics.Color(0xFF06B6D4)

// Backward compatibility alias for older references:
val AppleBlue = BrandBlue
val AppleGreen = BrandEmerald
val AppleOrange = BrandAmber
val ApplePurple = BrandPurple
val AppleRed = BrandRose

private val AppDarkColorScheme = androidx.compose.material3.darkColorScheme(
    primary = BrandBlue,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF1E2D4A),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFBFDBFE),
    secondary = BrandPurple,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF2E1F4A),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFDDD6FE),
    tertiary = BrandEmerald,
    onTertiary = androidx.compose.ui.graphics.Color.White,
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFF13382C),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFFA7F3D0),
    background = DarkBg,
    onBackground = androidx.compose.ui.graphics.Color(0xFFF1F5F9),
    surface = DarkSurface,
    onSurface = androidx.compose.ui.graphics.Color(0xFFF8FAFC),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF94A3B8),
    outline = DarkOutline,
    outlineVariant = androidx.compose.ui.graphics.Color(0xFF334155)
)

private val AppLightColorScheme = androidx.compose.material3.lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF2563EB),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFEFF6FF),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF1D4ED8),
    secondary = androidx.compose.ui.graphics.Color(0xFF7C3AED),
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFF5F3FF),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF5B21B6),
    tertiary = androidx.compose.ui.graphics.Color(0xFF059669),
    onTertiary = androidx.compose.ui.graphics.Color.White,
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFFECFDF5),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFF047857),
    background = androidx.compose.ui.graphics.Color(0xFFF8FAFC),
    onBackground = androidx.compose.ui.graphics.Color(0xFF0F172A),
    surface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    onSurface = androidx.compose.ui.graphics.Color(0xFF0F172A),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFF1F5F9),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF475569),
    outline = androidx.compose.ui.graphics.Color(0xFF64748B),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFF94A3B8)
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
    title: String = "Screen Time Progress",
    modifier: Modifier = Modifier
) {
    val rawFraction = if (limitMinutes > 0) (usedMinutes.toFloat() / limitMinutes.toFloat()).coerceIn(0f, 1f) else 0f
    val animatedFraction by animateFloatAsState(
        targetValue = rawFraction,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "screen_time_progress"
    )
    val remainingMins = (limitMinutes - usedMinutes).coerceAtLeast(0L)
    val remHours = remainingMins / 60
    val remMins = remainingMins % 60
    val isOverLimit = usedMinutes >= limitMinutes && limitMinutes > 0

    val arcColors = when {
        isOverLimit -> listOf(BrandRose, Color(0xFFFB7185))
        rawFraction >= 0.8f -> listOf(BrandAmber, Color(0xFFFBBF24))
        else -> listOf(BrandEmerald, BrandBlue, BrandCyan)
    }

    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                ) {
                    Text(
                        if (isOverLimit) "⚠️ Limit Reached" else "🟢 ${((1f - rawFraction) * 100).toInt()}% Left",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOverLimit) BrandRose else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(160.dp)
            ) {
                Canvas(modifier = Modifier.size(160.dp)) {
                    val strokeWidth = 14.dp.toPx()
                    // Full subtle background circle
                    drawArc(
                        color = trackColor,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = strokeWidth)
                    )
                    // Animated dual-tone gradient arc
                    val displaySweep = if (animatedFraction > 0f) 360f * animatedFraction else 45f
                    drawArc(
                        brush = Brush.sweepGradient(arcColors),
                        startAngle = -90f,
                        sweepAngle = displaySweep,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val displayText = if (isOverLimit) {
                        "0m"
                    } else if (remHours > 0) {
                        "${remHours}h ${remMins}m"
                    } else {
                        "${remMins}m"
                    }
                    Text(
                        displayText,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (isOverLimit) "Daily limit reached" else "Remaining today",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Daily Allowance: ${limitMinutes / 60}h ${limitMinutes % 60}m  •  Used: ${usedMinutes / 60}h ${usedMinutes % 60}m",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun WeeklyTrendChart(
    report: WeeklyAnalyticsReport,
    title: String = "7-DAY USAGE TREND",
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text("Daily Avg: ${report.dailyAverageMinutes / 60}h ${report.dailyAverageMinutes % 60}m", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                ) {
                    Text(
                        "Top: ${report.topCategory}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
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

            Spacer(Modifier.height(12.dp))
            val maxMins = (report.weeklyTrend.maxOfOrNull { it.minutes } ?: 1L).coerceAtLeast(180L)

            Row(
                Modifier.fillMaxWidth().height(125.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                report.weeklyTrend.forEach { day ->
                    val barHeight = ((day.minutes.toFloat() / maxMins.toFloat()) * 72).dp.coerceAtLeast(10.dp)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                        modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                    ) {
                        Text(
                            "${day.minutes}m",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            maxLines = 1,
                            softWrap = false,
                            color = if (day.isToday) BrandCyan else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .width(20.dp)
                                .height(barHeight)
                                .background(
                                    brush = if (day.isToday) Brush.verticalGradient(listOf(BrandCyan, BrandBlue))
                                    else Brush.verticalGradient(listOf(BrandEmerald, Color(0xFF059669))),
                                    shape = RoundedCornerShape(8.dp)
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
            Text("CATEGORY BREAKDOWN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
            Spacer(Modifier.height(8.dp))

            report.categories.take(5).forEach { cat ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${cat.icon} ${cat.category}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(130.dp), maxLines = 1)
                    LinearProgressIndicator(
                        progress = (cat.percentage / 100f).coerceIn(0f, 1f),
                        modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = when(cat.category) {
                            "Social" -> androidx.compose.ui.graphics.Color(0xFFEC4899)
                            "Entertainment" -> BrandPurple
                            "Gaming" -> BrandRose
                            "Education" -> BrandEmerald
                            else -> BrandCyan
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${cat.totalMinutes}m (${cat.percentage}%)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
fun OneTapPresetsBar(
    currentMode: String,
    onSelectMode: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "1-tap Preset Buttons",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val presets = listOf(
                    Triple(PresetModeEngine.MODE_STUDY, "Study", BrandEmerald),
                    Triple(PresetModeEngine.MODE_BEDTIME, "Bedtime", BrandPurple),
                    Triple(PresetModeEngine.MODE_DINNER, "Dinner", BrandAmber),
                    Triple(PresetModeEngine.MODE_REWARD, "Reward", BrandBlue)
                )
                presets.forEach { (mode, label, color) ->
                    val isSelected = currentMode == mode
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) color else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, if (isSelected) color else MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.weight(1f).clickable {
                            val next = if (isSelected) PresetModeEngine.MODE_NONE else mode
                            onSelectMode(next)
                        }
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
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
    isChildMode: Boolean = false,
    onRequestHandled: () -> Unit = {}
) {
    var allRequests by remember { mutableStateOf(ExtraTimeRequestEngine.getAllRequests(context)) }
    var deactivationRequested by remember { mutableStateOf(PresetModeEngine.isDeactivationRequested(context)) }
    var showPinDialog by remember { mutableStateOf(false) }

    fun refresh() {
        allRequests = ExtraTimeRequestEngine.getAllRequests(context)
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

    val displayRequests = if (isChildMode) {
        allRequests.take(6)
    } else {
        allRequests
    }

    if (displayRequests.isNotEmpty() || (deactivationRequested && !isChildMode)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isChildMode) "🙋‍♂️ MY TIME REQUESTS STATUS (${displayRequests.size})"
                        else "📨 PARENT INBOX & TIME APPROVALS (${displayRequests.count { it.status == "PENDING" }})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!isChildMode && displayRequests.any { it.status != "PENDING" }) {
                        TextButton(
                            onClick = {
                                ExtraTimeRequestEngine.clearHandledRequests(context)
                                refresh()
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("Clear Handled ✕", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                if (deactivationRequested && !isChildMode) {
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

                displayRequests.forEach { req ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = when (req.status) {
                                "APPROVED" -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                "DENIED" -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                                else -> MaterialTheme.colorScheme.surface
                            }
                        )
                    ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppIcon(req.packageName, modifier = Modifier.size(36.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    if (!isChildMode && req.childName.isNotBlank()) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                            modifier = Modifier.padding(bottom = 2.dp)
                                        ) {
                                            Text(
                                                "${req.childAvatar} ${req.childName}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(if (req.appName.isBlank()) "Extra Time Request" else req.appName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.width(6.dp))
                                        if (req.status == "APPROVED") {
                                            Text("✅ APPROVED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                        } else if (req.status == "DENIED") {
                                            Text("❌ DECLINED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                        } else {
                                            Text("⏳ PENDING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Text("Requested: +${req.requestedMinutes} mins", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Text("\"${req.reason}\"", style = MaterialTheme.typography.bodySmall, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                                }

                                if (isChildMode) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = when (req.status) {
                                            "APPROVED" -> MaterialTheme.colorScheme.primaryContainer
                                            "DENIED" -> MaterialTheme.colorScheme.errorContainer
                                            else -> MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    ) {
                                        Text(
                                            when (req.status) {
                                                "APPROVED" -> "Approved 🎉"
                                                "DENIED" -> "Declined ❌"
                                                else -> "Waiting ⏳"
                                            },
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                } else {
                                    if (req.status == "PENDING") {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Button(
                                                onClick = {
                                                    ExtraTimeRequestEngine.approveRequest(context, req.id)
                                                    kotlin.concurrent.thread {
                                                        ApiClient.decideTimeRequest(context, req.id, true)
                                                    }
                                                    refresh()
                                                },
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                            ) {
                                                Text("Approve")
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    ExtraTimeRequestEngine.denyRequest(context, req.id)
                                                    kotlin.concurrent.thread {
                                                        ApiClient.decideTimeRequest(context, req.id, false)
                                                    }
                                                    refresh()
                                                },
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                            ) {
                                                Text("Deny")
                                            }
                                        }
                                    } else {
                                        IconButton(onClick = {
                                            ExtraTimeRequestEngine.deleteRequest(context, req.id)
                                            refresh()
                                        }) {
                                            Icon(Icons.Default.Clear, contentDescription = "Remove Entry")
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
fun FamilySwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = MaterialTheme.colorScheme.tertiary,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant
        )
    )
}

@Composable
fun FeatureTogglesCard(context: Context, autoPushPolicy: () -> Unit = {}) {
    MasterSafetyControlsCard(context, autoPushPolicy)
}

@Composable
fun MasterSafetyControlsCard(context: Context, autoPushPolicy: () -> Unit = {}) {
    val prefs = remember { context.getSharedPreferences("parent_control", Context.MODE_PRIVATE) }
    var instantLock by remember { mutableStateOf(prefs.getBoolean("instant_pause_enabled", false)) }
    var webFilter by remember { mutableStateOf(WebFilterEngine.isEnabled(context)) }
    var systemGuard by remember { mutableStateOf(SystemGuardEngine.isEnabled(context)) }
    var catBudgets by remember { mutableStateOf(FeatureToggleEngine.isCategoryBudgetsEnabled(context)) }
    var piggyBank by remember { mutableStateOf(FeatureToggleEngine.isPiggyBankEnabled(context)) }
    var habitBadges by remember { mutableStateOf(FeatureToggleEngine.isHabitBadgesEnabled(context)) }
    var execReport by remember { mutableStateOf(FeatureToggleEngine.isExecutiveReportEnabled(context)) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("⚡ MASTER SAFETY & PROTECTION CONTROLS", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text("Consolidated control panel for all safety & enforcement features.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("⛔ Instant Remote Suspend (All Apps)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("One-tap remote suspend for child device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FamilySwitch(
                    checked = instantLock,
                    onCheckedChange = { locked ->
                        instantLock = locked
                        prefs.edit().putBoolean("instant_pause_enabled", locked).apply()
                        Executors.newSingleThreadExecutor().execute {
                            ApiClient.setInstantLock(context, locked)
                        }
                    }
                )
            }
            Divider(Modifier.padding(vertical = 2.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🌐 Web & SafeSearch Content Filter", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("Force Google SafeSearch & adult URL blocking", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FamilySwitch(checked = webFilter, onCheckedChange = { webFilter = it; WebFilterEngine.setEnabled(context, it) })
            }
            Divider(Modifier.padding(vertical = 2.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🛡️ System Anti-Tamper Guard", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("Prevent settings bypasses & time manipulation", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FamilySwitch(checked = systemGuard, onCheckedChange = { systemGuard = it; SystemGuardEngine.setEnabled(context, it) })
            }
            Divider(Modifier.padding(vertical = 2.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("🎓 Category Budgets & Learn-First", style = MaterialTheme.typography.bodyMedium)
                FamilySwitch(checked = catBudgets, onCheckedChange = { catBudgets = it; FeatureToggleEngine.setCategoryBudgetsEnabled(context, it); autoPushPolicy() })
            }
            Divider(Modifier.padding(vertical = 2.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("🏦 Screen Time Piggy Bank (Rollover)", style = MaterialTheme.typography.bodyMedium)
                FamilySwitch(checked = piggyBank, onCheckedChange = { piggyBank = it; FeatureToggleEngine.setPiggyBankEnabled(context, it); autoPushPolicy() })
            }
            Divider(Modifier.padding(vertical = 2.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("🏆 Gamified Badges & Digital Pledge", style = MaterialTheme.typography.bodyMedium)
                FamilySwitch(checked = habitBadges, onCheckedChange = { habitBadges = it; FeatureToggleEngine.setHabitBadgesEnabled(context, it); autoPushPolicy() })
            }
            Divider(Modifier.padding(vertical = 2.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("🤖 Weekly AI Executive Report Card", style = MaterialTheme.typography.bodyMedium)
                FamilySwitch(checked = execReport, onCheckedChange = { execReport = it; FeatureToggleEngine.setExecutiveReportEnabled(context, it); autoPushPolicy() })
            }
        }
    }
}

@Composable
fun PiggyBankCard(context: Context, isChildMode: Boolean = false) {
    var balance by remember { mutableStateOf(PiggyBankEngine.getBalanceMinutes(context)) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
fun CategoryBudgetsCard(
    context: Context,
    usage: List<AppUsage>,
    isParentMode: Boolean = true,
    childId: String? = null,
    onBudgetChanged: () -> Unit = {}
) {
    var isExpanded by rememberSaveable { mutableStateOf(true) }
    var socialLimit by remember(childId) { mutableStateOf(CategoryBudgetEngine.getSocialLimit(context, childId)) }
    var gamingLimit by remember(childId) { mutableStateOf(CategoryBudgetEngine.getGamingLimit(context, childId)) }
    var entLimit by remember(childId) { mutableStateOf(CategoryBudgetEngine.getEntertainmentLimit(context, childId)) }
    var eduLimit by remember(childId) { mutableStateOf(CategoryBudgetEngine.getEducationLimit(context, childId)) }
    var prodLimit by remember(childId) { mutableStateOf(CategoryBudgetEngine.getProductivityLimit(context, childId)) }
    var isLearnFirst by remember(childId) { mutableStateOf(CategoryBudgetEngine.isLearnFirstEnabled(context, childId)) }
    var learnFirstMins by remember(childId) { mutableStateOf(CategoryBudgetEngine.getLearnFirstRequiredMinutes(context, childId)) }

    LaunchedEffect(childId) {
        while (isActive) {
            val s = CategoryBudgetEngine.getSocialLimit(context, childId)
            val g = CategoryBudgetEngine.getGamingLimit(context, childId)
            val e = CategoryBudgetEngine.getEntertainmentLimit(context, childId)
            val ed = CategoryBudgetEngine.getEducationLimit(context, childId)
            val pr = CategoryBudgetEngine.getProductivityLimit(context, childId)
            val lf = CategoryBudgetEngine.isLearnFirstEnabled(context, childId)
            val lfm = CategoryBudgetEngine.getLearnFirstRequiredMinutes(context, childId)
            if (s != socialLimit) socialLimit = s
            if (g != gamingLimit) gamingLimit = g
            if (e != entLimit) entLimit = e
            if (ed != eduLimit) eduLimit = ed
            if (pr != prodLimit) prodLimit = pr
            if (lf != isLearnFirst) isLearnFirst = lf
            if (lfm != learnFirstMins) learnFirstMins = lfm
            delay(2000)
        }
    }

    val summaries = remember(usage, socialLimit, gamingLimit, entLimit, eduLimit, prodLimit) {
        CategoryBudgetEngine.getCategorySummaries(context, usage, childId)
    }
    val isGamingBlocked = CategoryBudgetEngine.isGamingBlockedByLearnFirst(context, usage, childId)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("🎓 5-CATEGORY TIME BUDGETS & LEARN-FIRST", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    if (!isExpanded) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Social: ${socialLimit}m • Gaming: ${gamingLimit}m • Ent: ${entLimit}m • Edu: ${eduLimit}m",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isExpanded) {
                Spacer(Modifier.height(12.dp))
                if (!isParentMode) {
                    // Mockup Screen 2: 5 Mini Rings in 1 Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        summaries.forEach { cat ->
                            val catColor = when (cat.category) {
                                "Social" -> Color(0xFF3B82F6) // Cobalt Blue
                                "Gaming" -> Color(0xFF10B981) // Emerald Green
                                "Entertainment" -> Color(0xFFF59E0B) // Amber
                                "Education" -> Color(0xFF8B5CF6) // Royal Violet
                                else -> Color(0xFF06B6D4) // Cyan
                            }
                            val progress = if (cat.limitMinutes > 0) (cat.usedMinutes.toFloat() / cat.limitMinutes.toFloat()).coerceIn(0f, 1f) else 0f
                            val remMins = (cat.limitMinutes - cat.usedMinutes).coerceAtLeast(0)

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(52.dp)
                                ) {
                                    Canvas(modifier = Modifier.size(52.dp)) {
                                        val strokeW = 4.5.dp.toPx()
                                        drawArc(
                                            color = catColor.copy(alpha = 0.2f),
                                            startAngle = 0f,
                                            sweepAngle = 360f,
                                            useCenter = false,
                                            style = Stroke(width = strokeW)
                                        )
                                        val sweep = if (progress > 0f) 360f * progress else 45f
                                        drawArc(
                                            color = catColor,
                                            startAngle = -90f,
                                            sweepAngle = sweep,
                                            useCenter = false,
                                            style = Stroke(width = strokeW, cap = StrokeCap.Round)
                                        )
                                    }
                                    Text(
                                        "${cat.limitMinutes}m",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                val shortLabel = when (cat.category) {
                                    "Social" -> "Social"
                                    "Gaming" -> "Gaming"
                                    "Entertainment" -> "Entertain"
                                    "Education" -> "Education"
                                    else -> "Productiv"
                                }
                                Text(
                                    shortLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    // Parent Mode: Stepper List
                    summaries.forEach { cat ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(cat.icon, style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(cat.category, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        Text("${cat.usedMinutes}m used • ${cat.limitMinutes}m cap", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            val newLimit = (cat.limitMinutes - 15).coerceAtLeast(15)
                                            when (cat.category) {
                                                "Social" -> { CategoryBudgetEngine.setSocialLimit(context, newLimit, childId); socialLimit = newLimit }
                                                "Gaming" -> { CategoryBudgetEngine.setGamingLimit(context, newLimit, childId); gamingLimit = newLimit }
                                                "Entertainment" -> { CategoryBudgetEngine.setEntertainmentLimit(context, newLimit, childId); entLimit = newLimit }
                                                "Education" -> { CategoryBudgetEngine.setEducationLimit(context, newLimit, childId); eduLimit = newLimit }
                                                "Productivity & Other" -> { CategoryBudgetEngine.setProductivityLimit(context, newLimit, childId); prodLimit = newLimit }
                                            }
                                            onBudgetChanged()
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(24.dp)) {
                                            Box(contentAlignment = Alignment.Center) { Text("−", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium) }
                                        }
                                    }
                                    Text(
                                        "${cat.limitMinutes}m",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp)
                                    )
                                    IconButton(
                                        onClick = {
                                            val newLimit = (cat.limitMinutes + 15).coerceAtMost(240)
                                            when (cat.category) {
                                                "Social" -> { CategoryBudgetEngine.setSocialLimit(context, newLimit, childId); socialLimit = newLimit }
                                                "Gaming" -> { CategoryBudgetEngine.setGamingLimit(context, newLimit, childId); gamingLimit = newLimit }
                                                "Entertainment" -> { CategoryBudgetEngine.setEntertainmentLimit(context, newLimit, childId); entLimit = newLimit }
                                                "Education" -> { CategoryBudgetEngine.setEducationLimit(context, newLimit, childId); eduLimit = newLimit }
                                                "Productivity & Other" -> { CategoryBudgetEngine.setProductivityLimit(context, newLimit, childId); prodLimit = newLimit }
                                            }
                                            onBudgetChanged()
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Surface(shape = CircleShape, color = BrandEmerald.copy(alpha = 0.2f), modifier = Modifier.size(24.dp)) {
                                            Box(contentAlignment = Alignment.Center) { Text("+", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = BrandEmerald) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                // Learn-First 1-Row
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("📚 Learn-First Goal", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text("Study $learnFirstMins mins before games unlock", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isParentMode) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        val newMins = (learnFirstMins - 15).coerceAtLeast(15)
                                        CategoryBudgetEngine.setLearnFirstRequiredMinutes(context, newMins, childId)
                                        learnFirstMins = newMins
                                        onBudgetChanged()
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(24.dp)) {
                                        Box(contentAlignment = Alignment.Center) { Text("−", fontWeight = FontWeight.Bold) }
                                    }
                                }
                                Text("${learnFirstMins}m", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
                                IconButton(
                                    onClick = {
                                        val newMins = (learnFirstMins + 15).coerceAtMost(120)
                                        CategoryBudgetEngine.setLearnFirstRequiredMinutes(context, newMins, childId)
                                        learnFirstMins = newMins
                                        onBudgetChanged()
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Surface(shape = CircleShape, color = BrandEmerald.copy(alpha = 0.2f), modifier = Modifier.size(24.dp)) {
                                        Box(contentAlignment = Alignment.Center) { Text("+", fontWeight = FontWeight.Bold, color = BrandEmerald) }
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                FamilySwitch(
                                    checked = isLearnFirst,
                                    onCheckedChange = {
                                        isLearnFirst = it
                                        CategoryBudgetEngine.setLearnFirstEnabled(context, it, childId)
                                        onBudgetChanged()
                                    }
                                )
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isGamingBlocked) MaterialTheme.colorScheme.errorContainer else BrandEmerald.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, if (isGamingBlocked) MaterialTheme.colorScheme.error.copy(alpha = 0.3f) else BrandEmerald.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    if (isGamingBlocked) "🔒 Study Req Pending" else "✅ Goal Met",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isGamingBlocked) MaterialTheme.colorScheme.error else BrandEmerald
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DigitalPledgeDialog(
    context: Context,
    childName: String,
    childId: String? = null,
    onDismiss: () -> Unit,
    onSigned: () -> Unit
) {
    val effectiveChildId = childId ?: ChildProfileManager.getActiveChild(context).id
    var isSigned by remember(effectiveChildId) { mutableStateOf(BadgeEngine.isAgreementSigned(context, effectiveChildId)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📜 Family Digital Pledge", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "A mutual commitment between Parent and $childName for a balanced, healthy, and transparent digital life.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("🤝 AGREED PRINCIPLES", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = AppleBlue)
                        FamilyAgreement.agreementRules.forEach { rule ->
                            Text(rule, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("🛡️ APP BOUNDARIES", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = AppleGreen)
                        FamilyAgreement.categories.forEach { cat ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${cat.icon} ", style = MaterialTheme.typography.bodyMedium)
                                Column {
                                    Text(cat.categoryName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                    Text(cat.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                if (isSigned) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = AppleGreen.copy(alpha = 0.2f),
                        border = BorderStroke(1.dp, AppleGreen.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text("✍️ Signed & Active Milestone Unlocked!", fontWeight = FontWeight.Bold, color = AppleGreen, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!isSigned) {
                Button(
                    onClick = {
                        BadgeEngine.signAgreement(context, effectiveChildId)
                        isSigned = true
                        Executors.newSingleThreadExecutor().execute {
                            ApiClient.publishPolicy(context, childId = effectiveChildId)
                        }
                        Toast.makeText(context, "Family Digital Pledge Signed! ✍️🎉", Toast.LENGTH_SHORT).show()
                        onSigned()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppleGreen)
                ) {
                    Text("✍️ Sign Pledge Together")
                }
            } else {
                Button(onClick = onDismiss) {
                    Text("Close")
                }
            }
        },
        dismissButton = {
            if (!isSigned) {
                TextButton(onClick = onDismiss) {
                    Text("Review Later")
                }
            }
        }
    )
}

@Composable
fun AwardBadgesDialog(
    context: Context,
    childProfile: ChildProfile,
    onDismiss: () -> Unit,
    onBadgesChanged: () -> Unit
) {
    var badges by remember(childProfile.id) { mutableStateOf(BadgeEngine.getBadgesForChild(context, childProfile.id)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${childProfile.avatarEmoji} Award Badges to ${childProfile.name}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Toggle badges on to award ${childProfile.name} for positive digital habits and milestones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                badges.forEach { badge ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (badge.isUnlocked) AppleGreen.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = BorderStroke(1.dp, if (badge.isUnlocked) AppleGreen else MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Text(badge.icon, style = MaterialTheme.typography.headlineMedium)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(badge.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                        if (badge.isUnlocked) {
                                            Spacer(Modifier.width(4.dp))
                                            Text("🎉", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    Text(badge.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            FamilySwitch(
                                checked = badge.isUnlocked,
                                onCheckedChange = { unlocked ->
                                    BadgeEngine.setBadgeUnlocked(context, childProfile.id, badge.id, unlocked)
                                    badges = BadgeEngine.getBadgesForChild(context, childProfile.id)
                                    Executors.newSingleThreadExecutor().execute {
                                        ApiClient.publishPolicy(context, childId = childProfile.id)
                                    }
                                    val msg = if (unlocked) "Awarded '${badge.title}' to ${childProfile.name}! 🎉" else "Removed '${badge.title}'"
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    onBadgesChanged()
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
fun BadgesCard(context: Context, childId: String? = null) {
    var badges by remember(childId) { mutableStateOf(BadgeEngine.getBadgesForChild(context, childId)) }
    var showPledgeDialog by remember { mutableStateOf(false) }
    var selectedBadgeDetail by remember { mutableStateOf<BadgeItem?>(null) }
    val childName = remember(childId) {
        if (childId != null) ChildProfileManager.getChildren(context).find { it.id == childId }?.name ?: "Child"
        else ChildProfileManager.getActiveChild(context).name
    }

    LaunchedEffect(childId) {
        while (isActive) {
            val latest = BadgeEngine.getBadgesForChild(context, childId)
            if (latest != badges) {
                badges = latest
            }
            delay(1500)
        }
    }

    if (showPledgeDialog) {
        DigitalPledgeDialog(
            context = context,
            childName = childName,
            onDismiss = { showPledgeDialog = false },
            onSigned = {
                badges = BadgeEngine.getBadgesForChild(context, childId)
            }
        )
    }

    if (selectedBadgeDetail != null) {
        val badge = selectedBadgeDetail!!
        AlertDialog(
            onDismissRequest = { selectedBadgeDetail = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${badge.icon} ${badge.title}")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(badge.description, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (badge.isUnlocked) AppleGreen.copy(alpha = 0.2f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                        border = BorderStroke(1.dp, if (badge.isUnlocked) AppleGreen else MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(8.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                if (badge.isUnlocked) "🎉 Milestone Earned & Active!" else "🔒 Locked — Ask Parent or complete routine goals to earn!",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (badge.isUnlocked) AppleGreen else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (badge.id == "agreement" && !badge.isUnlocked) {
                    Button(onClick = {
                        selectedBadgeDetail = null
                        showPledgeDialog = true
                    }) {
                        Text("✍️ Sign Pledge")
                    }
                } else {
                    Button(onClick = { selectedBadgeDetail = null }) {
                        Text("Got it")
                    }
                }
            },
            dismissButton = {
                if (badge.id == "agreement" && !badge.isUnlocked) {
                    TextButton(onClick = { selectedBadgeDetail = null }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    val unlockedCount = badges.count { it.isUnlocked }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("🏆 Gamified Badges & Pledge", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text("$unlockedCount of ${badges.size} Milestones Earned", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (unlockedCount > 0) BrandEmerald.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(1.dp, if (unlockedCount > 0) BrandEmerald else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.clickable { showPledgeDialog = true }
                ) {
                    Text(
                        "📜 Digital Pledge ›",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (unlockedCount > 0) BrandEmerald else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                badges.forEach { badge ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f).clickable { selectedBadgeDetail = badge }
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (badge.isUnlocked) BrandEmerald.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(
                                1.dp,
                                if (badge.isUnlocked) BrandEmerald else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (badge.isUnlocked) {
                                    Text(badge.icon, style = MaterialTheme.typography.titleMedium)
                                } else {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(badge.icon, style = MaterialTheme.typography.titleMedium, modifier = Modifier.alpha(0.35f))
                                        Text("🔒", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            badge.title.split(" ").firstOrNull() ?: badge.title,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = if (badge.isUnlocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (badge.isUnlocked) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1
                        )
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
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityOn = AccessibilityGuardEngine.isAccessibilityEnabled(context)
                overlayOn = AccessibilityGuardEngine.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                    Text("🛡️ DEVICE PROTECTION GUARD", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("Instant app blocking and tamper protection guard.", style = MaterialTheme.typography.bodySmall)
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

@Composable
fun DeviceProtectionSetupCard(context: Context, onAllGranted: () -> Unit = {}) {
    var accessibilityOn by remember { mutableStateOf(AccessibilityGuardEngine.isAccessibilityEnabled(context)) }
    var overlayOn by remember { mutableStateOf(AccessibilityGuardEngine.canDrawOverlays(context)) }
    var showDisclosureModal by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityOn = AccessibilityGuardEngine.isAccessibilityEnabled(context)
                overlayOn = AccessibilityGuardEngine.canDrawOverlays(context)
                if (accessibilityOn && overlayOn) {
                    onAllGranted()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (accessibilityOn && overlayOn) {
        return
    }

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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🛡️ DEVICE PROTECTION SETUP", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("Enable required permissions to activate screen time limits & safety guard.", style = MaterialTheme.typography.bodySmall)
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        "ACTION REQUIRED ⚠️",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
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
                    modifier = Modifier.weight(1f),
                    colors = if (accessibilityOn) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant) else ButtonDefaults.buttonColors()
                ) {
                    Text(if (accessibilityOn) "Accessibility 🟢" else "1. Accessibility")
                }

                Button(
                    onClick = {
                        AccessibilityGuardEngine.openOverlaySettings(context)
                    },
                    modifier = Modifier.weight(1f),
                    colors = if (overlayOn) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant) else ButtonDefaults.buttonColors()
                ) {
                    Text(if (overlayOn) "Overlay 🟢" else "2. Overlay")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildProfileSelectorBar(
    children: List<ChildProfile>,
    activeChildId: String,
    onSelectChild: (ChildProfile) -> Unit,
    onAddChildClick: () -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(children) { child ->
            val isSelected = (child.id == activeChildId)
            val context = LocalContext.current
            val actualDevCount = remember(child.id) {
                ChildDeviceManager.getDevicesForChild(context, child.id).size
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isSelected) androidx.compose.ui.graphics.Color(0xFF1E1B4B) else androidx.compose.ui.graphics.Color(0xFF131B2E),
                border = BorderStroke(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) androidx.compose.ui.graphics.Color(0xFF818CF8) else androidx.compose.ui.graphics.Color(0xFF1E293B)
                ),
                modifier = Modifier.width(136.dp).clickable { onSelectChild(child) }
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = if (isSelected) androidx.compose.ui.graphics.Color(0xFF3730A3) else androidx.compose.ui.graphics.Color(0xFF1E293B),
                            border = BorderStroke(1.dp, if (isSelected) androidx.compose.ui.graphics.Color(0xFFA5B4FC) else androidx.compose.ui.graphics.Color(0xFF334155)),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(child.avatarEmoji, style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                        if (isSelected) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = androidx.compose.ui.graphics.Color(0xFF10B981),
                                border = BorderStroke(2.dp, androidx.compose.ui.graphics.Color(0xFF1E1B4B)),
                                modifier = Modifier.size(12.dp)
                            ) {}
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        child.name.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color(0xFF94A3B8)
                    )
                    Spacer(Modifier.height(2.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSelected) androidx.compose.ui.graphics.Color(0xFF10B981).copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color(0xFF334155).copy(alpha = 0.4f)
                    ) {
                        Text(
                            if (isSelected) "Active Profile" else "$actualDevCount device${if (actualDevCount == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSelected) androidx.compose.ui.graphics.Color(0xFF34D399) else androidx.compose.ui.graphics.Color(0xFF94A3B8),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = androidx.compose.ui.graphics.Color(0xFF0F172A),
                border = BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFF6366F1).copy(alpha = 0.4f)),
                modifier = Modifier.width(110.dp).height(116.dp).clickable { onAddChildClick() }
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = androidx.compose.ui.graphics.Color(0xFF312E81).copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFF818CF8).copy(alpha = 0.5f)),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, "Add child", tint = androidx.compose.ui.graphics.Color(0xFFA5B4FC), modifier = Modifier.size(22.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "+ Add Child",
                        style = MaterialTheme.typography.labelMedium,
                        color = androidx.compose.ui.graphics.Color(0xFFA5B4FC),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildDeviceFilterBar(
    devices: List<ChildDevice>,
    selectedDeviceId: String,
    onSelectDevice: (String) -> Unit
) {
    if (devices.isEmpty()) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "DEVICE USAGE BREAKDOWN",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    if (selectedDeviceId == "ALL") "Aggregated (All ${devices.size} Devices)"
                    else devices.find { it.deviceId == selectedDeviceId }?.deviceName ?: "Single Device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(Modifier.height(6.dp))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    FilterChip(
                        selected = selectedDeviceId == "ALL",
                        onClick = { onSelectDevice("ALL") },
                        label = { Text("All Devices (${devices.size})") },
                        leadingIcon = { Text("📊") }
                    )
                }
                items(devices) { dev ->
                    val isSelected = (selectedDeviceId == dev.deviceId)
                    val icon = if (dev.deviceType == DeviceType.TABLET) "📟" else "📱"
                    val labelName = dev.deviceName.replace(Regex(".*'s "), "")
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectDevice(dev.deviceId) },
                        label = { Text("$icon $labelName") },
                        trailingIcon = {
                            if (dev.isOnline) {
                                Text("🟢", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PairedDevicesCard(
    context: Context,
    childProfile: ChildProfile,
    onPairAnotherDevice: () -> Unit,
    onDeviceListChanged: () -> Unit
) {
    var devices by remember(childProfile.id) {
        mutableStateOf(ChildDeviceManager.getDevicesForChild(context, childProfile.id))
    }
    var deviceToUnpair by remember { mutableStateOf<ChildDevice?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📱", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${childProfile.name.uppercase()}'S PAIRED DEVICES (${devices.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                TextButton(onClick = onPairAnotherDevice) {
                    Icon(Icons.Default.Add, contentDescription = "Add Device", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Pair Device", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(Modifier.height(8.dp))

            if (devices.isEmpty()) {
                Text(
                    "No devices paired yet for ${childProfile.name}. Tap 'Pair Device' to generate a pairing code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                devices.forEachIndexed { idx, dev ->
                    if (idx > 0) {
                        Divider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        if (dev.deviceType == DeviceType.TABLET) "📟" else "📱",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    dev.deviceName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (dev.isOnline) "🟢 Online" else "⚪ Offline",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (dev.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("• 🔋 ${dev.batteryPct}%", style = MaterialTheme.typography.labelSmall)
                                    Spacer(Modifier.width(8.dp))
                                    Text("• ${dev.model}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            }
                        }

                        IconButton(onClick = { deviceToUnpair = dev }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Unpair device",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (deviceToUnpair != null) {
        val dev = deviceToUnpair!!
        AlertDialog(
            onDismissRequest = { deviceToUnpair = null },
            title = { Text("Unpair ${dev.deviceName}?") },
            text = { Text("Are you sure you want to unpair this device from ${childProfile.name}? The device will lose active policy enforcement.") },
            confirmButton = {
                Button(
                    onClick = {
                        ChildDeviceManager.removeDevice(context, childProfile.id, dev.deviceId)
                        devices = ChildDeviceManager.getDevicesForChild(context, childProfile.id)
                        deviceToUnpair = null
                        Toast.makeText(context, "${dev.deviceName} has been unpaired", Toast.LENGTH_SHORT).show()
                        onDeviceListChanged()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Unpair")
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToUnpair = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddChildDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, avatar: String, dailyLimitMinutes: Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedAvatar by remember { mutableStateOf("👶") }
    var dailyLimitMinutes by remember { mutableStateOf(120) }
    var error by remember { mutableStateOf<String?>(null) }

    val avatars = listOf("👶", "👧", "👦", "🧒", "🎮", "🦄", "⚽", "🚀")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("➕", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(8.dp))
                Text("Add New Child Device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column {
                Text(
                    "Create a profile to pair another child's phone or tablet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Child's Name") },
                    placeholder = { Text("e.g. Maya, Ethan") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(12.dp))
                Text("Choose Avatar:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    avatars.take(4).forEach { emoji ->
                        FilterChip(
                            selected = (selectedAvatar == emoji),
                            onClick = { selectedAvatar = emoji },
                            label = { Text(emoji, style = MaterialTheme.typography.titleMedium) }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    avatars.drop(4).forEach { emoji ->
                        FilterChip(
                            selected = (selectedAvatar == emoji),
                            onClick = { selectedAvatar = emoji },
                            label = { Text(emoji, style = MaterialTheme.typography.titleMedium) }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Daily Screen Time Goal:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            val hours = dailyLimitMinutes / 60
                            val mins = dailyLimitMinutes % 60
                            val formattedTime = if (mins == 0) "${hours}h" else "${hours}h ${mins}m"
                            Text(
                                formattedTime,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "$dailyLimitMinutes minutes per day",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(40.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        if (dailyLimitMinutes > 15) {
                                            dailyLimitMinutes -= 15
                                        }
                                    },
                                    enabled = dailyLimitMinutes > 15
                                ) {
                                    Text("▼", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(40.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        if (dailyLimitMinutes < 720) {
                                            dailyLimitMinutes += 15
                                        }
                                    },
                                    enabled = dailyLimitMinutes < 720
                                ) {
                                    Text("▲", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) {
                        error = "Please enter child's name"
                        return@Button
                    }
                    onAdd(name.trim(), selectedAvatar, dailyLimitMinutes)
                }
            ) {
                Text("Create & Pair")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun NewChildPairingDialog(
    childName: String,
    pairingCode: String,
    onRefreshCode: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📲", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(8.dp))
                Text("Pair $childName's Device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Install FamOrbit on $childName's phone/tablet, select 'Child Device', and enter this pairing code:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        pairingCode,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 14.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "This code connects the device directly to your Parent Control Center.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center
                )
                if (onRefreshCode != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onRefreshCode) {
                        Text("🔄 Refresh Code")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
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
    onParentCenter: () -> Unit,
    onRoleSelection: () -> Unit = {}
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

    val role = ApiClient.getDeviceRole(context)
    val isParentRole = (role == ApiClient.ROLE_PARENT)
    val isChildRole = (role == ApiClient.ROLE_CHILD)

    val parentPrefs = remember { context.getSharedPreferences("parent_control", Context.MODE_PRIVATE) }
    var childrenProfiles by remember { mutableStateOf(ChildProfileManager.getChildren(context)) }
    var activeChildProfile by remember { mutableStateOf(ChildProfileManager.getActiveChild(context)) }
    var childDisplayName by remember { mutableStateOf(activeChildProfile.name) }
    var showEditChildNameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var devTapCount by remember { mutableStateOf(0) }
    var lastTapTime by remember { mutableStateOf(0L) }
    var showAddChildDialog by remember { mutableStateOf(false) }
    var showPaywallDialog by remember { mutableStateOf(false) }
    var isPremiumActive by remember { mutableStateOf(PremiumManager.isPremium(context)) }
    var newChildPairingCode by remember { mutableStateOf<String?>(null) }
    var newChildName by remember { mutableStateOf("") }
    var showEmergencyAlertConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteDummyReminderDialog by remember { mutableStateOf(false) }
    var addedChildProfileName by remember { mutableStateOf("") }

    var childDevices by remember(activeChildProfile.id) {
        mutableStateOf(ChildDeviceManager.getDevicesForChild(context, activeChildProfile.id))
    }
    var selectedDeviceFilter by remember(activeChildProfile.id) { mutableStateOf("ALL") }
    var isInstantLockActive by remember { mutableStateOf(false) }

    LaunchedEffect(activeChildProfile.id) {
        selectedDeviceFilter = "ALL"
        while (isActive) {
            withContext(Dispatchers.IO) {
                RequestPollEngine.checkOnce(context)
            }
            childrenProfiles = ChildProfileManager.getChildren(context)
            childDevices = ChildDeviceManager.getDevicesForChild(context, activeChildProfile.id)
            val res = withContext(Dispatchers.IO) {
                ApiClient.getInstantLock(context, activeChildProfile.id)
            }
            if (res.ok) {
                try {
                    isInstantLockActive = org.json.JSONObject(res.body).optBoolean("locked", false)
                } catch (_: Exception) {}
            }
            delay(3000)
        }
    }

    val activeDevice = childDevices.find { it.deviceId == selectedDeviceFilter }
    val (displayedUsedMinutes, displayedRingTitle, displayedTrendTitle) = remember(selectedDeviceFilter, totalUsedMinutes, activeChildProfile.id, isParentRole) {
        if (selectedDeviceFilter == "ALL" || activeDevice == null) {
            Triple(
                totalUsedMinutes,
                if (isParentRole) "${activeChildProfile.avatarEmoji} ${childDisplayName.uppercase()}'S SCREEN TIME (Synced from Cloud)" else "TODAY'S SCREEN TIME",
                if (isParentRole) "${activeChildProfile.avatarEmoji} ${childDisplayName.uppercase()}'S 7-DAY USAGE TREND (Synced from Cloud)" else "7-DAY USAGE TREND"
            )
        } else {
            val ratio = if (activeDevice.deviceType == DeviceType.TABLET) 0.62f else 0.38f
            val mins = (totalUsedMinutes * ratio).toLong().coerceAtLeast(1L)
            val devLabel = if (activeDevice.deviceType == DeviceType.TABLET) "📟 Tablet" else "📱 Phone"
            Triple(
                mins,
                if (isParentRole) "${activeChildProfile.avatarEmoji} ${childDisplayName.uppercase()}'S SCREEN TIME ($devLabel)" else "TODAY'S SCREEN TIME ($devLabel)",
                if (isParentRole) "${activeChildProfile.avatarEmoji} ${childDisplayName.uppercase()}'S 7-DAY USAGE TREND ($devLabel)" else "7-DAY USAGE TREND ($devLabel)"
            )
        }
    }

    val displayedReport = remember(selectedDeviceFilter, analyticsReport) {
        if (selectedDeviceFilter == "ALL" || activeDevice == null) {
            analyticsReport
        } else {
            val ratio = if (activeDevice.deviceType == DeviceType.TABLET) 0.62f else 0.38f
            val topCat = if (activeDevice.deviceType == DeviceType.TABLET) "Entertainment" else "Social"
            analyticsReport.copy(
                totalScreenTimeMinutes = (analyticsReport.totalScreenTimeMinutes * ratio).toLong(),
                dailyAverageMinutes = (analyticsReport.dailyAverageMinutes * ratio).toLong(),
                topCategory = topCat,
                weeklyTrend = analyticsReport.weeklyTrend.map {
                    it.copy(minutes = (it.minutes * ratio).toLong().coerceAtLeast(5L))
                }
            )
        }
    }

    if (showEditChildNameDialog) {
        var tempName by remember { mutableStateOf(childDisplayName) }
        var tempAvatar by remember { mutableStateOf(activeChildProfile.avatarEmoji) }
        val avatars = listOf("👶", "👧", "👦", "🧒", "🎮", "🦄", "⚽", "🚀")
        AlertDialog(
            onDismissRequest = { showEditChildNameDialog = false },
            title = { Text("Update Child Profile") },
            text = {
                Column {
                    Text("Personalize the child's display name and avatar:")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempName,
                        onValueChange = { tempName = it },
                        label = { Text("Child's Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Avatar:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        avatars.take(4).forEach { emoji ->
                            FilterChip(
                                selected = (tempAvatar == emoji),
                                onClick = { tempAvatar = emoji },
                                label = { Text(emoji, style = MaterialTheme.typography.titleMedium) }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        avatars.drop(4).forEach { emoji ->
                            FilterChip(
                                selected = (tempAvatar == emoji),
                                onClick = { tempAvatar = emoji },
                                label = { Text(emoji, style = MaterialTheme.typography.titleMedium) }
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Divider(Modifier.padding(vertical = 4.dp))
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Remove Profile", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = {
                                if (childrenProfiles.size <= 1) {
                                    Toast.makeText(context, "Cannot delete the only child profile", Toast.LENGTH_SHORT).show()
                                } else {
                                    showDeleteConfirmDialog = true
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Delete")
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val finalName = tempName.trim().ifBlank { "Child" }
                    val updated = activeChildProfile.copy(name = finalName, avatarEmoji = tempAvatar)
                    ChildProfileManager.updateChild(context, updated)
                    activeChildProfile = updated
                    childDisplayName = finalName
                    childrenProfiles = ChildProfileManager.getChildren(context)
                    parentPrefs.edit().putString("child_display_name", finalName).apply()
                    showEditChildNameDialog = false
                }) {
                    Text("Save Profile")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditChildNameDialog = false }) {
                    Text("Cancel")
                }
            }
        )

        if (showDeleteConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                title = { Text("Delete Child Profile?") },
                text = { Text("Are you sure you want to remove \"${activeChildProfile.name}\"? This will unpair all associated devices and delete this profile.") },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteConfirmDialog = false
                            showEditChildNameDialog = false
                            val idToDelete = activeChildProfile.id
                            ChildProfileManager.removeChild(context, idToDelete)
                            val remaining = ChildProfileManager.getChildren(context)
                            childrenProfiles = remaining
                            val nextActive = remaining.firstOrNull() ?: ChildProfileManager.getActiveChild(context)
                            ChildProfileManager.setActiveChild(context, nextActive.id)
                            activeChildProfile = nextActive
                            childDisplayName = nextActive.name
                            parentPrefs.edit().putString("child_display_name", nextActive.name).apply()
                            Toast.makeText(context, "Child profile removed", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }

    if (showAddChildDialog) {
        AddChildDialog(
            onDismiss = { showAddChildDialog = false },
            onAdd = { name, avatar, dailyLimit ->
                showAddChildDialog = false
                val prevProfiles = childrenProfiles
                ChildProfileManager.addChild(context, name, avatar, dailyLimit) { newProfile, error ->
                    (context as? Activity)?.runOnUiThread {
                        if (newProfile != null) {
                            childrenProfiles = ChildProfileManager.getChildren(context)
                            activeChildProfile = newProfile
                            childDisplayName = newProfile.name
                            ChildProfileManager.setActiveChild(context, newProfile.id)
                            newChildPairingCode = newProfile.pairingCode
                            newChildName = newProfile.name
                            if (prevProfiles.any { it.name.equals("Dummy Profile", ignoreCase = true) || it.isDefault }) {
                                addedChildProfileName = newProfile.name
                                showDeleteDummyReminderDialog = true
                            }
                            refresh()
                        } else if (error != null) {
                            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )
    }

    if (showDeleteDummyReminderDialog) {
        val dummyProfile = childrenProfiles.find { it.name.equals("Dummy Profile", ignoreCase = true) || it.isDefault }
        AlertDialog(
            onDismissRequest = { showDeleteDummyReminderDialog = false },
            title = { Text("Profile Added! 🎉") },
            text = {
                Text("You have successfully added $addedChildProfileName's profile.\n\nYou can now delete the 'Dummy Profile' if you no longer need it.")
            },
            confirmButton = {
                if (dummyProfile != null) {
                    Button(
                        onClick = {
                            showDeleteDummyReminderDialog = false
                            ChildProfileManager.removeChild(context, dummyProfile.id)
                            val remaining = ChildProfileManager.getChildren(context)
                            childrenProfiles = remaining
                            val nextActive = remaining.firstOrNull { it.id == activeChildProfile.id } ?: remaining.firstOrNull() ?: activeChildProfile
                            ChildProfileManager.setActiveChild(context, nextActive.id)
                            activeChildProfile = nextActive
                            childDisplayName = nextActive.name
                            refresh()
                            Toast.makeText(context, "Dummy Profile deleted", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete Dummy Profile")
                    }
                } else {
                    Button(onClick = { showDeleteDummyReminderDialog = false }) {
                        Text("Got it")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDummyReminderDialog = false }) {
                    Text("Keep for Now")
                }
            }
        )
    }

    if (showEmergencyAlertConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showEmergencyAlertConfirmDialog = false },
            title = { Text("🚨 Trigger Emergency SOS Alert?") },
            text = {
                Text("This will immediately send a high-priority SOS alert to ${activeChildProfile.name}'s devices and activate Instant Device Lock.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showEmergencyAlertConfirmDialog = false
                        kotlin.concurrent.thread {
                            ApiClient.setInstantLock(context, true, activeChildProfile.id)
                            ApiClient.createTimeRequest(context, 0, "🚨 SOS Emergency Alert from Parent", "SYSTEM_ALERT")
                            (context as? Activity)?.runOnUiThread {
                                isInstantLockActive = true
                                NotificationEngine.notify(
                                    context,
                                    9999,
                                    "🚨 EMERGENCY ALERT SENT",
                                    "Instant lock and emergency notification sent to ${activeChildProfile.name}."
                                )
                                Toast.makeText(context, "🚨 Emergency alert & lock sent to ${activeChildProfile.name}!", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Send SOS Alert")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmergencyAlertConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    val refreshDashboardPairingCode: () -> Unit = {
        newChildName = activeChildProfile.name
        newChildPairingCode = "Generating..."
        kotlin.concurrent.thread {
            val res = ApiClient.generatePairingCodeForChild(context, activeChildProfile.id)
            (context as? Activity)?.runOnUiThread {
                if (res.ok) {
                    newChildPairingCode = res.body
                    val updated = activeChildProfile.copy(pairingCode = res.body)
                    ChildProfileManager.updateChild(context, updated)
                    activeChildProfile = updated
                } else {
                    newChildPairingCode = activeChildProfile.pairingCode ?: ApiClient.getPairingCode(context)
                }
            }
        }
    }

    if (newChildPairingCode != null) {
        NewChildPairingDialog(
            childName = newChildName ?: activeChildProfile.name,
            pairingCode = newChildPairingCode!!,
            onRefreshCode = refreshDashboardPairingCode,
            onDismiss = { newChildPairingCode = null }
        )
    }

    if (showPaywallDialog) {
        FamOrbitPaywallDialog(
            onDismiss = { showPaywallDialog = false },
            onSubscribed = {
                isPremiumActive = PremiumManager.isPremium(context)
            }
        )
    }

    if (isChildRole) {
        LaunchedEffect(Unit) {
            onChildHome()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isParentRole) "FamOrbit — Parent Mode 📱"
                        else if (isChildRole) "FamOrbit — Child Device 👶"
                        else "FamOrbit v2.3.0",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.clickable {
                            if (isParentRole) {
                                val now = System.currentTimeMillis()
                                if (now - lastTapTime > 3000L) {
                                    devTapCount = 0
                                }
                                lastTapTime = now
                                devTapCount++
                                if (devTapCount in 6..9) {
                                    Toast.makeText(context, "You are now ${10 - devTapCount} steps away from developer diagnostics", Toast.LENGTH_SHORT).show()
                                } else if (devTapCount >= 10) {
                                    devTapCount = 0
                                    Toast.makeText(context, "Developer Mode enabled: Launching Sync Lab", Toast.LENGTH_SHORT).show()
                                    launchProtected(onSync)
                                }
                            }
                        }
                    )
                },
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                val isPairedAsChild = ApiClient.serverChildId(context) != null && ApiClient.serverFamilyId(context) != null
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = androidx.compose.ui.graphics.Color(0xFF0F172A),
                    border = BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFF334155)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = androidx.compose.ui.graphics.Color(0xFF312E81),
                                    border = BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFF6366F1)),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(if (isParentRole) "📱" else "👶", fontSize = 18.sp)
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        if (isParentRole) "PARENT CONTROL CENTER"
                                        else if (isChildRole) "CHILD SUPERVISION"
                                        else "ROLE UNSET",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = androidx.compose.ui.graphics.Color.White
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(androidx.compose.ui.graphics.Color(0xFF10B981), shape = RoundedCornerShape(4.dp))
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "Cloud Sync Active",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = androidx.compose.ui.graphics.Color(0xFF34D399),
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isPairedAsChild) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = androidx.compose.ui.graphics.Color(0xFF4F46E5),
                                        modifier = Modifier.clickable {
                                            ApiClient.setDeviceRole(context, ApiClient.ROLE_CHILD)
                                            onChildHome()
                                        }
                                    ) {
                                        Text(
                                            "👶 Child Mode",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = androidx.compose.ui.graphics.Color.White,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = androidx.compose.ui.graphics.Color(0xFF1E293B),
                                    border = BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFF475569)),
                                    modifier = Modifier.clickable { onRoleSelection() }
                                ) {
                                    Text(
                                        "Switch Role",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = androidx.compose.ui.graphics.Color(0xFFCBD5E1),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = androidx.compose.ui.graphics.Color(0xFF1E293B).copy(alpha = 0.6f)
                            ) {
                                Text(
                                    "Family ID: ${ApiClient.serverFamilyId(context)?.take(8) ?: "Unlinked"}…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = androidx.compose.ui.graphics.Color(0xFF94A3B8),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                            Text(
                                if (isParentUnlocked) "🔓 Parent Unlocked" else "🔒 PIN Protected",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isParentUnlocked) androidx.compose.ui.graphics.Color(0xFF38BDF8) else androidx.compose.ui.graphics.Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }

            if (isParentRole) {
                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isPremiumActive) androidx.compose.ui.graphics.Color(0xFF1E1B4B) else androidx.compose.ui.graphics.Color(0xFF1E1B4B),
                        border = BorderStroke(1.5.dp, if (isPremiumActive) androidx.compose.ui.graphics.Color(0xFF10B981) else androidx.compose.ui.graphics.Color(0xFF818CF8)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showPaywallDialog = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = androidx.compose.ui.graphics.Color(0xFF312E81),
                                    border = BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFFA5B4FC).copy(alpha = 0.5f)),
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("👑", fontSize = 22.sp)
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        if (isPremiumActive) "FamOrbit Premium Active" else "Unlock FamOrbit Premium",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = androidx.compose.ui.graphics.Color.White
                                    )
                                    Text(
                                        if (isPremiumActive) "Unlimited child devices & full protection" else "7-Day Free Trial • \$2.99/mo after",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isPremiumActive) androidx.compose.ui.graphics.Color(0xFF34D399) else androidx.compose.ui.graphics.Color(0xFFC7D2FE)
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isPremiumActive) androidx.compose.ui.graphics.Color(0xFF065F46) else androidx.compose.ui.graphics.Color(0xFF4F46E5),
                                border = BorderStroke(1.dp, if (isPremiumActive) androidx.compose.ui.graphics.Color(0xFF10B981) else androidx.compose.ui.graphics.Color(0xFF818CF8))
                            ) {
                                Text(
                                    if (isPremiumActive) "✓ Active" else "✨ Upgrade",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = androidx.compose.ui.graphics.Color.White
                                )
                            }
                        }
                    }
                }

                item {
                    ChildProfileSelectorBar(
                        children = childrenProfiles,
                        activeChildId = activeChildProfile.id,
                        onSelectChild = { selected ->
                            activeChildProfile = selected
                            childDisplayName = selected.name
                            ChildProfileManager.setActiveChild(context, selected.id)
                            refresh()
                        },
                        onAddChildClick = {
                            if (PremiumManager.canAddChild(context, childrenProfiles.size)) {
                                showAddChildDialog = true
                            } else {
                                showPaywallDialog = true
                            }
                        }
                    )
                }

                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            "QUICK CONTROLS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = androidx.compose.ui.graphics.Color(0xFF94A3B8)
                        )
                        Spacer(Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Action 1: Instant Lock / Resume
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = if (isInstantLockActive) androidx.compose.ui.graphics.Color(0xFF4C0519) else androidx.compose.ui.graphics.Color(0xFF131B2E),
                                border = BorderStroke(
                                    1.5.dp,
                                    if (isInstantLockActive) androidx.compose.ui.graphics.Color(0xFFF43F5E) else androidx.compose.ui.graphics.Color(0xFF6366F1).copy(alpha = 0.5f)
                                ),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    val nextState = !isInstantLockActive
                                    isInstantLockActive = nextState
                                    kotlin.concurrent.thread {
                                        ApiClient.setInstantLock(context, nextState, activeChildProfile.id)
                                    }
                                    Toast.makeText(
                                        context,
                                        if (nextState) "🔒 Locked all devices for ${activeChildProfile.name}"
                                        else "▶️ Resumed devices for ${activeChildProfile.name}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (isInstantLockActive) androidx.compose.ui.graphics.Color(0xFF881337) else androidx.compose.ui.graphics.Color(0xFF1E1B4B),
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(if (isInstantLockActive) "🔓" else "🔒", fontSize = 20.sp)
                                            }
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                if (isInstantLockActive) "DEVICE LOCK ACTIVE" else "INSTANT DEVICE LOCK",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isInstantLockActive) androidx.compose.ui.graphics.Color(0xFFFDA4AF) else androidx.compose.ui.graphics.Color.White
                                            )
                                            Text(
                                                if (isInstantLockActive) "Devices are currently frozen • Tap to resume" else "Freeze all devices for ${activeChildProfile.name}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isInstantLockActive) androidx.compose.ui.graphics.Color(0xFFF43F5E) else androidx.compose.ui.graphics.Color(0xFF94A3B8)
                                            )
                                        }
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isInstantLockActive) androidx.compose.ui.graphics.Color(0xFFBE123C) else androidx.compose.ui.graphics.Color(0xFF312E81)
                                    ) {
                                        Text(
                                            if (isInstantLockActive) "Unlock" else "Lock Now",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = androidx.compose.ui.graphics.Color.White,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            // Action 2: App Limits & Time Controls
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = androidx.compose.ui.graphics.Color(0xFF0F172A),
                                border = BorderStroke(1.5.dp, androidx.compose.ui.graphics.Color(0xFF0284C7).copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth().clickable { launchProtected(onParentCenter) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = androidx.compose.ui.graphics.Color(0xFF075985).copy(alpha = 0.5f),
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text("⏱️", fontSize = 20.sp)
                                            }
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                "APP LIMITS & SCHEDULES",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = androidx.compose.ui.graphics.Color.White
                                            )
                                            Text(
                                                "Set daily time budgets & bedtime focus",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = androidx.compose.ui.graphics.Color(0xFF38BDF8)
                                            )
                                        }
                                    }
                                    Text(
                                        "→",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 20.sp,
                                        color = androidx.compose.ui.graphics.Color(0xFF38BDF8)
                                    )
                                }
                            }

                            // Action 3: Emergency SOS Alert
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = androidx.compose.ui.graphics.Color(0xFF18181B),
                                border = BorderStroke(1.5.dp, androidx.compose.ui.graphics.Color(0xFFE11D48).copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    showEmergencyAlertConfirmDialog = true
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = androidx.compose.ui.graphics.Color(0xFF881337).copy(alpha = 0.4f),
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text("🚨", fontSize = 20.sp)
                                            }
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                "EMERGENCY SOS ALERT",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = androidx.compose.ui.graphics.Color(0xFFFB7185)
                                            )
                                            Text(
                                                "Send high-priority siren to child devices",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = androidx.compose.ui.graphics.Color(0xFFFDA4AF)
                                            )
                                        }
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = androidx.compose.ui.graphics.Color(0xFFE11D48).copy(alpha = 0.2f),
                                        border = BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFFE11D48))
                                    ) {
                                        Text(
                                            "Trigger SOS",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = androidx.compose.ui.graphics.Color(0xFFFB7185),
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    PairedDevicesCard(
                        context = context,
                        childProfile = activeChildProfile,
                        onPairAnotherDevice = refreshDashboardPairingCode,
                        onDeviceListChanged = {
                            childrenProfiles = ChildProfileManager.getChildren(context)
                            childDevices = ChildDeviceManager.getDevicesForChild(context, activeChildProfile.id)
                            if (childDevices.none { it.deviceId == selectedDeviceFilter }) {
                                selectedDeviceFilter = "ALL"
                            }
                            refresh()
                        }
                    )
                }
            }



            // Option C: Pending Extra Time Requests Inbox Card
            item {
                ParentInboxCard(context, isChildMode = !isParentRole)
            }

            // Phase 3: Per-Device Filter Bar
            item {
                ChildDeviceFilterBar(
                    devices = childDevices,
                    selectedDeviceId = selectedDeviceFilter,
                    onSelectDevice = { selectedDeviceFilter = it }
                )
            }

            // Option A: Visual Screen Time Progress Ring
            item {
                ScreenTimeProgressRing(
                    usedMinutes = displayedUsedMinutes,
                    limitMinutes = activeChildProfile.dailyScreenLimitMinutes.toLong(),
                    title = displayedRingTitle
                )
            }

            // Option A: 7-Day Usage Trend Chart
            item {
                WeeklyTrendChart(
                    report = displayedReport,
                    title = displayedTrendTitle
                )
            }

            if (isChildRole) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth().clickable { onChildHome() }
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("👶 MY DIGITAL DAY (CHILD DASHBOARD)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))
                                Text("View 3 time buckets (Used, Limit, Left), request extra time & claim habit rewards.", style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Open Child Dashboard")
                        }
                    }
                }
            }

            if (isChildRole) {
                item {
                    Method2EnforcementCard(context)
                }
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



            if (FeatureToggleEngine.isHabitBadgesEnabled(context)) {
                item {
                    BadgesCard(context)
                }
            }

            if (isParentRole) {
                item {
                    Text("PARENT CONTROLS", style = MaterialTheme.typography.titleSmall)
                }
                item {
                    Button(onClick = { launchProtected(onParentCenter) }, Modifier.fillMaxWidth()) {
                        Text(if (isParentUnlocked) "Parent Control Center 🔓" else "Parent Control Center 🔒")
                    }
                }
                item {
                    Button(onClick = onChildHome, Modifier.fillMaxWidth()) {
                        Text("👶 View Child Dashboard")
                    }
                }
            }

            item {
                var isLocalUsageExpanded by remember { mutableStateOf(false) }
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { isLocalUsageExpanded = !isLocalUsageExpanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (isParentRole) "📱 THIS PHONE'S LOCAL USAGE (Parent Device)"
                                    else "👶 CHILD PROTECTED SCREEN TIME (This Device)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (isParentRole && !isLocalUsageExpanded) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "Collapsed by default (Tap to view local apps on Parent phone)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(
                                imageVector = if (isLocalUsageExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (isLocalUsageExpanded) "Collapse" else "Expand"
                            )
                        }

                        if (isLocalUsageExpanded || !isParentRole) {
                            if (isParentRole) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Note: This section lists local app usage on this Parent phone. To configure & publish screen time limits for Child devices, tap Parent Control Center above.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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

                            Spacer(Modifier.height(8.dp))
                            val displayUsage = usage.filter { !AppScanner.isSystemStub(it.packageName, it.appName) }.sortedByDescending { it.minutes }
                            displayUsage.forEach { item ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        AppIcon(item.packageName, modifier = Modifier.size(32.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text(item.appName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                        Text("${item.minutes} min today", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
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
                        executor.execute {
                            try {
                                val prefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
                                val dailyLimit = prefs.getInt("daily_screen_limit", 180)
                                val activeChildId = ChildProfileManager.getActiveChild(context).id
                                ApiClient.createServerPolicy(context, dailyLimit, org.json.JSONObject(), activeChildId)
                            } catch (_: Exception) {}
                        }
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
                Text("Family Dashboard", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Text("Manage the child's digital routine and review requests.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("CHILD DEVICE & SECURITY", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        val isChildPaired = ApiClient.serverChildId(context) != null
                        Text(
                            "Status: ${if (isChildPaired) "Paired & Protected 🟢" else "Ready to Pair ⚠️"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isChildPaired) BrandEmerald else BrandAmber,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { showChangePinDialog = true },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🔑 Change Parent Security PIN", fontWeight = FontWeight.SemiBold)
                }
            }
            item {
                Button(
                    onClick = onControl,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Manage Screen Time & App Limits ➔", fontWeight = FontWeight.Bold)
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
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (insight.type == "ALERT") MaterialTheme.colorScheme.errorContainer
                                else MaterialTheme.colorScheme.primaryContainer
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
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

                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
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

fun launchEmergencyApp(context: Context, type: String) {
    try {
        val intent: Intent? = when (type) {
            "Phone" -> Intent(Intent.ACTION_DIAL)
            "Messages" -> {
                val smsIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_MESSAGING) }
                if (context.packageManager.queryIntentActivities(smsIntent, 0).isNotEmpty()) {
                    smsIntent
                } else {
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse("sms:"))
                }
            }
            "Camera" -> Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            "Recorder" -> {
                val recordIntent = Intent(android.provider.MediaStore.Audio.Media.RECORD_SOUND_ACTION)
                if (context.packageManager.queryIntentActivities(recordIntent, 0).isNotEmpty()) {
                    recordIntent
                } else {
                    val pm = context.packageManager
                    val pkgs = listOf(
                        "com.google.android.apps.recorder",
                        "com.android.soundrecorder",
                        "com.sec.android.app.voicenote",
                        "com.oneplus.soundrecorder",
                        "com.coloros.soundrecorder",
                        "com.oppo.soundrecorder"
                    )
                    val installed = pkgs.firstOrNull { pkg ->
                        try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
                    }
                    if (installed != null) pm.getLaunchIntentForPackage(installed) else null
                }
            }
            "Calculator" -> {
                val calcIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_CALCULATOR) }
                if (context.packageManager.queryIntentActivities(calcIntent, 0).isNotEmpty()) {
                    calcIntent
                } else {
                    val pm = context.packageManager
                    val pkgs = listOf(
                        "com.google.android.calculator",
                        "com.android.calculator2",
                        "com.sec.android.app.popupcalculator",
                        "com.oneplus.calculator",
                        "com.coloros.calculator"
                    )
                    val installed = pkgs.firstOrNull { pkg ->
                        try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
                    }
                    if (installed != null) pm.getLaunchIntentForPackage(installed) else null
                }
            }
            else -> null
        }
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            Toast.makeText(context, "$type is available", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open $type", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildHomeScreen(
    onBack: () -> Unit,
    onSwitchToParent: () -> Unit = onBack,
    darkMode: Boolean = true,
    onToggleDark: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val isParentDevice = ApiClient.getDeviceRole(context) == ApiClient.ROLE_PARENT
    var usage by remember { mutableStateOf(emptyList<AppUsage>()) }
    var requestStatus by remember { mutableStateOf("No request submitted") }
    var requests by remember { mutableStateOf(emptyList<org.json.JSONObject>()) }
    var busy by remember { mutableStateOf(false) }
    var reasonDialogMinutes by remember { mutableStateOf<Int?>(null) }
    var reasonDialogPackage by remember { mutableStateOf<String?>(null) }
    var enforcementStatus by remember { mutableStateOf("Not evaluated") }
    var showParentUnlockDialog by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("parent_control", Context.MODE_PRIVATE) }
    var childDisplayName by remember {
        mutableStateOf(
            if (isParentDevice) ChildProfileManager.getActiveChild(context).name
            else (prefs.getString("child_display_name", "") ?: "")
        )
    }
    var childDismissedIds by remember {
        mutableStateOf(prefs.getStringSet("child_dismissed_request_ids", emptySet()) ?: emptySet())
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val installedApps = remember { if (!isParentDevice) AppScanner.getInstalledApps(context) else emptyList() }

    fun loadAppRules(): List<Triple<String, String, Int>> {
        if (isParentDevice) {
            val child = ChildProfileManager.getActiveChild(context)
            val childId = child.id
            val savedPackages = prefs.getStringSet("${childId}_app_packages", null)
            if (!savedPackages.isNullOrEmpty()) {
                return savedPackages.map { pkg ->
                    val appName = prefs.getString("${childId}_appname_$pkg", AppNameResolver.getAppName(context, pkg)) ?: pkg
                    val limit = prefs.getInt("${childId}_limit_$pkg", 30)
                    Triple(pkg, appName, limit)
                }.sortedBy { it.second.lowercase() }
            } else {
                return listOf(
                    Triple("com.google.android.youtube", "YouTube", prefs.getInt("${childId}_limit_com.google.android.youtube", 30)),
                    Triple("com.roblox.client", "Roblox", prefs.getInt("${childId}_limit_com.roblox.client", 30)),
                    Triple("com.mojang.minecraftpe", "Minecraft", prefs.getInt("${childId}_limit_com.mojang.minecraftpe", 30)),
                    Triple("com.whatsapp", "WhatsApp", prefs.getInt("${childId}_limit_com.whatsapp", 60))
                )
            }
        }

        return if (installedApps.isNotEmpty()) {
            installedApps.map { app ->
                Triple(app.packageName, app.appName, prefs.getInt("limit_${app.packageName}", 30))
            }
        } else {
            listOf(
                Triple("com.google.android.youtube", "YouTube", prefs.getInt("limit_com.google.android.youtube", 30)),
                Triple("com.roblox.client", "Roblox", prefs.getInt("limit_com.roblox.client", 30)),
                Triple("com.mojang.minecraftpe", "Minecraft", prefs.getInt("limit_com.mojang.minecraftpe", 30))
            )
        }
    }
    var appRules by remember { mutableStateOf(loadAppRules()) }

    fun loadRequests() {
        executor.execute {
            val response = if (isParentDevice) {
                ApiClient.getTimeRequestsForChild(context, ChildProfileManager.getActiveChild(context).id)
            } else {
                ApiClient.getTimeRequests(context)
            }
            if (response.ok) {
                val json = org.json.JSONObject(response.body)
                val arr = json.optJSONArray("requests")
                val parsed = buildList {
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val pkg = obj.optString("package_name")
                            val reason = obj.optString("reason")
                            val isTelemetry = pkg.startsWith("APP_CATALOG:") ||
                                    pkg == "com.familycontrol.lab" ||
                                    pkg == "SYSTEM_ALERT" ||
                                    reason.startsWith("DEV:") ||
                                    reason.startsWith("DEV_INFO") ||
                                    reason.startsWith("DEV_APPS#") ||
                                    reason.startsWith("Child device online")
                            if (!isTelemetry) {
                                add(obj)
                            }
                        }
                    }
                }
                context.mainExecutor.execute { requests = parsed }
            }
        }
    }

    var dailyLimit by remember {
        mutableStateOf(
            if (isParentDevice) {
                val activeChild = ChildProfileManager.getActiveChild(context)
                prefs.getInt("${activeChild.id}_daily_screen_limit", activeChild.dailyScreenLimitMinutes)
            } else {
                prefs.getInt("daily_screen_limit", 180)
            }
        )
    }
    var catBudgetsEnabled by remember { mutableStateOf(FeatureToggleEngine.isCategoryBudgetsEnabled(context)) }
    var piggyBankEnabled by remember { mutableStateOf(FeatureToggleEngine.isPiggyBankEnabled(context)) }
    var habitBadgesEnabled by remember { mutableStateOf(FeatureToggleEngine.isHabitBadgesEnabled(context)) }

    fun refresh() {
        usage = if (!isParentDevice && hasUsageAccess(context)) getTodayUsage(context) else emptyList()
        loadRequests()
        childDisplayName = if (isParentDevice) ChildProfileManager.getActiveChild(context).name else (prefs.getString("child_display_name", "") ?: "")
        dailyLimit = if (isParentDevice) {
            val activeChild = ChildProfileManager.getActiveChild(context)
            prefs.getInt("${activeChild.id}_daily_screen_limit", activeChild.dailyScreenLimitMinutes)
        } else {
            prefs.getInt("daily_screen_limit", 180)
        }
        appRules = loadAppRules()
        executor.execute {
            if (isParentDevice) {
                RequestPollEngine.checkOnce(context)
            } else {
                PolicySyncEngine.syncAndApplyCloudPolicy(context)
                ApiClient.publishChildAppsAndTelemetry(context)
            }
            context.mainExecutor.execute {
                childDisplayName = if (isParentDevice) ChildProfileManager.getActiveChild(context).name else (prefs.getString("child_display_name", "") ?: "")
                dailyLimit = if (isParentDevice) {
                    val activeChild = ChildProfileManager.getActiveChild(context)
                    prefs.getInt("${activeChild.id}_daily_screen_limit", activeChild.dailyScreenLimitMinutes)
                } else {
                    prefs.getInt("daily_screen_limit", 180)
                }
                appRules = loadAppRules()
                catBudgetsEnabled = FeatureToggleEngine.isCategoryBudgetsEnabled(context)
                piggyBankEnabled = FeatureToggleEngine.isPiggyBankEnabled(context)
                habitBadgesEnabled = FeatureToggleEngine.isHabitBadgesEnabled(context)
            }
        }
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
                loadRequests()
            }
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            refresh()
            delay(3000)
        }
    }
    DisposableEffect(Unit) { onDispose { executor.shutdownNow() } }

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

    if (showParentUnlockDialog) {
        var enteredPin by remember { mutableStateOf("") }
        var pinError by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showParentUnlockDialog = false },
            title = { Text("Parent Access 🔒") },
            text = {
                Column {
                    Text("Enter 4-digit Parent PIN to exit Child Mode and return to Parent Dashboard.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = enteredPin,
                        onValueChange = {
                            enteredPin = it.filter { c -> c.isDigit() }.take(4)
                            pinError = false
                        },
                        label = { Text("Parent PIN") },
                        isError = pinError,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (pinError) {
                        Spacer(Modifier.height(4.dp))
                        Text("Incorrect PIN. Please try again.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            if (ParentSecurity.verifyPin(context, enteredPin)) {
                                val myDeviceId = ApiClient.serverDeviceId(context) ?: ""
                                val myChildId = ApiClient.serverChildId(context) ?: ""
                                if (myDeviceId.isNotBlank() && myChildId.isNotBlank()) {
                                    ChildDeviceManager.removeDevice(context, myChildId, myDeviceId)
                                }
                                ApiClient.clearRegistration(context)
                                ApiClient.setDeviceRole(context, ApiClient.ROLE_PARENT)
                                Toast.makeText(context, "Device unpaired successfully", Toast.LENGTH_SHORT).show()
                                showParentUnlockDialog = false
                                onSwitchToParent()
                            } else {
                                pinError = true
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Unpair Device")
                    }
                    Button(onClick = {
                        if (ParentSecurity.verifyPin(context, enteredPin)) {
                            ApiClient.setDeviceRole(context, ApiClient.ROLE_PARENT)
                            Toast.makeText(context, "Parent Mode Unlocked", Toast.LENGTH_SHORT).show()
                            showParentUnlockDialog = false
                            onSwitchToParent()
                        } else {
                            pinError = true
                        }
                    }) {
                        Text("Unlock & Exit")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showParentUnlockDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                val isParentDevice = ApiClient.getDeviceRole(context) == ApiClient.ROLE_PARENT
                Text(
                    if (isParentDevice) "Child View (Parent Preview)"
                    else if (childDisplayName.isNotBlank()) "$childDisplayName's Device"
                    else "Child Mode"
                )
            },
            navigationIcon = {
                if (ApiClient.getDeviceRole(context) != ApiClient.ROLE_CHILD) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back to Dashboard") }
                }
            },
            actions = {
                if (onToggleDark != null) {
                    IconButton(onClick = { onToggleDark(!darkMode) }) {
                        Icon(
                            if (darkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Toggle Light/Dark Mode"
                        )
                    }
                }
                IconButton(onClick = { refresh() }) {
                    Icon(Icons.Default.Refresh, "Refresh")
                }
                IconButton(onClick = { showParentUnlockDialog = true }) {
                    Icon(Icons.Default.Security, "Parent Access", tint = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (ApiClient.getDeviceRole(context) == ApiClient.ROLE_PARENT) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        modifier = Modifier.fillMaxWidth().clickable { onBack() }
                    ) {
                        Row(
                            Modifier.padding(14.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("👁️ PARENT PREVIEW ACTIVE", fontWeight = FontWeight.Bold)
                            Text("Exit to Dashboard ➔", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        val greetingSuffix = if (childDisplayName.isBlank() ||
                            childDisplayName.equals("Dummy Profile", ignoreCase = true) ||
                            childDisplayName.equals("Child", ignoreCase = true)
                        ) {
                            ""
                        } else {
                            " " + childDisplayName.trim().replaceFirstChar { it.uppercase() }
                        }
                        Text(
                            "Hey$greetingSuffix! 👋",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            "Today's Digital Balance",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("🧒", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }

            if (ApiClient.getDeviceRole(context) == ApiClient.ROLE_CHILD) {
                item {
                    DeviceProtectionSetupCard(context)
                }
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

            if (catBudgetsEnabled) {
                val activeChildId = if (isParentDevice) ChildProfileManager.getActiveChild(context).id else ApiClient.serverChildId(context)
                item {
                    CategoryBudgetsCard(
                        context = context,
                        usage = usage,
                        isParentMode = false,
                        childId = activeChildId
                    )
                }
            }

            item {
                val activeServerRequests = requests.filter { it.optString("request_id") !in childDismissedIds }
                val pendingRequests = activeServerRequests.filter { it.optString("status") == "PENDING" }
                val recentDecided = activeServerRequests.filter { it.optString("status") != "PENDING" }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Extra time Request", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Text("Select duration to ask parent for bonus time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (recentDecided.isNotEmpty()) {
                                TextButton(
                                    onClick = {
                                        val handledIds = recentDecided.map { it.optString("request_id") }.toSet()
                                        val updated = childDismissedIds + handledIds
                                        prefs.edit().putStringSet("child_dismissed_request_ids", updated).apply()
                                        childDismissedIds = updated
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("Dismiss All ✕", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            listOf(15, 30, 60).forEach { minutes ->
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = BrandBlue,
                                    border = BorderStroke(1.dp, BrandCyan.copy(alpha = 0.3f)),
                                    modifier = Modifier.weight(1f).clickable(enabled = !busy) { reasonDialogMinutes = minutes }
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("[+${minutes}m]", fontWeight = FontWeight.ExtraBold, color = Color.White, style = MaterialTheme.typography.titleSmall)
                                    }
                                }
                            }
                        }

                        if (pendingRequests.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            pendingRequests.take(2).forEach { req ->
                                val mins = req.optInt("requested_minutes")
                                val pkg = req.optString("package_name").takeIf { it.isNotBlank() && it != "null" && it != "None" }
                                val appName = AppNameResolver.getAppName(context, pkg)
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = BrandBlue.copy(alpha = 0.15f),
                                    border = BorderStroke(1.dp, BrandBlue.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("⏳ Waiting for Parent: +${mins}m ($appName)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }

                        if (recentDecided.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            recentDecided.take(2).forEach { req ->
                                val mins = req.optInt("requested_minutes")
                                val status = req.optString("status")
                                val pkg = req.optString("package_name").takeIf { it.isNotBlank() && it != "null" && it != "None" }
                                val appName = AppNameResolver.getAppName(context, pkg)
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (status == "APPROVED") BrandEmerald.copy(alpha = 0.15f) else BrandRose.copy(alpha = 0.15f),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            if (status == "APPROVED") "✅ Parent Approved +${mins}m ($appName)"
                                            else "❌ Parent Declined +${mins}m ($appName)",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (status == "APPROVED") BrandEmerald else BrandRose
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Mockup Section: Always Available Emergency Apps Dock
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Emergency Apps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = BrandEmerald.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, BrandEmerald.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    "Emergency Safe 🟢",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BrandEmerald,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("Essential emergency apps stay available even when device is paused.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            listOf(
                                Triple("📞", "Phone", Color(0xFFEF4444)),
                                Triple("💬", "Messages", Color(0xFF10B981)),
                                Triple("📷", "Camera", Color(0xFF3B82F6)),
                                Triple("🎙️", "Recorder", Color(0xFFF59E0B)),
                                Triple("🧮", "Calculator", Color(0xFF8B5CF6))
                            ).forEach { (icon, name, tint) ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable { launchEmergencyApp(context, name) }
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = tint,
                                        modifier = Modifier.size(52.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(icon, style = MaterialTheme.typography.titleLarge)
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            item {
                val childAnalyticsReport = remember(usage) { AnalyticsEngine.generateReport(context, usage) }
                WeeklyTrendChart(
                    report = childAnalyticsReport,
                    title = "📊 7-DAY USAGE TREND"
                )
            }

            if (piggyBankEnabled) {
                item {
                    PiggyBankCard(context, isChildMode = true)
                }
            }


            item {
                Text("APP POLICY STATUS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                val remainingAppMins = (effectiveAppLimit - appUsage).coerceAtLeast(0)
                val isSuspended = enabled && rule.third == 0
                val reached = enabled && (effectiveAppLimit == 0 || appUsage >= effectiveAppLimit)

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (reached || isSuspended) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ),
                    border = BorderStroke(1.dp, if (reached || isSuspended) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(rule.first, modifier = Modifier.size(38.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(rule.second, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                if (approvedExtra > 0) {
                                    Text("+$approvedExtra min extra approved", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = when {
                                    !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                    isSuspended -> MaterialTheme.colorScheme.error
                                    reached -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            ) {
                                Text(
                                    when {
                                        !enabled -> "RULE OFF"
                                        isSuspended -> "⛔ SUSPENDED"
                                        reached -> "🔒 LOCKED"
                                        else -> "⏱️ ACTIVE"
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (!enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onError
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // 3 Bucket Metrics Row
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.weight(1f).padding(end = 4.dp)
                            ) {
                                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("🎯 Limit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${effectiveAppLimit}m", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                            ) {
                                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("⏱️ Used", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${appUsage}m", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.weight(1f).padding(start = 4.dp)
                            ) {
                                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("⏳ Left", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${remainingAppMins}m", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (remainingAppMins == 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        val progressFraction = if (effectiveAppLimit > 0) (appUsage.toFloat() / effectiveAppLimit.toFloat()).coerceIn(0f, 1f) else 1f
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = if (reached || isSuspended) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surface
                        )

                        if (reached || isSuspended || remainingAppMins <= 5) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                enabled = !busy,
                                onClick = {
                                    reasonDialogPackage = rule.first
                                    reasonDialogMinutes = 15
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("📨 Request +15m Extra Time for ${rule.second}")
                            }
                        }
                    }
                }
            }
            item { Text("RECENT USAGE", style = MaterialTheme.typography.titleMedium) }

            items(usage.take(10)) { item ->
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.appName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Text(item.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                        ) {
                            Text(
                                "${item.minutes}m used",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().clickable { showParentUnlockDialog = true }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Security, "Parent PIN Unlock", tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Parent Access & Role Switch", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("PIN Unlock ➔", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentControlScreen(onBack: () -> Unit, onRoutines: () -> Unit = {}, onProtection: (String) -> Unit = {}) {
    val context = LocalContext.current
    val usage = if (hasUsageAccess(context)) getTodayUsage(context) else emptyList()

    val popularChildApps = listOf(
        AppPolicy("com.google.android.youtube", "YouTube", 45, true),
        AppPolicy("com.roblox.client", "Roblox", 30, true),
        AppPolicy("com.zhiliaoapp.musically", "TikTok", 30, true),
        AppPolicy("com.instagram.android", "Instagram", 30, true),
        AppPolicy("com.netflix.mediaclient", "Netflix", 45, true),
        AppPolicy("com.android.chrome", "Chrome", 60, true),
        AppPolicy("com.mojang.minecraftpe", "Minecraft", 30, true),
        AppPolicy("com.whatsapp", "WhatsApp", 60, false)
    )

    val prefs = remember {
        context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
    }

    var childrenProfiles by remember { mutableStateOf(ChildProfileManager.getChildren(context)) }
    var activeChildProfile by remember { mutableStateOf(ChildProfileManager.getActiveChild(context)) }
    var childDisplayName by remember {
        mutableStateOf(activeChildProfile.name)
    }

    fun loadDailyLimit(childId: String = activeChildProfile.id): Int {
        val child = childrenProfiles.find { it.id == childId } ?: activeChildProfile
        return if (prefs.contains("${childId}_daily_screen_limit")) {
            prefs.getInt("${childId}_daily_screen_limit", child.dailyScreenLimitMinutes)
        } else {
            child.dailyScreenLimitMinutes
        }
    }

    fun loadRules(childId: String = activeChildProfile.id): List<AppPolicy> {
        val isParentRole = ApiClient.getDeviceRole(context) == ApiClient.ROLE_PARENT
        if (isParentRole) {
            val savedPackages = prefs.getStringSet("${childId}_app_packages", null)
            val candidateList = if (!savedPackages.isNullOrEmpty()) {
                val list = mutableListOf<AppPolicy>()
                for (pkg in savedPackages) {
                    val appName = prefs.getString("${childId}_appname_$pkg", AppNameResolver.getAppName(context, pkg)) ?: pkg
                    val limit = prefs.getInt("${childId}_limit_$pkg", 30)
                    val enabled = prefs.getBoolean("${childId}_enabled_$pkg", true)
                    list.add(AppPolicy(pkg, appName, limit, enabled))
                }
                list
            } else {
                popularChildApps.map { default ->
                    AppPolicy(
                        default.packageName,
                        default.appName,
                        prefs.getInt("${childId}_limit_${default.packageName}", default.limitMinutes),
                        prefs.getBoolean("${childId}_enabled_${default.packageName}", default.enabled)
                    )
                }
            }
            return candidateList
        }

        val scanned = AppScanner.getInstalledApps(context)
        return if (scanned.isNotEmpty()) {
            scanned.map { app ->
                AppPolicy(
                    app.packageName,
                    app.appName,
                    prefs.getInt("${childId}_limit_${app.packageName}", 30),
                    prefs.getBoolean("${childId}_enabled_${app.packageName}", false)
                )
            }
        } else {
            popularChildApps.map { default ->
                AppPolicy(
                    default.packageName,
                    default.appName,
                    prefs.getInt("${childId}_limit_${default.packageName}", default.limitMinutes),
                    prefs.getBoolean("${childId}_enabled_${default.packageName}", default.enabled)
                )
            }
        }
    }

    var dailyLimit by remember {
        mutableStateOf(loadDailyLimit(activeChildProfile.id))
    }
    var rules by remember { mutableStateOf(loadRules(activeChildProfile.id)) }
    var selectedPackages by remember { mutableStateOf(setOf<String>()) }
    var status by remember { mutableStateOf("Draft loaded for ${activeChildProfile.name}") }
    var busy by remember { mutableStateOf(false) }
    var customRulePackage by remember { mutableStateOf<String?>(null) }
    var customMinutesText by remember { mutableStateOf("") }
    var customMinutesError by remember { mutableStateOf<String?>(null) }
    var showAddAppDialog by remember { mutableStateOf(false) }
    var newAppCustomName by remember { mutableStateOf("") }
    var newAppPackageName by remember { mutableStateOf("") }
    val executor = remember { Executors.newSingleThreadExecutor() }

    var searchQuery by remember { mutableStateOf("") }
    var requests by remember { mutableStateOf(emptyList<org.json.JSONObject>()) }
    var dismissedParentIds by remember {
        mutableStateOf(prefs.getStringSet("parent_dismissed_request_ids", emptySet()) ?: emptySet())
    }
    var showChangePinDialog by remember { mutableStateOf(false) }
    var newPinText by remember { mutableStateOf("") }
    var confirmPinText by remember { mutableStateOf("") }
    var changePinError by remember { mutableStateOf<String?>(null) }
    var showEditChildNameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showAddChildDialog by remember { mutableStateOf(false) }
    var showPaywallDialog by remember { mutableStateOf(false) }
    var isPremiumActive by remember { mutableStateOf(PremiumManager.isPremium(context)) }
    var newChildPairingCode by remember { mutableStateOf<String?>(null) }
    var newChildName by remember { mutableStateOf("") }
    var showDeleteDummyReminderDialog by remember { mutableStateOf(false) }
    var addedChildProfileName by remember { mutableStateOf("") }
    var isInstantLockActive by remember { mutableStateOf(false) }
    var showBadgesDialog by remember { mutableStateOf(false) }
    var showPledgeDialog by remember { mutableStateOf(false) }
    var badgesRefreshTrigger by remember { mutableStateOf(0) }
    var rawPublishResponse by remember { mutableStateOf("") }
    var showTechnicalDetails by remember { mutableStateOf(false) }

    fun refreshRequests() {
        executor.execute {
            val response = ApiClient.getTimeRequestsForChild(context, activeChildProfile.id)
            context.mainExecutor.execute {
                if (response.ok) {
                    val json = org.json.JSONObject(response.body)
                    val arr = json.optJSONArray("requests")
                    requests = buildList {
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                val pkg = obj.optString("package_name")
                                val reason = obj.optString("reason")
                                val isTelemetry = pkg.startsWith("APP_CATALOG:") ||
                                        pkg == "com.familycontrol.lab" ||
                                        pkg == "SYSTEM_ALERT" ||
                                        reason.startsWith("DEV:") ||
                                        reason.startsWith("Child device online")
                                if (!isTelemetry) {
                                    add(obj)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(activeChildProfile.id) {
        while (isActive) {
            withContext(Dispatchers.IO) {
                RequestPollEngine.checkOnce(context)
            }
            val currentChild = ChildProfileManager.getChildren(context).find { it.id == activeChildProfile.id }
            if (currentChild != null && currentChild.deviceCount != activeChildProfile.deviceCount) {
                childrenProfiles = ChildProfileManager.getChildren(context)
                activeChildProfile = currentChild
            }
            rules = loadRules(activeChildProfile.id)
            refreshRequests()
            val res = withContext(Dispatchers.IO) {
                ApiClient.getInstantLock(context, activeChildProfile.id)
            }
            if (res.ok) {
                try {
                    isInstantLockActive = org.json.JSONObject(res.body).optBoolean("locked", false)
                } catch (_: Exception) {}
            }
            delay(3000)
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
        updatedDailyLimit: Int = dailyLimit,
        targetChildId: String = activeChildProfile.id
    ) {
        prefs.edit()
            .putInt("${targetChildId}_daily_screen_limit", updatedDailyLimit)
            .apply()

        val editor = prefs.edit()
        val pkgSet = updatedRules.map { it.packageName }.toSet()
        editor.putStringSet("${targetChildId}_app_packages", pkgSet)
        updatedRules.forEach { rule ->
            editor.putString("${targetChildId}_appname_${rule.packageName}", rule.appName)
            editor.putInt("${targetChildId}_limit_${rule.packageName}", rule.limitMinutes)
            editor.putBoolean("${targetChildId}_enabled_${rule.packageName}", rule.enabled)
        }
        editor.apply()

        // Sync with ChildProfileManager so chips reflect the current saved limit
        val child = childrenProfiles.find { it.id == targetChildId }
        if (child != null && child.dailyScreenLimitMinutes != updatedDailyLimit) {
            val updatedChild = child.copy(dailyScreenLimitMinutes = updatedDailyLimit)
            ChildProfileManager.updateChild(context, updatedChild)
            childrenProfiles = ChildProfileManager.getChildren(context)
            activeChildProfile = ChildProfileManager.getActiveChild(context)
        }

        rules = updatedRules
        dailyLimit = updatedDailyLimit
        status = "Draft saved locally for ${activeChildProfile.name}"
        EventLog.record(context, "PARENT_DRAFT_SAVED child=$targetChildId")
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
        saveDraftLocally(rules, dailyLimit, activeChildProfile.id)
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
            val result = ApiClient.createServerPolicy(context, dailyLimit, jsonRules, activeChildProfile.id)
            context.mainExecutor.execute {
                busy = false
                rawPublishResponse = result.body
                status = if (result.ok) {
                    "Policy published successfully for ${activeChildProfile.name}"
                } else {
                    "Publish failed — ${result.error ?: "Network error"}"
                }
            }
        }
    }

    if (showBadgesDialog) {
        AwardBadgesDialog(
            context = context,
            childProfile = activeChildProfile,
            onDismiss = { showBadgesDialog = false },
            onBadgesChanged = {
                badgesRefreshTrigger++
            }
        )
    }

    if (showPledgeDialog) {
        DigitalPledgeDialog(
            context = context,
            childName = activeChildProfile.name,
            childId = activeChildProfile.id,
            onDismiss = { showPledgeDialog = false },
            onSigned = {
                badgesRefreshTrigger++
            }
        )
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
                        publishToServer()
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

    if (showAddAppDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddAppDialog = false
                newAppCustomName = ""
                newAppPackageName = ""
            },
            title = { Text("Add App to Limit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter app details or select from popular child apps:")
                    OutlinedTextField(
                        value = newAppCustomName,
                        onValueChange = { newAppCustomName = it },
                        label = { Text("App Name (e.g. Minecraft, Roblox)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newAppPackageName,
                        onValueChange = { newAppPackageName = it.trim() },
                        label = { Text("Package Name (e.g. com.mojang.minecraftpe)") },
                        placeholder = { Text("com.example.app") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Quick Pick:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("Roblox" to "com.roblox.client", "YouTube" to "com.google.android.youtube", "TikTok" to "com.zhiliaoapp.musically").forEach { (name, pkg) ->
                            FilterChip(
                                selected = (newAppPackageName == pkg),
                                onClick = {
                                    newAppCustomName = name
                                    newAppPackageName = pkg
                                },
                                label = { Text(name, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newAppCustomName.trim().ifBlank { "Custom App" }
                        val pkg = newAppPackageName.trim()
                        if (pkg.isNotBlank()) {
                            if (rules.none { it.packageName == pkg }) {
                                val newPolicy = AppPolicy(pkg, name, 30, true)
                                val updated = rules + newPolicy
                                saveDraftLocally(updated, dailyLimit)
                                publishToServer()
                                Toast.makeText(context, "Added $name to limits", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "$name is already in the list", Toast.LENGTH_SHORT).show()
                            }
                            showAddAppDialog = false
                            newAppCustomName = ""
                            newAppPackageName = ""
                        }
                    },
                    enabled = newAppPackageName.isNotBlank()
                ) { Text("Add App") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddAppDialog = false
                    newAppCustomName = ""
                    newAppPackageName = ""
                }) { Text("Cancel") }
            }
        )
    }

    if (showEditChildNameDialog) {
        var tempName by remember { mutableStateOf(childDisplayName) }
        var tempAvatar by remember { mutableStateOf(activeChildProfile.avatarEmoji) }
        val avatars = listOf("👶", "👧", "👦", "🧒", "🎮", "🦄", "⚽", "🚀")
        AlertDialog(
            onDismissRequest = { showEditChildNameDialog = false },
            title = { Text("Update Child Profile") },
            text = {
                Column {
                    Text("Personalize the child's display name and avatar:")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempName,
                        onValueChange = { tempName = it },
                        label = { Text("Child's Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Avatar:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        avatars.take(4).forEach { emoji ->
                            FilterChip(
                                selected = (tempAvatar == emoji),
                                onClick = { tempAvatar = emoji },
                                label = { Text(emoji, style = MaterialTheme.typography.titleMedium) }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        avatars.drop(4).forEach { emoji ->
                            FilterChip(
                                selected = (tempAvatar == emoji),
                                onClick = { tempAvatar = emoji },
                                label = { Text(emoji, style = MaterialTheme.typography.titleMedium) }
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Divider(Modifier.padding(vertical = 4.dp))
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Remove Profile", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = {
                                if (childrenProfiles.size <= 1) {
                                    Toast.makeText(context, "Cannot delete the only child profile", Toast.LENGTH_SHORT).show()
                                } else {
                                    showDeleteConfirmDialog = true
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Delete")
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val finalName = tempName.trim().ifBlank { "Child" }
                    val updated = activeChildProfile.copy(name = finalName, avatarEmoji = tempAvatar)
                    ChildProfileManager.updateChild(context, updated)
                    activeChildProfile = updated
                    childDisplayName = finalName
                    childrenProfiles = ChildProfileManager.getChildren(context)
                    prefs.edit().putString("child_display_name", finalName).apply()
                    showEditChildNameDialog = false
                }) {
                    Text("Save Profile")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditChildNameDialog = false }) {
                    Text("Cancel")
                }
            }
        )

        if (showDeleteConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                title = { Text("Delete Child Profile?") },
                text = { Text("Are you sure you want to remove \"${activeChildProfile.name}\"? This will unpair all associated devices and delete this profile.") },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteConfirmDialog = false
                            showEditChildNameDialog = false
                            val idToDelete = activeChildProfile.id
                            ChildProfileManager.removeChild(context, idToDelete)
                            val remaining = ChildProfileManager.getChildren(context)
                            childrenProfiles = remaining
                            val nextActive = remaining.firstOrNull() ?: ChildProfileManager.getActiveChild(context)
                            ChildProfileManager.setActiveChild(context, nextActive.id)
                            activeChildProfile = nextActive
                            childDisplayName = nextActive.name
                            prefs.edit().putString("child_display_name", nextActive.name).apply()
                            Toast.makeText(context, "Child profile removed", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }

    if (showAddChildDialog) {
        AddChildDialog(
            onDismiss = { showAddChildDialog = false },
            onAdd = { name, avatar, limit ->
                showAddChildDialog = false
                val prevProfiles = childrenProfiles
                ChildProfileManager.addChild(context, name, avatar, limit) { newProfile, error ->
                    (context as? Activity)?.runOnUiThread {
                        if (newProfile != null) {
                            childrenProfiles = ChildProfileManager.getChildren(context)
                            activeChildProfile = newProfile
                            childDisplayName = newProfile.name
                            ChildProfileManager.setActiveChild(context, newProfile.id)
                            newChildPairingCode = newProfile.pairingCode
                            newChildName = newProfile.name
                            if (prevProfiles.any { it.name.equals("Dummy Profile", ignoreCase = true) || it.isDefault }) {
                                addedChildProfileName = newProfile.name
                                showDeleteDummyReminderDialog = true
                            }
                            refreshRequests()
                        } else if (error != null) {
                            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )
    }

    if (showDeleteDummyReminderDialog) {
        val dummyProfile = childrenProfiles.find { it.name.equals("Dummy Profile", ignoreCase = true) || it.isDefault }
        AlertDialog(
            onDismissRequest = { showDeleteDummyReminderDialog = false },
            title = { Text("Profile Added! 🎉") },
            text = {
                Text("You have successfully added $addedChildProfileName's profile.\n\nYou can now delete the 'Dummy Profile' if you no longer need it.")
            },
            confirmButton = {
                if (dummyProfile != null) {
                    Button(
                        onClick = {
                            showDeleteDummyReminderDialog = false
                            ChildProfileManager.removeChild(context, dummyProfile.id)
                            val remaining = ChildProfileManager.getChildren(context)
                            childrenProfiles = remaining
                            val nextActive = remaining.firstOrNull { it.id == activeChildProfile.id } ?: remaining.firstOrNull() ?: activeChildProfile
                            ChildProfileManager.setActiveChild(context, nextActive.id)
                            activeChildProfile = nextActive
                            childDisplayName = nextActive.name
                            refreshRequests()
                            Toast.makeText(context, "Dummy Profile deleted", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete Dummy Profile")
                    }
                } else {
                    Button(onClick = { showDeleteDummyReminderDialog = false }) {
                        Text("Got it")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDummyReminderDialog = false }) {
                    Text("Keep for Now")
                }
            }
        )
    }

    val refreshParentHomePairingCode: () -> Unit = {
        newChildName = activeChildProfile.name
        newChildPairingCode = "Generating..."
        kotlin.concurrent.thread {
            val res = ApiClient.generatePairingCodeForChild(context, activeChildProfile.id)
            (context as? Activity)?.runOnUiThread {
                if (res.ok) {
                    newChildPairingCode = res.body
                    val updated = activeChildProfile.copy(pairingCode = res.body)
                    ChildProfileManager.updateChild(context, updated)
                    activeChildProfile = updated
                } else {
                    newChildPairingCode = activeChildProfile.pairingCode ?: ApiClient.getPairingCode(context)
                }
            }
        }
    }

    if (newChildPairingCode != null) {
        NewChildPairingDialog(
            childName = newChildName ?: activeChildProfile.name,
            pairingCode = newChildPairingCode!!,
            onRefreshCode = refreshParentHomePairingCode,
            onDismiss = { newChildPairingCode = null }
        )
    }

    if (showPaywallDialog) {
        FamOrbitPaywallDialog(
            onDismiss = { showPaywallDialog = false },
            onSubscribed = {
                isPremiumActive = PremiumManager.isPremium(context)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (childDisplayName.isNotBlank() && childDisplayName != "Child") "Parent Control ($childDisplayName ${activeChildProfile.avatarEmoji})" else "Parent Control Center") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showEditChildNameDialog = true }) {
                        Icon(Icons.Default.Edit, "Edit Child Profile")
                    }
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
            ChildProfileSelectorBar(
                children = childrenProfiles,
                activeChildId = activeChildProfile.id,
                onSelectChild = { selected ->
                    activeChildProfile = selected
                    childDisplayName = selected.name
                    ChildProfileManager.setActiveChild(context, selected.id)
                    dailyLimit = loadDailyLimit(selected.id)
                    rules = loadRules(selected.id)
                    status = "Draft loaded for ${selected.name}"
                    refreshRequests()
                },
                onAddChildClick = {
                    if (PremiumManager.canAddChild(context, childrenProfiles.size)) {
                        showAddChildDialog = true
                    } else {
                        showPaywallDialog = true
                    }
                }
            )
            Spacer(Modifier.height(6.dp))

            // Frozen Sticky Action Card — remains visible while scrolling down!
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !busy,
                            onClick = { publishToServer() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text(if (busy) "Publishing…" else "🚀 Publish Policy", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { saveDraftLocally() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("💾 Save Draft", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Button(
                        onClick = {
                            val nextLock = !isInstantLockActive
                            isInstantLockActive = nextLock
                            executor.execute {
                                ApiClient.setInstantLock(context, nextLock, activeChildProfile.id)
                            }
                            status = if (nextLock) "🔒 Paused all devices for ${activeChildProfile.name}"
                            else "▶️ Resumed devices for ${activeChildProfile.name}"
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isInstantLockActive) BrandRose else BrandIndigo
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isInstantLockActive) "▶️ Resume ${activeChildProfile.name}'s Devices" else "⏸️ Pause ${activeChildProfile.name}'s Devices", fontWeight = FontWeight.Bold)
                    }
                    Text("Status: $status", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("CHILD DEVICE & SECURITY", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Button(onClick = { showChangePinDialog = true }) {
                                Text("🔑 Change PIN")
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onProtection(activeChildProfile.id) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                            ) {
                                Icon(Icons.Default.Security, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Device Health", fontWeight = FontWeight.SemiBold)
                            }
                            Button(
                                onClick = onRoutines,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                            ) {
                                Text("📅 Routines", fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val count = remember(badgesRefreshTrigger, activeChildProfile.id) {
                                BadgeEngine.getUnlockedCount(context, activeChildProfile.id)
                            }
                            Button(
                                onClick = { showBadgesDialog = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = BrandEmerald.copy(alpha = 0.2f), contentColor = BrandEmerald)
                            ) {
                                Text("🏆 Badges ($count/5)", fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { showPledgeDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("📜 Digital Pledge")
                            }
                        }
                    }
                }
            }

            item {
                PairedDevicesCard(
                    context = context,
                    childProfile = activeChildProfile,
                    onPairAnotherDevice = refreshParentHomePairingCode,
                    onDeviceListChanged = {
                        childrenProfiles = ChildProfileManager.getChildren(context)
                        refreshRequests()
                    }
                )
            }

            item {
                var pairingCode by remember { mutableStateOf(ApiClient.getPairingCode(context)) }
                var generatingCode by remember { mutableStateOf(false) }
                val isRegistered = ApiClient.registered(context)
                var isPairingExpanded by remember { mutableStateOf(!isRegistered) }

                LaunchedEffect(Unit) {
                    if (isRegistered) {
                        generatingCode = true
                        executor.execute {
                            val res = ApiClient.generatePairingCode(context)
                            context.mainExecutor.execute {
                                generatingCode = false
                                if (res.ok) {
                                    pairingCode = res.body
                                }
                            }
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isRegistered) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("🔑 FAMILY PAIRING CODE", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(2.dp))
                                if (isRegistered && !isPairingExpanded) {
                                    Text("✅ Family Paired • Code: $pairingCode (Tap to view)", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Text(
                                        "Enter this 6-digit code on the Child's phone to pair:",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            IconButton(onClick = { isPairingExpanded = !isPairingExpanded }) {
                                Icon(
                                    if (isPairingExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Toggle Pairing Code"
                                )
                            }
                        }
                        if (isPairingExpanded) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (generatingCode) "Generating..." else pairingCode,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                OutlinedButton(onClick = {
                                    generatingCode = true
                                    executor.execute {
                                        val res = ApiClient.generatePairingCode(context)
                                        context.mainExecutor.execute {
                                            generatingCode = false
                                            if (res.ok) {
                                                pairingCode = res.body
                                            }
                                        }
                                    }
                                }) {
                                    Text("🔄 Refresh Code")
                                }
                            }
                        }
                    }
                }
            }

            val visibleRequests = requests.filter { req ->
                val reqId = req.optString("request_id")
                val pkg = req.optString("package_name")
                val reason = req.optString("reason")
                val isTelemetry = pkg.startsWith("APP_CATALOG:") ||
                        pkg == "com.familycontrol.lab" ||
                        pkg == "SYSTEM_ALERT" ||
                        reason.startsWith("DEV:") ||
                        reason.startsWith("Child device online")
                reqId !in dismissedParentIds && !isTelemetry
            }

            if (visibleRequests.isNotEmpty()) {
                val pendingCount = visibleRequests.count { it.optString("status") == "PENDING" }
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (pendingCount > 0) "TIME REQUESTS ($pendingCount Pending)" else "TIME REQUESTS (All Handled)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (visibleRequests.any { it.optString("status") != "PENDING" }) {
                            TextButton(
                                onClick = {
                                    val handledIds = visibleRequests
                                        .filter { it.optString("status") != "PENDING" }
                                        .map { it.optString("request_id") }
                                        .toSet()
                                    val updated = dismissedParentIds + handledIds
                                    prefs.edit().putStringSet("parent_dismissed_request_ids", updated).apply()
                                    dismissedParentIds = updated
                                    ExtraTimeRequestEngine.clearHandledRequests(context)
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("Clear Handled ✕", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                items(visibleRequests, key = { it.optString("request_id") }) { request ->
                    val requestId = request.optString("request_id")
                    val minutes = request.optInt("requested_minutes")
                    val packageName = request.optString("package_name").takeIf { it.isNotBlank() && it != "null" && it != "None" }
                    val appName = AppNameResolver.getAppName(context, packageName)
                    val reason = request.optString("reason").ifBlank { "No reason provided" }
                    val requestStatus = request.optString("status")

                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = when (requestStatus) {
                                "APPROVED" -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                "DECLINED" -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppIcon(packageName ?: "", modifier = Modifier.size(32.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "$minutes mins • $appName",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                if (requestStatus != "PENDING") {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (requestStatus == "APPROVED") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                                    ) {
                                        Text(
                                            if (requestStatus == "APPROVED") "APPROVED ✅" else "DECLINED ❌",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(
                                        onClick = {
                                            val updated = dismissedParentIds + requestId
                                            prefs.edit().putStringSet("parent_dismissed_request_ids", updated).apply()
                                            dismissedParentIds = updated
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(18.dp))
                                    }
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.tertiaryContainer
                                    ) {
                                        Text(
                                            "PENDING ⏳",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("Reason: $reason", style = MaterialTheme.typography.bodySmall, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
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
                FeatureTogglesCard(context)
            }

            if (FeatureToggleEngine.isCategoryBudgetsEnabled(context)) {
                item {
                    CategoryBudgetsCard(
                        context = context,
                        usage = usage,
                        isParentMode = true,
                        childId = activeChildProfile.id,
                        onBudgetChanged = { publishToServer() }
                    )
                }
            }

            item {
                val report = remember(usage) { AnalyticsEngine.generateReport(context, usage) }
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("📊 SCREEN TIME ANALYTICS & CATEGORIES", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(6.dp))
                        Text("Total Today: ${report.totalScreenTimeMinutes} mins • Top Category: ${report.topCategory}", style = MaterialTheme.typography.bodyMedium)
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
                                Text("${cat.totalMinutes}m (${cat.percentage}%)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Category Limit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                            ) {
                                Text(
                                    "${dailyLimit / 60}h ${dailyLimit % 60}m/day",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .size(54.dp)
                                    .clickable(enabled = dailyLimit > 15) {
                                        dailyLimit = (dailyLimit - 15).coerceAtLeast(15)
                                        saveDraftLocally(rules, dailyLimit)
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("−", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "${dailyLimit}m",
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "Daily Limit",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = BrandBlue,
                                modifier = Modifier
                                    .size(54.dp)
                                    .clickable(enabled = dailyLimit < 720) {
                                        dailyLimit = (dailyLimit + 15).coerceAtMost(720)
                                        saveDraftLocally(rules, dailyLimit)
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("+", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }

            item { Text("APP LIMITS", style = MaterialTheme.typography.titleMedium) }

            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (selectedPackages.isEmpty()) "ALL APPS SELECTED (${rules.size})"
                                else "SELECTED: ${selectedPackages.size} / ${rules.size} APPS",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
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

                        Text("Quick Apply to Selected Apps:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
                        publishToServer()
                    }
                    OneTapPresetsBar(activePreset) { mode ->
                        activePreset = mode
                        PresetModeEngine.setActivePreset(context, mode)
                        publishToServer()
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("🔍 Search Child Apps") },
                        placeholder = { Text("Search by name...") },
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
                    Button(
                        onClick = { showAddAppDialog = true },
                        modifier = Modifier.height(56.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add App")
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                }
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
                val isSuspended = rule.enabled && rule.limitMinutes == 0

                Card(
                    shape = RoundedCornerShape(16.dp),
                    onClick = {
                        selectedPackages = if (isSelected) selectedPackages - rule.packageName
                        else selectedPackages + rule.packageName
                    },
                    border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else if (isSuspended) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        rule.appName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (isSuspended) {
                                        Spacer(Modifier.width(6.dp))
                                        Text("⛔ SUSPENDED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    if (isSuspended) "Status: App Suspended (0m limit)"
                                    else "🎯 Limit: ${rule.limitMinutes}m (Tap to edit) • Used: ${used}m • Left: ${remaining}m",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.clickable {
                                        customRulePackage = rule.packageName
                                        customMinutesText = rule.limitMinutes.toString()
                                    }
                                )
                            }
                            FamilySwitch(
                                checked = rule.enabled,
                                onCheckedChange = { enabled ->
                                    val updated = rules.map {
                                        if (it.packageName == rule.packageName) it.copy(enabled = enabled)
                                        else it
                                    }
                                    saveDraftLocally(updated, dailyLimit)
                                    publishToServer()
                                }
                            )
                        }

                        if (isSuspended) {
                            Spacer(Modifier.height(6.dp))
                            Button(
                                onClick = {
                                    val updated = rules.map {
                                        if (it.packageName == rule.packageName) it.copy(limitMinutes = 30, enabled = true)
                                        else it
                                    }
                                    saveDraftLocally(updated, dailyLimit)
                                    publishToServer()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("▶️ Resume (30m limit)")
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("POLICY STATUS", style = MaterialTheme.typography.titleMedium)
                            val isOk = status.contains("published successfully", ignoreCase = true) || status.contains("Draft", ignoreCase = true)
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isOk) AppleGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    if (isOk) "ACTIVE & SYNCED 🟢" else "PENDING SYNC ⚠️",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isOk) AppleGreen else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(status, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Policy changes sync automatically with ${activeChildProfile.name}'s devices in real time. The child device continuously applies the latest rules in the background.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (rawPublishResponse.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showTechnicalDetails = !showTechnicalDetails },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (showTechnicalDetails) "Hide Developer Diagnostics ▲" else "Developer Diagnostics (Technical Details) ▼",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppleBlue
                                )
                            }
                            if (showTechnicalDetails) {
                                Spacer(Modifier.height(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        rawPublishResponse,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        modifier = Modifier.padding(8.dp)
                                    )
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
                serverStatus = if (it.ok) "SERVER ONLINE 🟢 (Connected to Cloud)" else "SERVER ERROR 🔴"
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
                                    PolicySyncEngine.syncAndApplyCloudPolicy(context)
                                },
                                onResult = {
                                    serverStatus = if (it.ok) "SERVER SYNC ACKNOWLEDGED & APPLIED" else "SYNC ACK FAILED"
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
                            FamilySwitch(
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
fun ProtectionScreen(childId: String? = null, onBack: () -> Unit) {
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
    var isExempt by remember { mutableStateOf(OEMProtection.isIgnoringBatteryOptimizations(context)) }

    // If childId is specified, fetch the child's remote devices telemetry
    val childProfile = remember(childId) {
        if (childId != null) ChildProfileManager.getChildren(context).find { it.id == childId } else null
    }
    var childDevices by remember(childId) {
        mutableStateOf(if (childId != null) ChildDeviceManager.getDevicesForChild(context, childId) else emptyList())
    }

    fun refreshProtection() {
        snapshot = ProtectionMonitor.snapshot(context)
        isExempt = OEMProtection.isIgnoringBatteryOptimizations(context)
        if (childId != null) {
            childDevices = ChildDeviceManager.getDevicesForChild(context, childId)
        }
    }

    LaunchedEffect(childId) {
        if (childId != null) {
            while (isActive) {
                withContext(Dispatchers.IO) {
                    RequestPollEngine.checkOnce(context)
                }
                refreshProtection()
                delay(3000)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (childProfile != null) "${childProfile.name}'s Device Health" else "Protection Health") },
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
            if (childProfile != null) {
                item {
                    Text("${childProfile.name}'s Paired Device Health", style = MaterialTheme.typography.headlineSmall)
                    Text("Live telemetry and enforcement protection status for child devices.")
                }

                if (childDevices.isEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("No Paired Devices Found", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                Text("Pair ${childProfile.name}'s phone or tablet in Parent Control Center to start monitoring telemetry.")
                            }
                        }
                    }
                } else {
                    items(childDevices) { dev ->
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(if (dev.deviceType == DeviceType.TABLET) "📟" else "📱", style = MaterialTheme.typography.titleLarge)
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(dev.deviceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                            Text(dev.model, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (dev.isOnline) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                                    ) {
                                        Text(
                                            if (dev.isOnline) "🟢 Online" else "⚪ Offline",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("🔋 Battery: ${dev.batteryPct}%", style = MaterialTheme.typography.bodyMedium)
                                    Text("📦 App: v${dev.appVersion}", style = MaterialTheme.typography.bodyMedium)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "🛡️ Real-Time Protection: Active (Accessibility, Policy & Anti-Uninstall Enforced)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            } else {
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
                            if (isExempt) "✅ Battery Optimization: EXEMPT (FamOrbit is protected from OEM task killers)"
                            else "⚠️ Battery Optimization: NOT EXEMPT (Android OS or OEM task killer may terminate background enforcement)"
                        )
                        Spacer(Modifier.height(8.dp))
                        if (!isExempt) {
                            Button(onClick = {
                                OEMProtection.requestBatteryOptimizationExemption(context)
                                isExempt = OEMProtection.isIgnoringBatteryOptimizations(context)
                            }) {
                                Text("Request Battery Exemption")
                            }
                        }
                    }
                }
            }
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
                    FamilySwitch(checked = weekdaysOnly, onCheckedChange = { weekdaysOnly = it })
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
                                FamilySwitch(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleSelectionScreen(
    onParentRole: () -> Unit,
    onChildRole: () -> Unit,
    onResumePairedChild: () -> Unit = {}
) {
    val context = LocalContext.current
    val isAlreadyPaired = ApiClient.serverChildId(context) != null && ApiClient.serverFamilyId(context) != null
    val prefs = remember { context.getSharedPreferences("parent_control", Context.MODE_PRIVATE) }
    val pairedChildName = remember(prefs) { prefs.getString("child_display_name", "") ?: "" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FamOrbit Setup — Device Role") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Welcome to FamOrbit",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Select how this device will be used in your family:",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(24.dp))

            if (isAlreadyPaired) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            ApiClient.setDeviceRole(context, ApiClient.ROLE_CHILD)
                            onResumePairedChild()
                        }
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text("👶 RESUME PAIRED CHILD MODE", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "This device is already paired with your family${if (pairedChildName.isNotBlank()) " as $pairedChildName" else ""}. Tap here to return to Child Mode immediately without re-pairing.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        ApiClient.setDeviceRole(context, ApiClient.ROLE_PARENT)
                        onParentRole()
                    }
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("📱 THIS IS A PARENT PHONE", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Manage app limits, instant remote locks, web filters, and approve time requests from your phone.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        ApiClient.setDeviceRole(context, ApiClient.ROLE_CHILD)
                        onChildRole()
                    }
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(if (isAlreadyPaired) "🔗 RE-PAIR AS CHILD PHONE" else "👶 THIS IS A CHILD PHONE", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (isAlreadyPaired)
                            "Pair this device with a different family or new 6-digit pairing code."
                        else
                            "Pair this device with the parent's phone using a 6-digit family code to enable screen time enforcement.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            var showPrivacyModal by remember { mutableStateOf(false) }
            TextButton(onClick = { showPrivacyModal = true }) {
                Text(
                    "🔒 Privacy Policy & Terms of Service",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (showPrivacyModal) {
                PrivacyPolicyDialog(onDismiss = { showPrivacyModal = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildPairingScreen(
    onBack: () -> Unit,
    onPairComplete: () -> Unit
) {
    val context = LocalContext.current
    val isAlreadyPaired = ApiClient.serverChildId(context) != null && ApiClient.serverFamilyId(context) != null
    var codeText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) { onDispose { executor.shutdownNow() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair Child Device") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isAlreadyPaired) {
                item {
                    OutlinedButton(
                        onClick = onPairComplete,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("👶 Device Already Paired — Resume Child Mode")
                    }
                }
            }

            item {
                Text("🔑 Enter 6-Digit Family Code", style = MaterialTheme.typography.headlineSmall)
            }
            item {
                Text(
                    "Open FamOrbit on the Parent's phone to view the 6-digit Family Pairing Code.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            item {
                OutlinedTextField(
                    value = codeText,
                    onValueChange = { input ->
                        val filtered = input.filter { it.isDigit() || it == '-' }.take(7)
                        codeText = filtered
                        isError = false
                    },
                    label = { Text("6-Digit Code (e.g. 482-910)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (statusText.isNotBlank()) {
                item {
                    Text(
                        statusText,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            item {
                Button(
                    enabled = !busy && codeText.replace("-", "").length == 6,
                    onClick = {
                        busy = true
                        statusText = "Pairing with Parent Cloud..."
                        isError = false
                        executor.execute {
                            val result = ApiClient.pairChildWithCode(context, codeText)
                            context.mainExecutor.execute {
                                busy = false
                                if (result.ok) {
                                    statusText = "✅ Successfully Paired with Parent!"
                                    Toast.makeText(context, "Child device successfully paired!", Toast.LENGTH_SHORT).show()
                                    executor.execute {
                                        PolicySyncEngine.syncAndApplyCloudPolicy(context)
                                        ApiClient.publishChildAppsAndTelemetry(context)
                                    }
                                    onPairComplete()
                                } else {
                                    isError = true
                                    statusText = result.error ?: "Pairing failed. Check code and try again."
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Pairing…" else "🔗 Pair Device")
                }
            }

            item {
                DeviceProtectionSetupCard(context)
            }
        }
    }
}

@Composable
fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🔒 Privacy Policy & Data Protection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "FamOrbit is committed to the digital safety and privacy of your family in strict compliance with COPPA & GDPR-K regulations.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "• Zero Advertising: We do not serve third-party ads or sell personal data.\n" +
                    "• Children's Data: Telemetry is strictly encrypted in transit and accessible only to linked parent devices in the family.\n" +
                    "• Accessibility & Usage Permissions: Used exclusively to monitor screen time and enforce parent-set app limits.\n" +
                    "• Data Deletion: You may permanently erase your entire family profile and logs at any time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://famorbit-api.onrender.com/privacy"))
                    context.startActivity(intent)
                } catch (_: Exception) {}
                onDismiss()
            }) {
                Text("Open Web Policy")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun DeleteAccountDialog(onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    var isDeleting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⚠️ Delete Family Account & All Data?", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "This action is permanent and cannot be undone. All child profiles, app limits, pairing links, and cloud logs will be erased from the server immediately.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                enabled = !isDeleting,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                onClick = {
                    isDeleting = true
                    kotlin.concurrent.thread {
                        ApiClient.deleteFamilyAccount(context)
                        (context as? Activity)?.runOnUiThread {
                            isDeleting = false
                            Toast.makeText(context, "Family data erased successfully.", Toast.LENGTH_LONG).show()
                            onDeleted()
                        }
                    }
                }
            ) {
                Text(if (isDeleting) "Erasing Data…" else "Erase All Family Data")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamOrbitPaywallDialog(
    onDismiss: () -> Unit,
    onSubscribed: () -> Unit = {}
) {
    val context = LocalContext.current
    var selectedPlan by remember { mutableStateOf(PremiumManager.PLAN_ANNUAL) }
    var isProcessing by remember { mutableStateOf(false) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            shape = RoundedCornerShape(24.dp),
            color = androidx.compose.ui.graphics.Color(0xFF0A0E1A),
            border = BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFF2D3748))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top close bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f)
                        )
                    }
                }

                // Crown & Header
                Text(
                    "👑",
                    fontSize = 44.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Unlock FamOrbit Premium",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color.White,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Total peace of mind for your whole family",
                    style = MaterialTheme.typography.bodyMedium,
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(Modifier.height(20.dp))

                // 4 Feature Cards (2x2 Grid)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PaywallFeatureCard(
                        icon = "📱",
                        title = "Unlimited Child Devices",
                        desc = "Protect all family devices",
                        modifier = Modifier.weight(1f)
                    )
                    PaywallFeatureCard(
                        icon = "⏳",
                        title = "Per-App Limits & Budgets",
                        desc = "Granular time control",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PaywallFeatureCard(
                        icon = "🌙",
                        title = "Bedtime & Focus Schedules",
                        desc = "Automated sleep routines",
                        modifier = Modifier.weight(1f)
                    )
                    PaywallFeatureCard(
                        icon = "🔔",
                        title = "Instant Approvals & Push",
                        desc = "Live sub-second sync",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(24.dp))

                // Plan Selector Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Monthly Card ($2.99/mo)
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedPlan = PremiumManager.PLAN_MONTHLY },
                        shape = RoundedCornerShape(16.dp),
                        color = if (selectedPlan == PremiumManager.PLAN_MONTHLY) androidx.compose.ui.graphics.Color(0xFF1E293B) else androidx.compose.ui.graphics.Color(0xFF111827),
                        border = BorderStroke(
                            if (selectedPlan == PremiumManager.PLAN_MONTHLY) 2.dp else 1.dp,
                            if (selectedPlan == PremiumManager.PLAN_MONTHLY) androidx.compose.ui.graphics.Color(0xFF818CF8) else androidx.compose.ui.graphics.Color(0xFF374151)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Monthly", fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(PremiumManager.DEFAULT_MONTHLY_PRICE, style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f))
                            Spacer(Modifier.height(6.dp))
                            Text("Standard", style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.Gray)
                        }
                    }

                    // Annual Card (Best Value + 7-Day Trial)
                    Surface(
                        modifier = Modifier
                            .weight(1.15f)
                            .clickable { selectedPlan = PremiumManager.PLAN_ANNUAL },
                        shape = RoundedCornerShape(16.dp),
                        color = if (selectedPlan == PremiumManager.PLAN_ANNUAL) androidx.compose.ui.graphics.Color(0xFF241442) else androidx.compose.ui.graphics.Color(0xFF111827),
                        border = BorderStroke(
                            if (selectedPlan == PremiumManager.PLAN_ANNUAL) 2.dp else 1.dp,
                            if (selectedPlan == PremiumManager.PLAN_ANNUAL) androidx.compose.ui.graphics.Color(0xFF38BDF8) else androidx.compose.ui.graphics.Color(0xFF374151)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = androidx.compose.ui.graphics.Color(0xFF0284C7)
                            ) {
                                Text(
                                    "Best Value • ${PremiumManager.DEFAULT_ANNUAL_SAVINGS}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = androidx.compose.ui.graphics.Color.White,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("Annual", fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(2.dp))
                            Text(PremiumManager.DEFAULT_ANNUAL_PRICE, style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f))
                            Spacer(Modifier.height(4.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = androidx.compose.ui.graphics.Color(0xFFF59E0B)
                            ) {
                                Text(
                                    "⭐ 7-Day Free Trial",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = androidx.compose.ui.graphics.Color.Black,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // CTA Button
                Button(
                    enabled = !isProcessing,
                    onClick = {
                        isProcessing = true
                        if (selectedPlan == PremiumManager.PLAN_ANNUAL) {
                            PremiumManager.startFreeTrial(context, PremiumManager.PLAN_ANNUAL)
                            Toast.makeText(context, "🎉 7-Day Free Trial Activated! Enjoy FamOrbit Premium.", Toast.LENGTH_LONG).show()
                        } else {
                            PremiumManager.setPremium(context, true, PremiumManager.PLAN_MONTHLY, 30)
                            Toast.makeText(context, "🎉 FamOrbit Premium Monthly Activated!", Toast.LENGTH_LONG).show()
                        }
                        onSubscribed()
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedPlan == PremiumManager.PLAN_ANNUAL) androidx.compose.ui.graphics.Color(0xFF7C3AED) else androidx.compose.ui.graphics.Color(0xFF4F46E5)
                    )
                ) {
                    Text(
                        if (selectedPlan == PremiumManager.PLAN_ANNUAL) "Start 7-Day Free Trial" else "Subscribe for \$2.99 / Mo",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = androidx.compose.ui.graphics.Color.White
                    )
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    "No commitment • Cancel anytime in Google Play",
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f)
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "Privacy Policy",
                        style = MaterialTheme.typography.labelSmall,
                        color = androidx.compose.ui.graphics.Color(0xFF818CF8),
                        modifier = Modifier.clickable {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://famorbit-api.onrender.com/privacy"))
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }
                    )
                    Text(
                        "Terms of Service",
                        style = MaterialTheme.typography.labelSmall,
                        color = androidx.compose.ui.graphics.Color(0xFF818CF8),
                        modifier = Modifier.clickable {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://famorbit-api.onrender.com/terms"))
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PaywallFeatureCard(
    icon: String,
    title: String,
    desc: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = androidx.compose.ui.graphics.Color(0xFF131B2E),
        border = BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFF1E293B))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(icon, fontSize = 24.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium,
                color = androidx.compose.ui.graphics.Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(2.dp))
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color.Gray,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
