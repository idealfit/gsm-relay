package com.security.gsmrelay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.security.gsmrelay.model.Relay
import com.security.gsmrelay.viewmodel.RelaySetupOptions
import com.security.gsmrelay.viewmodel.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayListScreen(
    modifier: Modifier = Modifier,
    viewModel: AppViewModel,
    onRelayClick: (Relay) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var relayToEdit by remember { mutableStateOf<Relay?>(null) }
    var relayToDelete by remember { mutableStateOf<Relay?>(null) }
    val relays by viewModel.relays.collectAsState()
    val pendingCommands by viewModel.pendingCommands.collectAsState()
    val config = LocalConfiguration.current
    val compactHeader = config.screenWidthDp < 360 || config.fontScale > 1.1f

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add Relay")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (compactHeader) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Relee configurate", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Administreaza releele si utilizatorii dintr-un singur loc.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = { showDialog = true },
                        modifier = Modifier.defaultMinSize(minHeight = 40.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add Relay")
                        Spacer(Modifier.width(6.dp))
                        Text("Releu nou")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Relee configurate", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Administreaza releele si utilizatorii dintr-un singur loc.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { showDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add Relay")
                        Spacer(Modifier.width(6.dp))
                        Text("Releu nou")
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("Total: ${relays.size}") })
                if (pendingCommands.isNotEmpty()) {
                    AssistChip(
                        onClick = {},
                        label = { Text("In curs: ${pendingCommands.size}") }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            if (relays.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Nu exista relee configurate", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Adauga primul releu pentru a incepe.",
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(relays) { relay ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                ) {
                                    Text(relay.name, style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(2.dp))
                                    Text(relay.phoneNumber)
                                    if (relay.location.isNotBlank()) {
                                        Text(
                                            relay.location,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    AssistChip(
                                        onClick = {},
                                        label = {
                                            Text("${relay.users.count { it.phone.isNotBlank() }} / 999 utilizatori")
                                        }
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { onRelayClick(relay) }) {
                                        Icon(Icons.Filled.Edit, contentDescription = "Detalii")
                                    }
                                    IconButton(onClick = { relayToEdit = relay }) {
                                        Icon(Icons.Filled.Edit, contentDescription = "Editeaza")
                                    }
                                    IconButton(onClick = { relayToDelete = relay }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Sterge")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showDialog) {
            AddRelayDialog(
                onDismiss = { showDialog = false },
                title = "Releu nou",
                confirmLabel = "Adauga",
                showQueryRange = true,
                onAddRelay = { name, phone, password, location, queryStart, queryEnd, options ->
                    viewModel.addRelay(name, phone, password, location, queryStart, queryEnd, options)
                    showDialog = false
                }
            )
        }

        if (relayToEdit != null) {
            val relay = relayToEdit ?: return@Scaffold
            AddRelayDialog(
                onDismiss = { relayToEdit = null },
                title = "Editeaza releu",
                confirmLabel = "Salveaza",
                initialName = relay.name,
                initialPhoneNumber = relay.phoneNumber,
                initialPassword = relay.password,
                initialLocation = relay.location,
                showQueryRange = false,
                onAddRelay = { name, phone, password, location, _, _, _ ->
                    viewModel.updateRelayDetails(relay.id, name, phone, password, location)
                    relayToEdit = null
                }
            )
        }

        if (relayToDelete != null) {
            val relay = relayToDelete ?: return@Scaffold
            AlertDialog(
                onDismissRequest = { relayToDelete = null },
                title = { Text("Sterge releu") },
                text = { Text("Stergi releul ${relay.name}? Actiunea nu poate fi anulata.") },
                confirmButton = {
                    Button(onClick = {
                        viewModel.deleteRelay(relay.id)
                        relayToDelete = null
                    }) {
                        Text("Sterge")
                    }
                },
                dismissButton = {
                    Button(onClick = { relayToDelete = null }) {
                        Text("Anuleaza")
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRelayDialog(
    onDismiss: () -> Unit,
    onAddRelay: (String, String, String, String, Int, Int, RelaySetupOptions) -> Unit,
    title: String,
    confirmLabel: String,
    showQueryRange: Boolean = false,
    initialName: String = "",
    initialPhoneNumber: String = "",
    initialPassword: String = "1234",
    initialLocation: String = "",
    initialQueryStart: String = "001",
    initialQueryEnd: String = "999"
) {
    var name by remember { mutableStateOf(initialName) }
    var phoneNumber by remember { mutableStateOf(initialPhoneNumber) }
    var password by remember { mutableStateOf(initialPassword) }
    var location by remember { mutableStateOf(initialLocation) }
    var queryStart by remember { mutableStateOf(initialQueryStart) }
    var queryEnd by remember { mutableStateOf(initialQueryEnd) }
    var setupForcePassword by remember { mutableStateOf(true) }
    var setupSetTime by remember { mutableStateOf(true) }
    var setupSetMaster by remember { mutableStateOf(true) }
    var setupConfirmOn by remember { mutableStateOf(true) }
    var setupConfirmOff by remember { mutableStateOf(true) }
    var setupQueryUsers by remember { mutableStateOf(true) }
    var setupAutoAdmins by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nume releu *") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = { phoneNumber = it },
                    label = { Text("Numar telefon releu *") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Parola (4 cifre)") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Locatie (optional)") }
                )
                if (showQueryRange) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = queryStart,
                        onValueChange = { queryStart = it.filter(Char::isDigit).take(3) },
                        label = { Text("Interogare start (001-999)") }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = queryEnd,
                        onValueChange = { queryEnd = it.filter(Char::isDigit).take(3) },
                        label = { Text("Interogare end (001-999)") }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Comenzi initiale automate")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = setupForcePassword, onCheckedChange = { setupForcePassword = it })
                        Text("Reset parola la 2005")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = setupSetTime, onCheckedChange = { setupSetTime = it })
                        Text("Setare data/ora")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = setupSetMaster, onCheckedChange = { setupSetMaster = it })
                        Text("Setare master (A001)")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = setupConfirmOn, onCheckedChange = { setupConfirmOn = it })
                        Text("Mesaj confirmare ON")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = setupConfirmOff, onCheckedChange = { setupConfirmOff = it })
                        Text("Mesaj confirmare OFF")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = setupQueryUsers, onCheckedChange = { setupQueryUsers = it })
                        Text("Interogare utilizatori")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = setupAutoAdmins, onCheckedChange = { setupAutoAdmins = it })
                        Text("Inregistrare automata useri admin")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank() || phoneNumber.isBlank() || password.length != 4) return@Button
                    val start = (queryStart.toIntOrNull() ?: 1).coerceIn(1, 999)
                    val end = (queryEnd.toIntOrNull() ?: 999).coerceIn(1, 999)
                    val options = RelaySetupOptions(
                        forcePasswordReset = setupForcePassword,
                        setDateTime = setupSetTime,
                        setMaster = setupSetMaster,
                        setConfirmOn = setupConfirmOn,
                        setConfirmOff = setupConfirmOff,
                        queryUsers = setupQueryUsers,
                        autoAddAdmins = setupAutoAdmins
                    )
                    onAddRelay(name, phoneNumber, password, location, start, end, options)
                }
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Anuleaza")
            }
        }
    )
}
