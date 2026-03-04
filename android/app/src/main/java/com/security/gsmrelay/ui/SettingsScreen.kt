package com.security.gsmrelay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.security.gsmrelay.viewmodel.AppViewModel

@Composable
fun SettingsScreen(modifier: Modifier = Modifier, viewModel: AppViewModel) {
    val serverConfig by viewModel.serverConfig.collectAsState()
    val notifications by viewModel.notifications.collectAsState()
    val urlState = remember(serverConfig.baseUrl) { mutableStateOf(serverConfig.baseUrl) }
    val userState = remember(serverConfig.username) { mutableStateOf(serverConfig.username) }
    val passState = remember(serverConfig.password) { mutableStateOf(serverConfig.password) }
    val gatewayState = remember(serverConfig.gatewayId) { mutableStateOf(serverConfig.gatewayId) }
    val masterState = remember(serverConfig.masterPhone) { mutableStateOf(serverConfig.masterPhone) }
    val canSync = urlState.value.isNotBlank() &&
        userState.value.isNotBlank() &&
        passState.value.isNotBlank() &&
        gatewayState.value.isNotBlank()
    val lastNotif = notifications.firstOrNull()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Conectare server", style = MaterialTheme.typography.titleLarge)
        Text(
            "Datele de mai jos sunt folosite pentru sync intre aplicatia Android si desktop.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = urlState.value,
                    onValueChange = { urlState.value = it },
                    label = { Text("Server URL (https://...)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = userState.value,
                    onValueChange = { userState.value = it },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = passState.value,
                    onValueChange = { passState.value = it },
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = gatewayState.value,
                    onValueChange = { gatewayState.value = it },
                    label = { Text("Gateway ID (telefon) *") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = masterState.value,
                    onValueChange = { masterState.value = it },
                    label = { Text("Master phone (A001)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            viewModel.updateServerConfig(
                                urlState.value.trim(),
                                userState.value.trim(),
                                passState.value,
                                gatewayState.value.trim(),
                                masterState.value.trim()
                            )
                        },
                        enabled = canSync
                    ) {
                        Text("Save")
                    }
                    Button(
                        onClick = { viewModel.syncFromServer() },
                        enabled = canSync
                    ) {
                        Text("Sync now")
                    }
                }
            }
        }

        AssistChip(
            onClick = {},
            label = { Text(if (canSync) "Configuratie valida" else "Configuratie incompleta") }
        )

        if (lastNotif != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Status", fontWeight = FontWeight.SemiBold)
                    Text(lastNotif.message)
                }
            }
        }
    }
}
