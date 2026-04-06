package com.security.gsmrelay

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import com.security.gsmrelay.service.CommandPollService
import com.security.gsmrelay.ui.LocationDetailScreen
import com.security.gsmrelay.ui.LocationListScreen
import com.security.gsmrelay.ui.RelayDetailScreen
import com.security.gsmrelay.ui.SettingsScreen
import com.security.gsmrelay.ui.theme.GSMRelayTheme
import com.security.gsmrelay.viewmodel.AppViewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GSMRelayTheme {
                GSMRelayManagerApp(viewModel = AppViewModel(application))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GSMRelayManagerApp(viewModel: AppViewModel) {
    var activeTab by remember { mutableStateOf("relays") }
    var selectedLocation by remember { mutableStateOf<String?>(null) }
    val selectedRelay by viewModel.selectedRelay.collectAsState()
    val serverConfig by viewModel.serverConfig.collectAsState()
    val relays by viewModel.relays.collectAsState()
    val appTitle = if (BuildConfig.IS_GATEWAY) "GSM Relay Gateway" else "GSM Relay Client"

    val permissions = if (BuildConfig.IS_GATEWAY) {
        listOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        listOf(Manifest.permission.POST_NOTIFICATIONS)
    }

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            launcher.launch(missing.toTypedArray())
        }
        viewModel.syncFromServer()
        if (BuildConfig.IS_GATEWAY) {
            val serviceIntent = Intent(context, CommandPollService::class.java)
            startForegroundService(context, serviceIntent)
        }
    }

    LaunchedEffect(serverConfig) {
        if (BuildConfig.IS_GATEWAY) return@LaunchedEffect
        if (!serverConfig.isValid()) return@LaunchedEffect
        while (true) {
            delay(60_000)
            viewModel.syncFromServer()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        if (activeTab == "relays") {
                            selectedRelay?.name ?: (selectedLocation ?: appTitle)
                        } else {
                            "Setari"
                        }
                    )
                },
                actions = {
                    if (activeTab == "relays" && selectedRelay == null) {
                        Badge {
                            Text(relays.size.toString())
                        }
                    }
                    if (serverConfig.isValid()) {
                        Icon(
                            imageVector = Icons.Filled.CloudDone,
                            contentDescription = "Server configurat"
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Phone, contentDescription = "Relays") },
                    label = { Text("Relee") },
                    selected = activeTab == "relays",
                    onClick = {
                        activeTab = "relays"
                        selectedLocation = null
                        viewModel.selectRelay(null)
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                    label = { Text("Setari") },
                    selected = activeTab == "settings",
                    onClick = { activeTab = "settings" }
                )
            }
        }
    ) { innerPadding ->
        Surface(modifier = Modifier.padding(innerPadding)) {
            when (activeTab) {
                "relays" -> {
                    if (selectedRelay == null) {
                        if (selectedLocation == null) {
                            LocationListScreen(
                                viewModel = viewModel,
                                onLocationClick = { location ->
                                    selectedLocation = location
                                }
                            )
                        } else {
                            LocationDetailScreen(
                                locationName = selectedLocation ?: "",
                                viewModel = viewModel,
                                onBack = { selectedLocation = null },
                                onRelayClick = { relay ->
                                    viewModel.selectRelay(relay)
                                }
                            )
                        }
                    } else {
                        RelayDetailScreen(
                            viewModel = viewModel,
                            onBack = {
                                viewModel.selectRelay(null)
                            }
                        )
                    }
                }

                "settings" -> SettingsScreen(viewModel = viewModel)
            }
        }
    }
}
