package com.security.gsmrelay.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.security.gsmrelay.BuildConfig
import com.security.gsmrelay.viewmodel.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsersScreen(
    modifier: Modifier = Modifier,
    viewModel: AppViewModel,
    onBack: () -> Unit
) {
    val relay by viewModel.selectedRelay.collectAsState()
    val filterGroup by viewModel.filterGroup.collectAsState()
    val history by viewModel.history.collectAsState()
    val commands by viewModel.commands.collectAsState()
    val notifications by viewModel.notifications.collectAsState()
    var showAddUser by remember { mutableStateOf(false) }
    var showQuery by remember { mutableStateOf(false) }
    var showChangePassword by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    var statusFilter by remember { mutableStateOf("all") }
    var searchQuery by remember { mutableStateOf("") }
    val config = LocalConfiguration.current
    val compactLayout = config.screenWidthDp < 420 || config.fontScale > 1.1f
    val isGateway = BuildConfig.IS_GATEWAY

    val selectedRelay = relay ?: return
    val usersWithPhone = selectedRelay.users.filter { it.phone.isNotBlank() }
    val filteredUsers = if (filterGroup == "all") usersWithPhone else usersWithPhone.filter { it.group == filterGroup }
    val emptySlots = selectedRelay.users.filter { it.phone.isBlank() && it.known }
    val anyFreeSlots = selectedRelay.users.filter { it.phone.isBlank() }
    val occupiedCount = selectedRelay.users.count { it.phone.isNotBlank() }
    val freeCount = selectedRelay.users.count { it.known && it.phone.isBlank() }
    val unknownCount = selectedRelay.users.count { !it.known }
    val now = System.currentTimeMillis()
    val pendingQueryFromQueue = commands.any { item ->
        (item.status.equals("pending", ignoreCase = true) ||
            item.status.equals("sent_waiting", ignoreCase = true)) &&
            sameRelayNumber(item.relayPhone, selectedRelay.phoneNumber) &&
            (item.description.startsWith("Interogare utilizatori", ignoreCase = true) ||
                Regex("^\\d{4}AL\\d{3}#\\d{3}#$").matches(item.command.trim().uppercase()))
    }
    val pendingQueryFromLocalRecent = history.any {
        it.status == "trimis" &&
            it.description.startsWith("Interogare utilizatori") &&
            sameRelayNumber(it.relayPhone, selectedRelay.phoneNumber) &&
            (now - it.timestamp) < 90_000
    }
    val pendingQuery = pendingQueryFromQueue || pendingQueryFromLocalRecent
    val lastNotif = notifications.firstOrNull()

    val context = LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = readTextFromUri(context, uri)
            if (text.isNotBlank()) {
                viewModel.importCsv(text)
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            val csv = viewModel.exportCsv()
            writeTextToUri(context, uri, csv)
        }
    }

    LaunchedEffect(selectedRelay.id) {
        viewModel.syncCommands(showNotifications = false)
    }

    LaunchedEffect(pendingQuery) {
        if (pendingQuery && isGateway) {
            repeat(24) {
                kotlinx.coroutines.delay(5000)
                viewModel.syncFromInbox()
            }
        }
    }

    val usersToShow = selectedRelay.users.filter { user ->
        when (statusFilter) {
            "occupied" -> user.phone.isNotBlank()
            "free" -> user.known && user.phone.isBlank()
            "unknown" -> !user.known
            else -> true
        }
    }.filter { user ->
        if (searchQuery.isBlank()) return@filter true
        val q = searchQuery.trim().lowercase()
        val phone = user.phone.lowercase()
        val group = user.group.lowercase()
        val id = user.id.toString().padStart(3, '0')
        phone.contains(q) || group.contains(q) || id.contains(q)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        item {
            TextButton(onClick = onBack) {
                Text("<- Inapoi")
            }
            Text(selectedRelay.name)
            Spacer(Modifier.height(4.dp))
            Text("Telefon: ${selectedRelay.phoneNumber}")
            if (selectedRelay.location.isNotBlank()) {
                Text("Locatie: ${selectedRelay.location}")
            }
            Spacer(Modifier.height(8.dp))
            Text("Utilizatori: ${usersWithPhone.size} / 999")
            Spacer(Modifier.height(4.dp))
            Text("Ocupate: $occupiedCount   Libere: $freeCount   Necunoscute: $unknownCount")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { statusFilter = "all" }) { Text("Toate") }
                TextButton(onClick = { statusFilter = "occupied" }) { Text("Ocupate") }
                TextButton(onClick = { statusFilter = "free" }) { Text("Libere") }
                TextButton(onClick = { statusFilter = "unknown" }) { Text("Necunoscute") }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Cauta: telefon, grup, ID (ex. 007)") },
                modifier = Modifier.fillMaxWidth()
            )
            if (searchQuery.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { searchQuery = "" }) { Text("Sterge cautarea") }
            }
        }

        if (pendingQuery) {
            item {
                Spacer(Modifier.height(8.dp))
                Card {
                    Text(
                        "Interogare in curs... astept raspuns SMS",
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        if (lastNotif != null) {
            item {
                Spacer(Modifier.height(8.dp))
                Card {
                    Text(
                        lastNotif.message,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            if (compactLayout) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showAddUser = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 40.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Adauga")
                    }
                    Button(
                        onClick = { importLauncher.launch(arrayOf("text/*", "text/csv")) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 40.dp)
                    ) {
                        Icon(Icons.Filled.FileUpload, contentDescription = null)
                        Text("Import CSV")
                    }
                    Button(
                        onClick = { exportLauncher.launch("${selectedRelay.name}_utilizatori.csv") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 40.dp)
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null)
                        Text("Export CSV")
                    }
                }
            } else {
                ActionButtonsRow {
                    Button(onClick = { showAddUser = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Adauga")
                    }
                    Button(onClick = { importLauncher.launch(arrayOf("text/*", "text/csv")) }) {
                        Icon(Icons.Filled.FileUpload, contentDescription = null)
                        Text("Import CSV")
                    }
                    Button(onClick = { exportLauncher.launch("${selectedRelay.name}_utilizatori.csv") }) {
                        Icon(Icons.Filled.Save, contentDescription = null)
                        Text("Export CSV")
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            if (compactLayout) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showQuery = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 40.dp)
                    ) {
                        Text("Interogare SMS")
                    }
                    Button(
                        onClick = { showChangePassword = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 40.dp)
                    ) {
                        Text("Schimba parola")
                    }
                    Button(
                        onClick = { showTimer = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 40.dp)
                    ) {
                        Text("Temporizare")
                    }
                }
            } else {
                ActionButtonsRow {
                    Button(onClick = { showQuery = true }) {
                        Text("Interogare SMS")
                    }
                    Button(onClick = { showChangePassword = true }) {
                        Text("Schimba parola")
                    }
                    Button(onClick = { showTimer = true }) {
                        Text("Temporizare")
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            if (compactLayout) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isGateway) {
                        Button(
                            onClick = { viewModel.syncFromInbox() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 40.dp)
                        ) {
                            Text("Sync SMS")
                        }
                    }
                    Button(
                        onClick = { viewModel.allowAllAccess() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 40.dp)
                    ) {
                        Text("ALL")
                    }
                    Button(
                        onClick = { viewModel.allowAuthorizedOnly() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 40.dp)
                    ) {
                        Text("AUT")
                    }
                }
            } else {
                ActionButtonsRow {
                    if (isGateway) {
                        Button(onClick = { viewModel.syncFromInbox() }) {
                            Text("Sync SMS")
                        }
                    }
                    Button(onClick = { viewModel.allowAllAccess() }) {
                        Text("ALL")
                    }
                    Button(onClick = { viewModel.allowAuthorizedOnly() }) {
                        Text("AUT")
                    }
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }

        items(usersToShow) { user ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.padding(end = 8.dp)) {
                        val status = when {
                            !user.known -> "Necunoscut"
                            user.phone.isBlank() -> "Empty"
                            else -> "Ocupat"
                        }
                        val label = if (user.phone.isNotBlank()) user.phone else status
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val color = when {
                                !user.known -> Color(0xFF9E9E9E)
                                user.phone.isBlank() -> Color(0xFF4CAF50)
                                else -> Color(0xFFF44336)
                            }
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(color, CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Pozitie ${user.id.toString().padStart(3, '0')}")
                        }
                        Text(label)
                        if (user.phone.isNotBlank()) {
                            Text("Grup: ${user.group}")
                        }
                    }
                    if (user.phone.isNotBlank()) {
                        TextButton(onClick = { viewModel.deleteUser(user.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
        }
    }

    if (showAddUser) {
        AddUserDialog(
            emptySlots = emptySlots.map { it.id },
            anyFreeSlots = anyFreeSlots.map { it.id },
            onDismiss = { showAddUser = false },
            onAdd = { id, phone, name, group ->
                val ok = viewModel.addUser(id, phone, name, group)
                if (ok) showAddUser = false
                ok
            }
        )
    }

    if (showQuery) {
        QueryUsersDialog(
            onDismiss = { showQuery = false },
            onQuery = { start, end ->
                viewModel.queryUsers(start, end)
                showQuery = false
            }
        )
    }

    if (showChangePassword) {
        ChangePasswordDialog(
            onDismiss = { showChangePassword = false },
            onSave = { newPassword ->
                viewModel.changeRelayPassword(newPassword)
                showChangePassword = false
            }
        )
    }

    if (showTimer) {
        TimerDialog(
            onDismiss = { showTimer = false },
            onSave = { seconds ->
                viewModel.setRelayTimer(seconds)
                showTimer = false
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionButtonsRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddUserDialog(
    emptySlots: List<Int>,
    anyFreeSlots: List<Int>,
    onDismiss: () -> Unit,
    onAdd: (Int, String, String, String) -> Boolean
) {
    val defaultSlot = emptySlots.firstOrNull() ?: anyFreeSlots.firstOrNull() ?: 1
    var userId by remember(defaultSlot) { mutableStateOf(defaultSlot) }
    var phone by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("general") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adauga utilizator") },
        text = {
            Column {
                OutlinedTextField(
                    value = userId.toString(),
                    onValueChange = { userId = it.toIntOrNull() ?: userId },
                    label = { Text("Pozitie ID (001-999)") }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Numar telefon *") }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nume (optional)") }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text("Grup") }
                )
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val ok = onAdd(userId, phone, name, group)
                if (!ok) error = "Pozitia este ocupata sau nu este verificata"
            }) {
                Text("Trimite")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Anuleaza")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueryUsersDialog(
    onDismiss: () -> Unit,
    onQuery: (Int, Int) -> Unit
) {
    var start by remember { mutableStateOf("1") }
    var end by remember { mutableStateOf("999") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Interogare utilizatori") },
        text = {
            Column {
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    label = { Text("Start (001-999)") }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = end,
                    onValueChange = { end = it },
                    label = { Text("End (001-999)") }
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val s = start.toIntOrNull() ?: 1
                val e = end.toIntOrNull() ?: s
                onQuery(s.coerceIn(1, 999), e.coerceIn(1, 999))
            }) { Text("Trimite") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Anuleaza") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var pwd by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schimba parola releu") },
        text = {
            Column {
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    label = { Text("Parola noua (4 cifre)") }
                )
            }
        },
        confirmButton = {
            Button(onClick = { if (pwd.length == 4) onSave(pwd) }) { Text("Salveaza") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Anuleaza") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerDialog(
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit
) {
    var seconds by remember { mutableStateOf("0") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Temporizare releu") },
        text = {
            Column {
                OutlinedTextField(
                    value = seconds,
                    onValueChange = { seconds = it },
                    label = { Text("Secunde (0-999)") }
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(seconds.toIntOrNull() ?: 0) }) { Text("Trimite") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Anuleaza") } }
    )
}

private fun sameRelayNumber(a: String, b: String): Boolean {
    val aDigits = a.filter(Char::isDigit).takeLast(8)
    val bDigits = b.filter(Char::isDigit).takeLast(8)
    return aDigits.isNotBlank() && aDigits == bDigits
}
