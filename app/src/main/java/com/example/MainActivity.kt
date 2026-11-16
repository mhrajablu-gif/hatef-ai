package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.engine.PersianDateEngine
import com.example.ui.screens.AddEditReminderDialog
import com.example.ui.screens.AiAssistantScreen
import com.example.ui.screens.CalendarScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.OverdueScreen
import com.example.ui.screens.ReportsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.VoiceCaptureSheet
import com.example.ui.screens.VoiceLabScreen
import com.example.ui.theme.CoralUrgent
import com.example.ui.theme.HatefAssistantTheme
import com.example.ui.theme.TealAccent
import com.example.ui.theme.TealPrimary
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.NavTab
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.service.ai.configureAppCheck(this)
        enableEdgeToEdge()

        // Check if launched from Quick Mic Widget
        if (intent?.action == "com.example.ACTION_QUICK_VOICE") {
            viewModel.openVoiceSheet()
        }

        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            val isDark = when (themeMode) {
                com.example.ui.theme.ThemeMode.DARK -> true
                com.example.ui.theme.ThemeMode.LIGHT -> false
                com.example.ui.theme.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            HatefAssistantTheme(darkTheme = isDark) {
                MainAppContent(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == "com.example.ACTION_QUICK_VOICE") {
            viewModel.openVoiceSheet()
        }
    }
}

@Composable
fun MainAppContent(viewModel: MainViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val overdueReminders by viewModel.overdueReminders.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Request notification permission on Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Handled */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Show info toast / snackbar
    LaunchedEffect(uiState.infoMessage) {
        uiState.infoMessage?.let {
            scope.launch {
                snackbarHostState.showSnackbar(it)
                viewModel.clearInfoMessage()
            }
        }
    }

    // Handle back button on voice lab or sub-tabs
    BackHandler(enabled = uiState.isVoiceLabOpen) {
        viewModel.closeVoiceLab()
    }

    BackHandler(enabled = !uiState.isVoiceLabOpen && uiState.currentTab != NavTab.HOME) {
        viewModel.setTab(NavTab.HOME)
    }

    if (uiState.isVoiceLabOpen) {
        VoiceLabScreen(onBack = { viewModel.closeVoiceLab() })
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                HatefBottomNavigation(
                    currentTab = uiState.currentTab,
                    overdueCount = overdueReminders.size,
                    onTabSelected = { viewModel.setTab(it) }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (uiState.currentTab) {
                    NavTab.HOME -> HomeScreen(viewModel = viewModel)
                    NavTab.CALENDAR -> CalendarScreen(viewModel = viewModel)
                    NavTab.ASSISTANT -> AiAssistantScreen(viewModel = viewModel)
                    NavTab.OVERDUE -> OverdueScreen(viewModel = viewModel)
                    NavTab.SETTINGS -> SettingsScreen(viewModel = viewModel)
                }
            }

            // Add / Edit Manual Dialog
            if (uiState.isAddEditOpen) {
                AddEditReminderDialog(
                    initialReminder = uiState.editingReminder,
                    onDismiss = { viewModel.closeAddDialog() },
                    onSave = { reminder -> viewModel.saveReminder(reminder) }
                )
            }

            // Voice Capture Bottom Sheet
            if (uiState.isVoiceSheetOpen) {
                VoiceCaptureSheet(
                    viewModel = viewModel,
                    onDismiss = { viewModel.closeVoiceSheet() }
                )
            }
        }
    }
}

data class NavItem(val tab: NavTab, val icon: ImageVector, val label: String)

@Composable
fun HatefBottomNavigation(
    currentTab: NavTab,
    overdueCount: Int,
    onTabSelected: (NavTab) -> Unit
) {
    val items = listOf(
        NavItem(NavTab.HOME, Icons.Filled.Home, "داشبورد"),
        NavItem(NavTab.CALENDAR, Icons.Filled.CalendarMonth, "تقویم"),
        NavItem(NavTab.ASSISTANT, Icons.Filled.AutoAwesome, "دستیار"),
        NavItem(NavTab.OVERDUE, Icons.Filled.AlarmOff, "عقب‌افتاده"),
        NavItem(NavTab.SETTINGS, Icons.Filled.Settings, "تنظیمات")
    )

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp
    ) {
        items.forEach { item ->
            val isSelected = currentTab == item.tab
            NavigationBarItem(
                selected = isSelected,
                onClick = { onTabSelected(item.tab) },
                icon = {
                    if (item.tab == NavTab.OVERDUE && overdueCount > 0) {
                        BadgedBox(
                            badge = {
                                Badge(containerColor = CoralUrgent) {
                                    Text(
                                        text = PersianDateEngine.toPersianDigits(overdueCount),
                                        color = androidx.compose.ui.graphics.Color.White,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        ) {
                            Icon(item.icon, contentDescription = item.label, modifier = Modifier.size(24.dp))
                        }
                    } else {
                        Icon(item.icon, contentDescription = item.label, modifier = Modifier.size(24.dp))
                    }
                },
                label = {
                    Text(
                        text = item.label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = TealPrimary,
                    selectedTextColor = TealPrimary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.testTag("nav_tab_${item.tab.name.lowercase()}")
            )
        }
    }
}
